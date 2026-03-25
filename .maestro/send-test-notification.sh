#!/usr/bin/env bash
# Send test notifications via ADB to exercise the Lithium classifier
# Sends a variety of notification types to trigger different categories:
#   social, promotional, OTP/transactional, background/system, personal message

echo "Sending test notifications..."

APP_ID="ai.talkingrock.lithium.debug"

# Social — engagement noise
adb shell cmd notification post -t "user123 liked your photo" "social_1" "Instagram" 2>&1 || true
adb shell cmd notification post -t "You have 5 new followers" "social_2" "Twitter" 2>&1 || true

# Promotional — marketing
adb shell cmd notification post -t "Flash Sale! 50% off everything today only" "promo_1" "ShopApp" 2>&1 || true
adb shell cmd notification post -t "Your exclusive offer expires tonight" "promo_2" "RetailApp" 2>&1 || true

# OTP / transactional — high-signal
adb shell cmd notification post -t "Your verification code is 482910" "otp_1" "BankApp" 2>&1 || true
adb shell cmd notification post -t "Security alert: new sign-in to your account" "otp_2" "AuthApp" 2>&1 || true

# Background / ongoing — system noise
adb shell cmd notification post -t "Sync in progress" "bg_1" "BackupApp" 2>&1 || true
adb shell cmd notification post -t "Uploading 3 photos" "bg_2" "PhotoApp" 2>&1 || true

# Personal message — contact-like (person name in title, should score high)
adb shell cmd notification post -t "Alice: are you free tonight?" "msg_1" "Messages" 2>&1 || true
adb shell cmd notification post -t "Bob sent you a photo" "msg_2" "WhatsApp" 2>&1 || true

echo "Test notifications sent (10 total across 5 categories)."
