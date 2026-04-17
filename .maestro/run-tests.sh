#!/usr/bin/env bash
# Wrapper to run all Maestro tests with proper setup
# Usage: .maestro/run-tests.sh [optional flow file]
#
# Outputs:
#   .maestro/results/<timestamp>/
#     summary.md                 — pass/fail table + failure details
#     run.log                    — full console output (unbuffered, via tee)
#     <flow>.logcat              — per-flow logcat
#     <flow>.maestro-dir         — symlink to ~/.maestro/tests/<ts> for that flow
#
# No set -e: a single flow failure should not abort the whole suite.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_ID="ai.talkingrock.lithium.debug"
LISTENER="$APP_ID/ai.talkingrock.lithium.service.LithiumNotificationListener"

if [ -n "$ANDROID_SERIAL" ]; then
    MAESTRO_DEVICE_FLAG="--device $ANDROID_SERIAL"
else
    MAESTRO_DEVICE_FLAG=""
fi

export PATH="$PATH:$HOME/Android/Sdk/platform-tools:$HOME/.maestro/bin"

# Detect physical device vs emulator. Emulator serials start with "emulator-".
# On physical devices we skip destructive flows (purge-all-data) by default so
# we can build up real learning data. Override with ALLOW_DESTRUCTIVE=1.
DEVICE_IS_EMULATOR=0
case "${ANDROID_SERIAL:-}" in
    emulator-*) DEVICE_IS_EMULATOR=1 ;;
esac
if [ "$DEVICE_IS_EMULATOR" = "1" ] || [ "${ALLOW_DESTRUCTIVE:-0}" = "1" ]; then
    SKIP_DESTRUCTIVE=0
else
    SKIP_DESTRUCTIVE=1
fi

# Results directory for this run
RUN_TS="$(date '+%Y-%m-%d_%H%M%S')"
RESULTS_DIR="$SCRIPT_DIR/results/$RUN_TS"
mkdir -p "$RESULTS_DIR"
RUN_LOG="$RESULTS_DIR/run.log"
SUMMARY="$RESULTS_DIR/summary.md"

# Mirror all output to run.log (unbuffered — drops the old `| tail -120` trap)
exec > >(tee -a "$RUN_LOG") 2>&1

echo "=== Lithium Maestro Test Runner ==="
echo "Run timestamp: $RUN_TS"
echo "Results dir:   $RESULTS_DIR"
echo "Started at:    $(date '+%Y-%m-%d %H:%M:%S')"
if [ "$SKIP_DESTRUCTIVE" = "1" ]; then
    echo "Device mode:   PHYSICAL — skipping destructive flows (09_purge_data)"
    echo "               Set ALLOW_DESTRUCTIVE=1 to run them anyway."
else
    echo "Device mode:   EMULATOR — running full suite"
fi

# -----------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------

# Run one maestro flow, capture its ~/.maestro/tests/<ts> dir, logcat, and
# print the failure tail if it fails. Sets globals: LAST_MAESTRO_DIR,
# LAST_FLOW_RESULT, LAST_FLOW_ELAPSED.
run_flow() {
    local flow="$1"
    local name
    name="$(basename "$flow" .yaml)"
    local t0
    t0=$(date +%s)

    echo ""
    echo "--- Running: $name ---"

    # Snapshot which maestro test dirs exist before this run so we can
    # identify the new one afterward.
    local before_marker="$RESULTS_DIR/.before-$name"
    ls "$HOME/.maestro/tests/" 2>/dev/null | sort > "$before_marker"

    adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} logcat -c 2>/dev/null || true

    local rc=0
    maestro ${MAESTRO_DEVICE_FLAG:-} test "$flow" || rc=$?
    LAST_FLOW_ELAPSED=$(( $(date +%s) - t0 ))

    # Capture logcat tail for this flow
    adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} logcat -d > "$RESULTS_DIR/$name.logcat" 2>/dev/null || true

    # Identify new maestro dirs created during this flow. On failure Maestro
    # may split artifacts across two sibling dirs (one with maestro.log, one
    # with the ❌ screenshot + commands JSON), so we collect all new dirs and
    # pick the one with maestro.log as the primary (for grepping), and
    # separately locate the screenshot regardless of which dir holds it.
    local new_dirs=()
    while IFS= read -r d; do
        [ -n "$d" ] && new_dirs+=("$HOME/.maestro/tests/$d")
    done < <(ls "$HOME/.maestro/tests/" 2>/dev/null | sort | comm -13 "$before_marker" -)
    rm -f "$before_marker"

    LAST_MAESTRO_DIR=""
    local log_dir="" shot="" ai_report=""
    for d in "${new_dirs[@]}"; do
        [ -f "$d/maestro.log" ] && log_dir="$d"
        local s
        s=$(ls "$d"/screenshot-❌-* 2>/dev/null | head -1)
        [ -n "$s" ] && shot="$s"
        [ -f "$d/ai-report-$name.html" ] && ai_report="$d/ai-report-$name.html"
    done
    # Prefer log_dir as the primary symlink target (it has the full command
    # trace); if absent, fall back to the last new dir.
    if [ -n "$log_dir" ]; then
        LAST_MAESTRO_DIR="$log_dir"
    elif [ ${#new_dirs[@]} -gt 0 ]; then
        LAST_MAESTRO_DIR="${new_dirs[-1]}"
    fi
    [ -n "$LAST_MAESTRO_DIR" ] && ln -sfn "$LAST_MAESTRO_DIR" "$RESULTS_DIR/$name.maestro-dir"
    # Second symlink for the artifact dir if it differs from log_dir
    if [ -n "$shot" ]; then
        local shot_dir
        shot_dir="$(dirname "$shot")"
        [ "$shot_dir" != "$LAST_MAESTRO_DIR" ] \
            && ln -sfn "$shot_dir" "$RESULTS_DIR/$name.artifacts-dir"
    fi

    if [ $rc -eq 0 ]; then
        echo "PASSED: $name (${LAST_FLOW_ELAPSED}s)"
        LAST_FLOW_RESULT="PASSED"
    else
        echo "FAILED: $name (${LAST_FLOW_ELAPSED}s)"
        LAST_FLOW_RESULT="FAILED"
        if [ -n "$log_dir" ] && [ -f "$log_dir/maestro.log" ]; then
            echo ""
            echo "  Failure tail (from $log_dir/maestro.log):"
            grep -E "FAILED|ERROR|CommandFailed|Element not found|Assertion" \
                "$log_dir/maestro.log" 2>/dev/null \
                | sed 's/metadata CommandMetadata.*//' \
                | tail -6 | sed 's/^/    /'
        fi
        [ -n "$shot" ]      && echo "  Screenshot: $shot"
        [ -n "$ai_report" ] && echo "  AI report:  $ai_report"
    fi
    return $rc
}

# Append a row to the summary.md flow table
write_row() {
    local name="$1" result="$2" elapsed="$3" maestro_dir="$4"
    local icon="✅"; [ "$result" = "FAILED" ] && icon="❌"
    local link=""
    if [ -n "$maestro_dir" ]; then
        link="[artifacts]($maestro_dir)"
    fi
    echo "| $icon $name | $result | ${elapsed}s | $link |" >> "$SUMMARY"
}

# -----------------------------------------------------------------
# Prep summary.md
# -----------------------------------------------------------------
{
    echo "# Maestro run $RUN_TS"
    echo ""
    echo "- Device: ${ANDROID_SERIAL:-default}"
    echo "- Started: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""
    echo "## Flows"
    echo ""
    echo "| Flow | Result | Time | Artifacts |"
    echo "|------|--------|------|-----------|"
} > "$SUMMARY"

# -----------------------------------------------------------------
# Wake device
# -----------------------------------------------------------------
echo ""
echo "Waking device..."
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
adb shell input keyevent 82
sleep 1

# -----------------------------------------------------------------
# Phase 1: Setup screen test (permissions revoked)
# -----------------------------------------------------------------
echo ""
echo "=== Phase 1: Setup screen test (permissions revoked) ==="
echo "Revoking permissions..."
adb shell cmd notification disallow_listener "$LISTENER" 2>&1 || true
adb shell appops set "$APP_ID" android:get_usage_stats deny 2>&1 || true
adb shell pm revoke "$APP_ID" android.permission.READ_CONTACTS 2>&1 || true
adb shell am force-stop "$APP_ID" 2>&1 || true
sleep 1

run_flow "$SCRIPT_DIR/01_setup_screen.yaml" || true
write_row "01_setup_screen" "$LAST_FLOW_RESULT" "$LAST_FLOW_ELAPSED" "$LAST_MAESTRO_DIR"
SETUP_RESULT="$LAST_FLOW_RESULT"

# -----------------------------------------------------------------
# Phase 2: Grant permissions + seed notifications
# -----------------------------------------------------------------
echo ""
echo "=== Phase 2: Granting permissions for main test suite ==="
adb shell cmd notification allow_listener "$LISTENER" 2>&1 || true
adb shell appops set "$APP_ID" android:get_usage_stats allow 2>&1 || true
adb shell pm grant "$APP_ID" android.permission.READ_CONTACTS 2>&1 || true

# Debug-receiver manifest-presence check. Flow 18 depends on
# SuggestionInjectReceiver (app/src/debug/) being present in the installed
# APK. If the APK on device was built before the receiver was added, the
# INJECT_SUGGESTION broadcast silently goes nowhere and flow 18 times out.
# Catch this early with a clear message so the dev knows to reinstall.
if ! adb shell dumpsys package "$APP_ID" 2>/dev/null | grep -q "INJECT_SUGGESTION"; then
    echo ""
    echo "WARNING: installed debug APK does not register INJECT_SUGGESTION receiver."
    echo "         Flow 18 (suggestion_approve) will fail. To fix:"
    echo "         cd $REPO_DIR && ./gradlew :app:installDebug"
    echo "         (or equivalent)"
    echo ""
fi

echo "Sending test notifications..."
adb shell cmd notification post -t "user123 liked your photo" "ig1" "Instagram" 2>&1 || true
adb shell cmd notification post -t "Flash Sale — 50% off everything today only" "promo1" "ShopApp" 2>&1 || true
adb shell cmd notification post -t "Your verification code is 482910" "otp1" "BankApp" 2>&1 || true
adb shell cmd notification post -t "Sync in progress" "sync1" "BackupApp" 2>&1 || true
adb shell cmd notification post -t "Alice: are you free tonight?" "msg1" "Messages" 2>&1 || true
sleep 2

# -----------------------------------------------------------------
# Phase 2.5: Complete onboarding — hard gate on the rest of the suite
# -----------------------------------------------------------------
echo ""
echo "=== Phase 2.5: Completing onboarding flow ==="
if ! run_flow "$SCRIPT_DIR/complete-onboarding.yaml"; then
    echo ""
    echo "ABORT: complete-onboarding failed — the remaining flows depend on a"
    echo "post-onboarding app state and would produce misleading results."
    write_row "complete-onboarding" "$LAST_FLOW_RESULT" "$LAST_FLOW_ELAPSED" "$LAST_MAESTRO_DIR"
    {
        echo ""
        echo "## Status: ABORTED"
        echo ""
        echo "Onboarding completion failed. Suite halted before Phase 3."
    } >> "$SUMMARY"
    echo ""
    echo "Summary: $SUMMARY"
    exit 1
fi
write_row "complete-onboarding" "$LAST_FLOW_RESULT" "$LAST_FLOW_ELAPSED" "$LAST_MAESTRO_DIR"

# -----------------------------------------------------------------
# Helper: rule-enforcement HTTP bridge (required by flow 16).
# Maestro 2.x's runScript sandbox has no Java interop, so adb operations
# for flow 16 are delegated via http.get calls to this local server. We
# start it after onboarding (the rule-enforcement flow needs a post-
# onboarding app state anyway) and ensure it dies on exit.
# -----------------------------------------------------------------
RULE_HELPER_PORT=18765
RULE_HELPER_PID=""
if [ -f "$SCRIPT_DIR/rule-enforcement-helper.py" ]; then
    ANDROID_SERIAL="${ANDROID_SERIAL:-}" python3 "$SCRIPT_DIR/rule-enforcement-helper.py" \
        --port "$RULE_HELPER_PORT" >> "$RESULTS_DIR/rule-enforcement-helper.log" 2>&1 &
    RULE_HELPER_PID=$!
    # Give the server a moment to bind
    for _ in 1 2 3 4 5; do
        if curl -fsS "http://127.0.0.1:$RULE_HELPER_PORT/__nonexistent__" \
                -o /dev/null 2>&1 \
            || curl -fsS -I "http://127.0.0.1:$RULE_HELPER_PORT/" -o /dev/null 2>&1 \
            || nc -z 127.0.0.1 "$RULE_HELPER_PORT" 2>/dev/null; then
            break
        fi
        sleep 0.3
    done
    echo "rule-enforcement helper: PID=$RULE_HELPER_PID port=$RULE_HELPER_PORT"
fi

cleanup_helper() {
    if [ -n "$RULE_HELPER_PID" ] && kill -0 "$RULE_HELPER_PID" 2>/dev/null; then
        kill "$RULE_HELPER_PID" 2>/dev/null || true
        wait "$RULE_HELPER_PID" 2>/dev/null || true
        echo "rule-enforcement helper: stopped (PID=$RULE_HELPER_PID)"
    fi
}
trap cleanup_helper EXIT

# -----------------------------------------------------------------
# Phase 3: Main suite (or single flow if specified)
# -----------------------------------------------------------------
if [ -n "$1" ]; then
    echo ""
    echo "=== Running single flow: $1 ==="
    run_flow "$1" || true
    write_row "$(basename "$1" .yaml)" "$LAST_FLOW_RESULT" "$LAST_FLOW_ELAPSED" "$LAST_MAESTRO_DIR"
else
    echo ""
    echo "=== Phase 3: Running main test suite ==="
    PASSED=0
    FAILED=0
    TOTAL_T0=$(date +%s)
    FAILED_NAMES=()

    for flow in \
        "$SCRIPT_DIR/02_setup_to_briefing.yaml" \
        "$SCRIPT_DIR/03_briefing_screen.yaml" \
        "$SCRIPT_DIR/04_navigate_all_tabs.yaml" \
        "$SCRIPT_DIR/05_create_rule.yaml" \
        "$SCRIPT_DIR/06_settings_interactions.yaml" \
        "$SCRIPT_DIR/07_run_analysis.yaml" \
        "$SCRIPT_DIR/13_training_tab.yaml" \
        "$SCRIPT_DIR/14_suggestion_approve.yaml" \
        "$SCRIPT_DIR/15_queue_screen.yaml" \
        "$SCRIPT_DIR/16_rule_enforcement.yaml" \
        "$SCRIPT_DIR/17_queue_enforcement.yaml" \
        "$SCRIPT_DIR/18_suggestion_approve.yaml" \
        "$SCRIPT_DIR/19_qa_plumbing.yaml" \
        $([ "$SKIP_DESTRUCTIVE" = "1" ] || echo "$SCRIPT_DIR/09_purge_data.yaml") \
        "$SCRIPT_DIR/10_stress_navigation.yaml" \
        "$SCRIPT_DIR/11_cold_start.yaml" \
        "$SCRIPT_DIR/12_permission_revoke.yaml"; do

        [ -f "$flow" ] || continue
        name=$(basename "$flow" .yaml)
        run_flow "$flow" || true
        write_row "$name" "$LAST_FLOW_RESULT" "$LAST_FLOW_ELAPSED" "$LAST_MAESTRO_DIR"
        if [ "$LAST_FLOW_RESULT" = "PASSED" ]; then
            PASSED=$((PASSED + 1))
        else
            FAILED=$((FAILED + 1))
            FAILED_NAMES+=("$name")
        fi
    done

    TOTAL_ELAPSED=$(( $(date +%s) - TOTAL_T0 ))

    echo ""
    echo "================================"
    echo "Suite finished in ${TOTAL_ELAPSED}s"
    echo "Setup (01):  $SETUP_RESULT"
    echo "Suite (02-15): $PASSED passed, $FAILED failed out of $((PASSED + FAILED))"
    if [ ${#FAILED_NAMES[@]} -gt 0 ]; then
        echo ""
        echo "Failed flows:"
        for n in "${FAILED_NAMES[@]}"; do
            echo "  - $n"
            echo "      log:        $RESULTS_DIR/$n.maestro-dir/maestro.log"
            echo "      logcat:     $RESULTS_DIR/$n.logcat"
            adir="$RESULTS_DIR/$n.artifacts-dir"
            [ -e "$adir" ] || adir="$RESULTS_DIR/$n.maestro-dir"
            shot=$(ls "$adir"/screenshot-❌-* 2>/dev/null | head -1)
            [ -n "$shot" ] && echo "      screenshot: $shot"
        done
    fi
    echo ""
    echo "Full summary: $SUMMARY"
    echo "================================"

    {
        echo ""
        echo "## Totals"
        echo ""
        echo "- Suite time: ${TOTAL_ELAPSED}s"
        echo "- Passed: $PASSED"
        echo "- Failed: $FAILED"
        if [ ${#FAILED_NAMES[@]} -gt 0 ]; then
            echo ""
            echo "## Failures"
            echo ""
            for n in "${FAILED_NAMES[@]}"; do
                ldir="$RESULTS_DIR/$n.maestro-dir"
                echo "### $n"
                echo ""
                echo '```'
                if [ -f "$ldir/maestro.log" ]; then
                    grep -E "FAILED|ERROR|CommandFailed|Element not found|Assertion" \
                        "$ldir/maestro.log" 2>/dev/null \
                        | sed 's/metadata CommandMetadata.*//' | tail -6
                fi
                echo '```'
                echo ""
                echo "- Maestro log dir: \`$ldir\`"
                echo "- Logcat: \`$RESULTS_DIR/$n.logcat\`"
                adir="$RESULTS_DIR/$n.artifacts-dir"
                [ -e "$adir" ] || adir="$ldir"
                shot=$(ls "$adir"/screenshot-❌-* 2>/dev/null | head -1)
                [ -n "$shot" ] && echo "- Screenshot: \`$shot\`"
                ai=$(ls "$adir"/ai-report-*.html 2>/dev/null | head -1)
                [ -n "$ai" ] && echo "- AI report: \`$ai\`"
                echo ""
            done
        fi
    } >> "$SUMMARY"
fi

# -----------------------------------------------------------------
# Rotation: keep last 30 runs in .maestro/results and 30 in ~/.maestro/tests
# -----------------------------------------------------------------
prune_to() {
    local dir="$1" keep="$2"
    [ -d "$dir" ] || return 0
    ls -1 "$dir" 2>/dev/null | sort | head -n -"$keep" | while read -r old; do
        rm -rf "$dir/$old"
    done
}
prune_to "$SCRIPT_DIR/results" 30
prune_to "$HOME/.maestro/tests" 30

exit 0
