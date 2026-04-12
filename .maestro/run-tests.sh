#!/usr/bin/env bash
# Wrapper to run all Maestro tests with proper setup
# Usage: .maestro/run-tests.sh [optional flow file]
# No set -e: a single flow failure should not abort the whole suite

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_ID="ai.talkingrock.lithium.debug"
LISTENER="$APP_ID/ai.talkingrock.lithium.service.LithiumNotificationListener"

export PATH="$PATH:$HOME/Android/Sdk/platform-tools:$HOME/.maestro/bin"

echo "=== Lithium Maestro Test Runner ==="
echo "Started at: $(date '+%Y-%m-%d %H:%M:%S')"

# Ensure phone is awake and unlocked
echo ""
echo "Waking device..."
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
adb shell input keyevent 82
sleep 1

# ---------------------------------------------------------------
# Phase 1: Setup screen test (permissions revoked)
# ---------------------------------------------------------------
echo ""
echo "=== Phase 1: Setup screen test (permissions revoked) ==="
echo "Revoking permissions..."
adb shell cmd notification disallow_listener "$LISTENER" 2>&1 || true
adb shell appops set "$APP_ID" android:get_usage_stats deny 2>&1 || true
adb shell pm revoke "$APP_ID" android.permission.READ_CONTACTS 2>&1 || true
adb shell am force-stop "$APP_ID" 2>&1 || true
sleep 1

T0=$(date +%s)
if maestro test "$SCRIPT_DIR/01_setup_screen.yaml"; then
    echo "PASSED: 01_setup_screen ($(( $(date +%s) - T0 ))s)"
    SETUP_RESULT="PASSED"
else
    echo "FAILED: 01_setup_screen ($(( $(date +%s) - T0 ))s)"
    SETUP_RESULT="FAILED"
fi

# ---------------------------------------------------------------
# Phase 2: Grant all permissions for the main suite
# ---------------------------------------------------------------
echo ""
echo "=== Phase 2: Granting permissions for main test suite ==="

echo "Granting notification listener..."
adb shell cmd notification allow_listener "$LISTENER" 2>&1 || true

echo "Granting usage stats..."
adb shell appops set "$APP_ID" android:get_usage_stats allow 2>&1 || true

echo "Granting contacts..."
adb shell pm grant "$APP_ID" android.permission.READ_CONTACTS 2>&1 || true

echo "Sending test notifications to exercise the classifier..."
# Social
adb shell cmd notification post -t "user123 liked your photo" "ig1" "Instagram" 2>&1 || true
# Promotional
adb shell cmd notification post -t "Flash Sale — 50% off everything today only" "promo1" "ShopApp" 2>&1 || true
# OTP / transactional
adb shell cmd notification post -t "Your verification code is 482910" "otp1" "BankApp" 2>&1 || true
# Ongoing-style (repeat title to simulate persistence)
adb shell cmd notification post -t "Sync in progress" "sync1" "BackupApp" 2>&1 || true
# Contact-like title (person name)
adb shell cmd notification post -t "Alice: are you free tonight?" "msg1" "Messages" 2>&1 || true

sleep 2
echo "All permissions granted and test data sent."

# ---------------------------------------------------------------
# Phase 2.5: Complete onboarding (clearState wiped the pref)
# ---------------------------------------------------------------
echo ""
echo "=== Phase 2.5: Completing onboarding flow ==="
if maestro test "$SCRIPT_DIR/complete-onboarding.yaml"; then
    echo "Onboarding completed — app is on briefing screen."
else
    echo "WARNING: Onboarding completion failed — remaining tests may fail."
fi
echo ""

# ---------------------------------------------------------------
# Phase 3: Run all flows sequentially (or a single flow if specified)
# ---------------------------------------------------------------
if [ -n "$1" ]; then
    echo "=== Running single flow: $1 ==="
    T0=$(date +%s)
    maestro test "$1"
    echo "Finished in $(( $(date +%s) - T0 ))s"
else
    echo "=== Phase 3: Running main test suite ==="
    PASSED=0
    FAILED=0
    TOTAL_T0=$(date +%s)
    ERRORS=""

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
        echo ""
        echo "--- Running: $name ---"
        T0=$(date +%s)
        if maestro test "$flow"; then
            elapsed=$(( $(date +%s) - T0 ))
            echo "PASSED: $name (${elapsed}s)"
            PASSED=$((PASSED + 1))
        else
            elapsed=$(( $(date +%s) - T0 ))
            echo "FAILED: $name (${elapsed}s)"
            FAILED=$((FAILED + 1))
            ERRORS="$ERRORS  - $name\n"
        fi
    done

    TOTAL_ELAPSED=$(( $(date +%s) - TOTAL_T0 ))
    TOTAL=$((PASSED + FAILED + 1))   # +1 for setup test

    echo ""
    echo "================================"
    echo "Suite finished in ${TOTAL_ELAPSED}s"
    echo "Setup (01):  $SETUP_RESULT"
    echo "Suite (02-15): $PASSED passed, $FAILED failed out of $((PASSED + FAILED))"
    echo "Grand total: $TOTAL tests (includes 13_training_tab, 14_suggestion_approve, 15_queue_screen)"
    if [ -n "$ERRORS" ]; then
        echo ""
        echo "Failed flows:"
        echo -e "$ERRORS"
    fi
    echo "================================"
fi
