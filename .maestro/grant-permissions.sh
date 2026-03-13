#!/usr/bin/env bash
# Pre-grant all permissions for Lithium debug build via ADB
# Run this before Maestro flows that need permissions already granted

APP_ID="ai.talkingrock.lithium.debug"
LISTENER="ai.talkingrock.lithium.debug/ai.talkingrock.lithium.service.LithiumNotificationListener"

echo "Granting notification listener access..."
adb shell cmd notification allow_listener "$LISTENER" 2>&1

echo "Granting usage stats access..."
adb shell appops set "$APP_ID" android:get_usage_stats allow 2>&1

echo "Granting contacts permission..."
adb shell pm grant "$APP_ID" android.permission.READ_CONTACTS 2>&1

echo "All permissions granted."
