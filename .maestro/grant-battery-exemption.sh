#!/usr/bin/env bash
# Grant battery optimization exemption and Doze whitelist for the Lithium app.
# Run before Maestro CI or WorkManager tests to prevent the OS from killing
# background jobs mid-run (especially TierBackfillWorker and AiAnalysisWorker).
#
# Android version notes:
#   - Works on Android 10+ (API 29+). The cmd deviceidle whitelist command
#     was introduced in Android 6 (API 23).
#   - On Android 14+ (API 34), 'appops' set may require adb root on some builds.
#     Use 'adb shell settings' as a fallback.
#   - On physical Pixel devices running Android 15+, battery settings are
#     enforced at the hardware level — this exemption only affects software Doze.

APP_ID="${1:-ai.talkingrock.lithium.debug}"

echo "Granting battery optimization exemption for $APP_ID..."

# Disconnect battery simulation (keeps device in a "plugged in" power state).
# This prevents Doze from activating during the test run.
adb shell dumpsys battery unplug 2>/dev/null || true

# Add to Doze whitelist — prevents the OS from suspending background jobs.
adb shell cmd deviceidle whitelist "+$APP_ID" 2>/dev/null || true

# Allow REQUEST_IGNORE_BATTERY_OPTIMIZATIONS via appops (Android 9+).
# This is the appops equivalent of the user tapping "Don't optimize" in Settings.
adb shell appops set "$APP_ID" REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true

echo "Battery exemption granted for $APP_ID"
echo ""
echo "To restore normal battery behavior after testing, run:"
echo "  adb shell dumpsys battery reset"
echo "  adb shell cmd deviceidle whitelist -$APP_ID"
