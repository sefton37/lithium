"""
Synthetic training data generator for Lithium notification classifier.

Generates labeled notification examples in the same format the ONNX model will
receive at inference time:
    [APP: {pkg}] [CHANNEL: {channel}] [TITLE: {title}] [TEXT: {text}]

Categories (7 classes, matching AiEngine.CATEGORY_LABELS):
    0: personal         — direct human communication
    1: engagement_bait  — algorithmic pull-back content
    2: promotional      — marketing, offers, discounts
    3: transactional    — 2FA, delivery, payment, receipts
    4: system           — OS/device/platform notifications
    5: social_signal    — likes, follows, reactions (not direct messages)
    6: unknown          — ambiguous / unclassifiable

Usage:
    python generate_data.py                     # writes data/train.csv and data/val.csv
    python generate_data.py --count 5000        # 5000 examples per category
    python generate_data.py --seed 42           # reproducible
"""

import argparse
import csv
import os
import random
from pathlib import Path


# ---------------------------------------------------------------------------
# Template pools — each category has multiple templates that get filled with
# random choices to produce diverse training examples.
# ---------------------------------------------------------------------------

# Common app packages mapped to typical notification behaviors
SOCIAL_PACKAGES = [
    "com.instagram.android", "com.facebook.katana", "com.twitter.android",
    "com.zhiliaoapp.musically",  # TikTok
    "com.snapchat.android", "com.reddit.frontpage",
    "com.linkedin.android", "com.pinterest",
    "com.tumblr", "org.joinmastodon.android",
    "com.instagram.barcelona",  # Threads
    "com.bereal.ft", "com.google.android.youtube",
]

MESSAGING_PACKAGES = [
    "com.whatsapp", "org.telegram.messenger", "com.discord",
    "com.slack", "com.microsoft.teams", "com.skype.raider",
    "com.viber.voip", "jp.naver.line.android",
    "com.google.android.gm",  # Gmail
    "com.microsoft.office.outlook",
    "org.thoughtcrime.securesms",  # Signal
    "com.google.android.apps.messaging",  # Google Messages
]

ECOMMERCE_PACKAGES = [
    "com.amazon.mShop.android.shopping", "com.ebay.mobile",
    "com.etsy.android", "com.shopify.mobile",
    "com.walmart.android", "com.target.ui",
    "com.bestbuy.android", "com.wayfair.wayfair",
    "com.contextlogic.wish", "com.alibaba.aliexpresshd",
    "com.zhiliaoapp.shein", "com.einnovation.temu",
]

FOOD_PACKAGES = [
    "com.ubercab.eats", "com.dd.doordash",
    "com.grubhub.android", "com.instacart.client",
    "com.postmates.android",
]

FINANCE_PACKAGES = [
    "com.chase.sig.android", "com.wf.wellsfargomobile",
    "com.inuit.turbotax", "com.paypal.android.p2pmobile",
    "com.venmo", "com.squareup.cash",
    "com.americanexpress.android.acctsvcs.us",
    "com.capitalone.mobile", "com.citi.citimobile",
]

SYSTEM_PACKAGES = [
    "android", "com.android.systemui", "com.android.settings",
    "com.android.vending", "com.google.android.gms",
    "com.google.android.deskclock", "com.google.android.dialer",
    "com.google.android.calendar", "com.google.android.apps.wellbeing",
    "com.google.android.apps.nexuslauncher",
    "com.android.providers.downloads", "com.android.bluetooth",
    "com.android.phone",
]

RIDE_PACKAGES = [
    "com.ubercab", "com.lyft.android",
]

TRAVEL_PACKAGES = [
    "com.tripadvisor.tripadvisor", "com.booking",
    "com.airbnb.android", "com.expedia.bookings",
    "com.google.android.apps.maps",
]

NEWS_PACKAGES = [
    "com.google.android.apps.magazines",  # Google News
    "com.nytimes.android", "com.washingtonpost.android",
    "com.cnn.mobile.android.phone", "com.bbc.news",
    "flipboard.app", "com.reddit.frontpage",
]

# Human names for personal messages
FIRST_NAMES = [
    "Alex", "Jordan", "Sam", "Casey", "Morgan", "Taylor", "Riley",
    "Jamie", "Drew", "Quinn", "Blake", "Avery", "Charlie", "Dakota",
    "Emery", "Finley", "Harper", "Jessie", "Kendall", "Logan",
    "Mason", "Noah", "Olivia", "Parker", "Reese", "Sage", "Tyler",
    "Val", "Winter", "Zoe", "Mom", "Dad", "Sis", "Bro",
]

# Social media usernames
USERNAMES = [
    "jess_creates", "mario_photo", "travel_daily", "neon_vibes",
    "coast_captures", "pixel_girl", "sunset_chaser", "urban_lens",
    "wild_wanderer", "art_by_maya", "coffee_and_code", "the_real_mike",
    "sarah_runs", "mountain_man", "night_owl_29", "lazy_baker",
    "photo_ninja", "street_shots", "digital_dave", "nature_nerd",
    "bookworm_anna", "fit_with_fran", "retro_rick", "vegan_val",
    "ski_queen", "surf_dude", "gamer_girl", "chef_tom",
]


def _pick(lst):
    """Pick a random element from a list."""
    return random.choice(lst)


def _picks(lst, n):
    """Pick n random elements (with replacement) from a list."""
    return [random.choice(lst) for _ in range(n)]


def _rand_count():
    """Random engagement count."""
    return random.choice([2, 3, 5, 7, 8, 12, 15, 23, 47, 100, 250])


# ---------------------------------------------------------------------------
# Category generators — each returns (text, label_index)
# ---------------------------------------------------------------------------

def gen_personal():
    """Generate a 'personal' notification example."""
    pkg = _pick(MESSAGING_PACKAGES)
    name = _pick(FIRST_NAMES)

    templates = [
        # Direct messages
        (f"{name}", f"Hey, are you free tonight?", "messages"),
        (f"{name}", f"Can you pick up milk on the way home?", "messages"),
        (f"{name}", f"Call me when you get a chance", "messages"),
        (f"{name}", f"Happy birthday! 🎂", "messages"),
        (f"{name}", f"Running 10 min late, sorry!", "messages"),
        (f"{name}", f"Did you see the news about the earthquake?", "messages"),
        (f"{name}", f"I just sent you the documents", "messages"),
        (f"{name}", f"Meeting moved to 3pm", "messages"),
        (f"{name}", f"Want to grab lunch tomorrow?", "messages"),
        (f"{name}", f"Thanks for helping yesterday!", "messages"),
        (f"{name}", f"I'm at the store, need anything?", "messages"),
        (f"{name}", f"The kids are asking about you", "messages"),
        (f"{name}", f"Doctor appointment is at 2:30", "messages"),
        (f"{name}", f"Flight lands at 8pm", "messages"),
        (f"{name}", f"Got the job!! 🎉", "messages"),
        (f"{name}", f"Can we talk?", "messages"),
        (f"{name}", f"Check your email, sent you something", "messages"),
        (f"{name}", f"Movie starts at 7, don't be late", "messages"),
        (f"{name}", f"How's the project going?", "messages"),
        (f"{name}", f"Miss you ❤️", "messages"),
        # Email-style personal
        (f"{name}", f"Re: Weekend plans", "inbox"),
        (f"{name}", f"Quick question about the report", "inbox"),
        (f"{name}", f"Fw: Funny video you'll love", "inbox"),
        # Group chats
        (f"{name} in Family Group", f"Who's coming to dinner Sunday?", "group_messages"),
        (f"Book Club", f"{name}: Just finished chapter 12!", "group_messages"),
        (f"Work Team", f"{name}: PR is ready for review", "group_messages"),
        # Voice/video calls
        (f"{name}", f"Missed call", "calls"),
        (f"{name}", f"Incoming video call", "calls"),
        (f"{name}", f"Voicemail (0:32)", "calls"),
    ]

    title, text, channel = _pick(templates)
    return _format(pkg, channel, title, text), 0


def gen_engagement_bait():
    """Generate an 'engagement_bait' notification example."""
    username = _pick(USERNAMES)

    # Package-title pairs — each template is (pkg, title, text, channel)
    # so the package always matches the title.
    APP_TEMPLATES = {
        "com.instagram.android": "Instagram",
        "com.facebook.katana": "Facebook",
        "com.twitter.android": "Twitter",
        "com.zhiliaoapp.musically": "TikTok",
        "com.snapchat.android": "Snapchat",
        "com.reddit.frontpage": "Reddit",
        "com.linkedin.android": "LinkedIn",
        "com.pinterest": "Pinterest",
        "com.google.android.youtube": "YouTube",
        "com.instagram.barcelona": "Threads",
    }

    pkg = _pick(list(APP_TEMPLATES.keys()))
    app_name = APP_TEMPLATES[pkg]

    # Generic templates that work with any app name
    generic_templates = [
        (f"See what's trending today", "explore"),
        (f"Based on your activity, you might like {username}'s posts", "suggestions"),
        (f"You have unseen posts from accounts you follow", "digest"),
        (f"Suggested for you: {username}'s latest post is going viral", "recommendations"),
        (f"Don't miss out! See what's popular right now", "trending"),
        (f"Posts you've missed from people you follow", "digest"),
        (f"You haven't posted in a while — your followers miss you!", "engagement"),
        (f"Your reach dropped this week. Post now to boost it!", "engagement"),
        (f"Check out what {username} posted — it's going viral!", "viral"),
        (f"Recommended for you based on your history", "recommendations"),
        (f"People you may know: 5 new suggestions", "people_you_may_know"),
        (f"See what {username} has been up to", "suggestions"),
        (f"Your friends are talking about this trending topic", "trending"),
        (f"{username} shared something you might be interested in", "suggestions"),
        (f"Popular near you: content you haven't seen", "highlights"),
        (f"Discover: Top stories trending now", "discover"),
        (f"LIVE: Creators you follow are streaming now", "live"),
        (f"Trending: This post has 2M views in 24 hours", "for_you"),
        (f"{username} is streaming live right now — join {_rand_count()} viewers", "live"),
        (f"Popular posts in your communities", "digest"),
        (f"Your network is buzzing about this article", "trending"),
        (f"Inspired by your interests: {_rand_count()} new things to explore", "recommendations"),
        (f"Popular in your area: new ideas for you", "trending"),
        (f"Your friends posted new stories — check them out!", "stories"),
    ]

    text, channel = _pick(generic_templates)
    return _format(pkg, channel, app_name, text), 1


def gen_promotional():
    """Generate a 'promotional' notification example."""
    pkg = _pick(ECOMMERCE_PACKAGES + FOOD_PACKAGES + random.sample(NEWS_PACKAGES, 1))

    templates = [
        ("Flash Sale!", "50% off everything — today only!", "promotions"),
        ("Limited Time Offer", "Save $20 on your next order with code SAVE20", "promotions"),
        ("Deal of the Day", "Lightning deal: AirPods Pro for $179", "deals"),
        ("Back in Stock", "The item on your wish list is available again", "alerts"),
        ("Price Drop Alert", "An item in your cart just dropped 30%", "alerts"),
        ("New Arrivals", "Just dropped: Spring collection is here", "promotions"),
        ("Free Shipping", "Order in the next 2 hours for free shipping", "promotions"),
        ("Exclusive Deal", "Members only: early access to our biggest sale", "promotions"),
        ("Weekend Sale", "Up to 70% off — shop now before it's gone", "promotions"),
        ("Reward Points", "You have 500 points expiring tomorrow — redeem now!", "rewards"),
        ("Special Offer", "Buy one get one free on select items", "promotions"),
        ("Your Cart", "You left something in your cart — complete your order!", "cart"),
        ("Coupon", "Here's a $10 coupon just for you", "promotions"),
        ("Daily Deals", "Today's top picks: up to 60% off electronics", "deals"),
        ("Sale Ends Tonight", "Last chance: clearance items going fast", "promotions"),
        ("Subscribe & Save", "Save 15% when you subscribe to monthly delivery", "promotions"),
        ("New for You", "Products curated based on your browsing history", "recommendations"),
        ("Free Trial", "Try Premium free for 30 days — cancel anytime", "promotions"),
        ("Rate Your Purchase", "How was your recent order? Leave a review for 10% off", "feedback"),
        ("Refer a Friend", "Share the love: give $10, get $10", "referral"),
        ("App Exclusive", "Save an extra 10% when you order through the app", "promotions"),
        ("Birthday Special", "Happy birthday! Here's 25% off your next purchase", "promotions"),
        ("Newsletter", "This week's top stories and must-reads", "newsletter"),
        ("Weekly Digest", "Your personalized reading list is ready", "digest"),
        ("Early Access", "VIP early access: new drops before everyone else", "promotions"),
    ]

    title, text, channel = _pick(templates)
    return _format(pkg, channel, title, text), 2


def gen_transactional():
    """Generate a 'transactional' notification example."""
    # Mix of finance, ecommerce, ride-hailing, travel
    pkg = _pick(FINANCE_PACKAGES + ECOMMERCE_PACKAGES + RIDE_PACKAGES + TRAVEL_PACKAGES)

    order_num = f"#{random.randint(100000, 999999)}"
    amount = f"${random.randint(5, 500)}.{random.randint(0, 99):02d}"
    code = f"{random.randint(100000, 999999)}"

    templates = [
        # 2FA / OTP
        ("Security Code", f"Your verification code is {code}", "security"),
        ("Login Alert", f"Your one-time code: {code}. Don't share it.", "security"),
        ("Two-Factor Auth", f"Enter code {code} to complete sign-in", "security"),
        ("Verification", f"Your OTP is {code}. Valid for 5 minutes.", "security"),
        ("Account Security", f"Authentication code: {code}", "security"),
        # Delivery / shipping
        ("Order Shipped", f"Your order {order_num} has shipped! Track it now.", "orders"),
        ("Out for Delivery", f"Your package is out for delivery — arriving today", "orders"),
        ("Delivered", f"Your order {order_num} has been delivered", "orders"),
        ("Delivery Update", f"Estimated delivery: Tomorrow by 8pm", "orders"),
        ("Shipment Tracking", f"Your tracking number for order {order_num}", "orders"),
        ("Order Confirmed", f"We've received your order {order_num}", "orders"),
        ("Package Arriving", f"Your Amazon package will arrive by 9pm tonight", "orders"),
        # Payment / banking
        ("Payment Received", f"Payment of {amount} received", "transactions"),
        ("Transaction Alert", f"Charged {amount} at Grocery Store", "transactions"),
        ("Refund Processed", f"Refund of {amount} has been processed", "transactions"),
        ("Direct Deposit", f"Direct deposit of {amount} posted to checking", "transactions"),
        ("Bill Due", f"Reminder: {amount} payment due in 3 days", "bills"),
        ("Statement Ready", "Your monthly statement is now available", "statements"),
        ("Subscription Renewed", f"Your subscription was renewed for {amount}/mo", "billing"),
        ("Receipt", f"Receipt for your purchase of {amount}", "transactions"),
        # Ride-hailing
        ("Your Ride", "Your driver is arriving in 3 minutes", "rides"),
        ("Trip Complete", f"Your trip cost {amount}. Rate your driver.", "rides"),
        ("Ride Cancelled", "Your ride has been cancelled", "rides"),
        # Travel
        ("Booking Confirmed", f"Your hotel reservation for Mar 15-18 is confirmed", "bookings"),
        ("Check-in Open", "Online check-in is now available for your flight", "travel"),
        ("Flight Update", "Gate change: your flight now departs from Gate B12", "travel"),
        ("Boarding Pass", "Your boarding pass is ready", "travel"),
    ]

    title, text, channel = _pick(templates)
    return _format(pkg, channel, title, text), 3


def gen_system():
    """Generate a 'system' notification example."""
    pkg = _pick(SYSTEM_PACKAGES)

    templates = [
        ("System Update", "Android 15 security update available", "system_updates"),
        ("Battery", "Battery at 15% — connect to charger", "battery"),
        ("Storage", "Phone storage almost full — free up space", "storage"),
        ("Wi-Fi", "Connected to Home_Network_5G", "connectivity"),
        ("Bluetooth", "Galaxy Buds Pro connected", "connectivity"),
        ("Do Not Disturb", "Do Not Disturb is on until 7:00 AM", "dnd"),
        ("Screen Time", "You've reached your daily limit for social media", "wellbeing"),
        ("Digital Wellbeing", "You've unlocked your phone 47 times today", "wellbeing"),
        ("App Update", "3 app updates available", "updates"),
        ("Download Complete", "system-update.zip downloaded", "downloads"),
        ("USB", "USB connected — tap for more options", "usb"),
        ("NFC", "Tap to pay with Google Pay", "nfc"),
        ("Alarm", "Alarm set for 7:00 AM", "alarms"),
        ("Calendar", "Team standup in 15 minutes", "calendar"),
        ("Reminder", "Pick up prescription", "reminders"),
        ("Auto-rotate", "Auto-rotate is off", "settings"),
        ("Night Mode", "Night mode will turn on at 9:00 PM", "display"),
        ("VPN", "VPN connected to ExpressVPN", "connectivity"),
        ("Charging", "Charging slowly — use original charger", "battery"),
        ("Data Usage", "You've used 80% of your monthly data", "data"),
        ("Permissions", "App requesting location access", "permissions"),
        ("Backup", "Last backup: 2 days ago", "backup"),
        ("Cast", "Casting to Living Room TV", "media"),
        ("Hotspot", "2 devices connected to your hotspot", "tethering"),
        ("SIM", "No SIM card detected", "connectivity"),
        ("Google Play", "App update: Chrome updated to v120", "updates"),
        ("Security Scan", "No threats found", "security"),
        ("Setup", "Finish setting up your device", "setup"),
    ]

    title, text, channel = _pick(templates)
    return _format(pkg, channel, title, text), 4


def gen_social_signal():
    """Generate a 'social_signal' notification example."""
    # Package-to-app-name mapping for consistent pairing
    APP_NAMES = {
        "com.instagram.android": "Instagram",
        "com.facebook.katana": "Facebook",
        "com.twitter.android": "Twitter",
        "com.zhiliaoapp.musically": "TikTok",
        "com.snapchat.android": "Snapchat",
        "com.reddit.frontpage": "Reddit",
        "com.linkedin.android": "LinkedIn",
        "com.pinterest": "Pinterest",
        "com.google.android.youtube": "YouTube",
        "com.instagram.barcelona": "Threads",
        "com.tumblr": "Tumblr",
        "org.joinmastodon.android": "Mastodon",
        "com.bereal.ft": "BeReal",
    }

    pkg = _pick(list(APP_NAMES.keys()))
    app_name = APP_NAMES[pkg]
    username = _pick(USERNAMES)
    count = _rand_count()

    templates = [
        (f"@{username} liked your photo", "likes"),
        (f"@{username} liked your post", "likes"),
        (f"@{username} and {count} others liked your reel", "likes"),
        (f"@{username} commented: 'Love this! 🔥'", "comments"),
        (f"@{username} commented on your photo", "comments"),
        (f"@{username} replied to your story", "comments"),
        (f"@{username} started following you", "follows"),
        (f"{count} people followed you today", "follows"),
        (f"@{username} mentioned you in a comment", "mentions"),
        (f"@{username} tagged you in a post", "mentions"),
        (f"@{username} shared your post", "shares"),
        (f"@{username} retweeted your tweet", "shares"),
        (f"@{username} reacted to your message", "reactions"),
        (f"Your post reached {count} people", "reach"),
        (f"Your reel has {count} new views", "views"),
        (f"@{username} upvoted your comment", "votes"),
        (f"@{username} awarded your post", "awards"),
        (f"New follower: @{username}", "follows"),
        (f"@{username} and {count} others commented on your post", "comments"),
        (f"Your story was viewed by {count} people", "views"),
        (f"@{username} boosted your post", "shares"),
        (f"@{username} saved your post", "saves"),
        (f"You got {count} new likes on your photo", "likes"),
        (f"@{username} quoted your tweet", "quotes"),
    ]

    text, channel = _pick(templates)
    return _format(pkg, channel, app_name, text), 5


def gen_unknown():
    """Generate an 'unknown' notification example — genuinely ambiguous."""
    # Mix packages that could go either way
    pkg = _pick(
        SOCIAL_PACKAGES + MESSAGING_PACKAGES + ECOMMERCE_PACKAGES +
        NEWS_PACKAGES + ["com.example.app", "com.unknown.app", "net.someapp"]
    )

    templates = [
        (pkg.split(".")[-1].capitalize(), "Tap to continue", "default"),
        (pkg.split(".")[-1].capitalize(), "New activity", "default"),
        (pkg.split(".")[-1].capitalize(), "Update available", "default"),
        (pkg.split(".")[-1].capitalize(), "Action required", "default"),
        (pkg.split(".")[-1].capitalize(), "Notification", "default"),
        (pkg.split(".")[-1].capitalize(), "Check this out", "default"),
        (pkg.split(".")[-1].capitalize(), "", "default"),
        (pkg.split(".")[-1].capitalize(), "1 new item", "default"),
        (pkg.split(".")[-1].capitalize(), "Something happened", "default"),
        ("", "Tap for details", "default"),
        (None, None, "default"),
        (pkg.split(".")[-1].capitalize(), "See more", "default"),
        (pkg.split(".")[-1].capitalize(), "You have updates", "default"),
    ]

    title, text, channel = _pick(templates)
    return _format(pkg, channel, title, text), 6


def _format(pkg, channel, title, text):
    """Format a notification into the model input string."""
    parts = [f"[APP: {pkg}]"]
    if channel:
        parts.append(f"[CHANNEL: {channel}]")
    if title:
        parts.append(f"[TITLE: {title}]")
    if text:
        parts.append(f"[TEXT: {text}]")
    return " ".join(parts)


# ---------------------------------------------------------------------------
# Data generation
# ---------------------------------------------------------------------------

GENERATORS = [
    gen_personal,        # 0
    gen_engagement_bait, # 1
    gen_promotional,     # 2
    gen_transactional,   # 3
    gen_system,          # 4
    gen_social_signal,   # 5
    gen_unknown,         # 6
]

LABEL_NAMES = [
    "personal", "engagement_bait", "promotional",
    "transactional", "system", "social_signal", "unknown",
]


def generate_dataset(count_per_category: int, seed: int = 42):
    """Generate a balanced dataset with count_per_category examples per class."""
    random.seed(seed)
    rows = []

    for label_idx, gen_fn in enumerate(GENERATORS):
        for _ in range(count_per_category):
            text, label = gen_fn()
            assert label == label_idx, f"Generator mismatch: expected {label_idx}, got {label}"
            rows.append({"text": text, "label": label, "label_name": LABEL_NAMES[label]})

    random.shuffle(rows)
    return rows


def write_csv(rows, path):
    """Write rows to a CSV file."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["text", "label", "label_name"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"  Wrote {len(rows)} rows to {path}")


def main():
    parser = argparse.ArgumentParser(description="Generate synthetic notification training data")
    parser.add_argument("--count", type=int, default=2000,
                        help="Examples per category (default: 2000)")
    parser.add_argument("--val-count", type=int, default=300,
                        help="Validation examples per category (default: 300)")
    parser.add_argument("--seed", type=int, default=42,
                        help="Random seed (default: 42)")
    parser.add_argument("--output-dir", type=str, default="data",
                        help="Output directory (default: data/)")
    args = parser.parse_args()

    print(f"Generating training data: {args.count} examples/category, seed={args.seed}")
    train_rows = generate_dataset(args.count, seed=args.seed)

    print(f"Generating validation data: {args.val_count} examples/category, seed={args.seed + 1}")
    val_rows = generate_dataset(args.val_count, seed=args.seed + 1)

    output_dir = Path(args.output_dir)
    write_csv(train_rows, output_dir / "train.csv")
    write_csv(val_rows, output_dir / "val.csv")

    # Print class distribution
    from collections import Counter
    train_dist = Counter(r["label_name"] for r in train_rows)
    print("\nTraining set distribution:")
    for name in LABEL_NAMES:
        print(f"  {name}: {train_dist[name]}")
    print(f"  Total: {sum(train_dist.values())}")


if __name__ == "__main__":
    main()
