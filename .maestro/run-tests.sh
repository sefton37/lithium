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

    # Identify the new maestro dir created during this flow
    local new_dir
    new_dir=$(ls "$HOME/.maestro/tests/" 2>/dev/null | sort \
        | comm -13 "$before_marker" - | tail -1)
    rm -f "$before_marker"
    if [ -n "$new_dir" ]; then
        LAST_MAESTRO_DIR="$HOME/.maestro/tests/$new_dir"
        ln -sfn "$LAST_MAESTRO_DIR" "$RESULTS_DIR/$name.maestro-dir"
    else
        LAST_MAESTRO_DIR=""
    fi

    if [ $rc -eq 0 ]; then
        echo "PASSED: $name (${LAST_FLOW_ELAPSED}s)"
        LAST_FLOW_RESULT="PASSED"
    else
        echo "FAILED: $name (${LAST_FLOW_ELAPSED}s)"
        LAST_FLOW_RESULT="FAILED"
        if [ -n "$LAST_MAESTRO_DIR" ] && [ -f "$LAST_MAESTRO_DIR/maestro.log" ]; then
            echo ""
            echo "  Failure tail (from $LAST_MAESTRO_DIR/maestro.log):"
            grep -E "FAILED|ERROR|CommandFailed|Element not found|Assertion" \
                "$LAST_MAESTRO_DIR/maestro.log" 2>/dev/null \
                | sed 's/metadata CommandMetadata.*//' \
                | tail -6 | sed 's/^/    /'
            local shot
            shot=$(ls "$LAST_MAESTRO_DIR"/screenshot-❌-* 2>/dev/null | head -1)
            [ -n "$shot" ] && echo "  Screenshot: $shot"
            [ -f "$LAST_MAESTRO_DIR/ai-report-$name.html" ] \
                && echo "  AI report:  $LAST_MAESTRO_DIR/ai-report-$name.html"
        fi
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
        "$SCRIPT_DIR/09_purge_data.yaml" \
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
            echo "      screenshot: $(ls "$RESULTS_DIR/$n.maestro-dir"/screenshot-❌-* 2>/dev/null | head -1)"
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
                echo "- Maestro dir: \`$ldir\`"
                echo "- Logcat: \`$RESULTS_DIR/$n.logcat\`"
                shot=$(ls "$ldir"/screenshot-❌-* 2>/dev/null | head -1)
                [ -n "$shot" ] && echo "- Screenshot: \`$shot\`"
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
