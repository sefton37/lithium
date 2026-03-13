#!/usr/bin/env bash
# Send test notifications via ADB to exercise the notification listener
# These will be captured by LithiumNotificationListener

echo "Sending test notifications..."

# Send a basic notification
adb shell am broadcast \
  -a android.intent.action.MAIN \
  --es title "Test Message" \
  --es text "Hello from Maestro test" \
  2>&1

# Use the notification service directly to post notifications
# This creates real notifications that the listener will capture
adb shell cmd notification post -t "Test Notification 1" "tag1" "This is a test notification from Maestro" 2>&1
adb shell cmd notification post -t "Test Notification 2" "tag2" "Another test notification" 2>&1
adb shell cmd notification post -t "Test Notification 3" "tag3" "Third test notification" 2>&1

echo "Test notifications sent."
