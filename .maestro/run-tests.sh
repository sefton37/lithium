#!/usr/bin/env bash
# Wrapper to run all Maestro tests with proper setup
# Usage: .maestro/run-tests.sh [optional flow file]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_ID="ai.talkingrock.lithium.debug"
LISTENER="$APP_ID/ai.talkingrock.lithium.service.LithiumNotificationListener"

export PATH="$PATH:$HOME/Android/Sdk/platform-tools:$HOME/.maestro/bin"

echo "=== Lithium Maestro Test Runner ==="

# Ensure phone is awake and unlocked
echo "Waking device..."
adb shell input keyevent KEYCODE_WAKEUP
sleep 1
adb shell input keyevent 82
sleep 1

# First: run test 01 (setup screen) with permissions revoked
echo ""
echo "=== Phase 1: Setup screen test (permissions revoked) ==="
echo "Revoking permissions..."
adb shell cmd notification disallow_listener "$LISTENER" 2>&1 || true
adb shell appops set "$APP_ID" android:get_usage_stats deny 2>&1 || true
adb shell pm revoke "$APP_ID" android.permission.READ_CONTACTS 2>&1 || true
adb shell am force-stop "$APP_ID" 2>&1 || true

maestro test "$SCRIPT_DIR/01_setup_screen.yaml" && echo "PASSED: 01_setup_screen" || echo "FAILED: 01_setup_screen"

# Grant all permissions for remaining tests
echo ""
echo "=== Phase 2: Granting permissions for main test suite ==="
echo "Granting notification listener..."
adb shell cmd notification allow_listener "$LISTENER"
echo "Granting usage stats..."
adb shell appops set "$APP_ID" android:get_usage_stats allow
echo "Granting contacts..."
adb shell pm grant "$APP_ID" android.permission.READ_CONTACTS
echo "Sending test notifications..."
adb shell cmd notification post -t "Test from Instagram" "ig1" "user123 liked your photo" 2>&1 || true
adb shell cmd notification post -t "Flash Sale!" "promo1" "50% off everything today only" 2>&1 || true
adb shell cmd notification post -t "Your code is 482910" "otp1" "Use this code to verify" 2>&1 || true
sleep 2

echo "All permissions granted and test data sent."
echo ""

# Run remaining tests
if [ -n "$1" ]; then
    echo "=== Running single flow: $1 ==="
    maestro test "$1"
else
    echo "=== Phase 3: Running main test suite ==="
    PASSED=0
    FAILED=0
    ERRORS=""

    for flow in "$SCRIPT_DIR"/0{2,3,4,5,6,7,8,9}_*.yaml "$SCRIPT_DIR"/1{0,1}_*.yaml; do
        [ -f "$flow" ] || continue
        name=$(basename "$flow" .yaml)
        echo ""
        echo "--- Running: $name ---"
        if maestro test "$flow"; then
            echo "PASSED: $name"
            PASSED=$((PASSED + 1))
        else
            echo "FAILED: $name"
            FAILED=$((FAILED + 1))
            ERRORS="$ERRORS  - $name\n"
        fi
    done

    echo ""
    echo "================================"
    echo "Results: $PASSED passed, $FAILED failed out of $((PASSED + FAILED + 1)) total (including setup)"
    if [ -n "$ERRORS" ]; then
        echo "Failed flows:"
        echo -e "$ERRORS"
    fi
    echo "================================"
fi
