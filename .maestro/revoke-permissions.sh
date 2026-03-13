#!/usr/bin/env bash
# Revoke all permissions for Lithium debug build via ADB
# Use this to reset state before testing the Setup flow

APP_ID="ai.talkingrock.lithium.debug"
LISTENER="ai.talkingrock.lithium.debug/ai.talkingrock.lithium.service.LithiumNotificationListener"

echo "Revoking notification listener access..."
adb shell cmd notification disallow_listener "$LISTENER" 2>&1

echo "Revoking usage stats access..."
adb shell appops set "$APP_ID" android:get_usage_stats deny 2>&1

echo "Revoking contacts permission..."
adb shell pm revoke "$APP_ID" android.permission.READ_CONTACTS 2>&1

echo "Force stopping app..."
adb shell am force-stop "$APP_ID" 2>&1

echo "All permissions revoked."
