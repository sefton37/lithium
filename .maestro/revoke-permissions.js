const APP_ID = 'ai.talkingrock.lithium.debug';
const LISTENER = 'ai.talkingrock.lithium.debug/ai.talkingrock.lithium.service.LithiumNotificationListener';

java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'cmd', 'notification', 'disallow_listener', LISTENER]).waitFor();
java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'appops', 'set', APP_ID, 'android:get_usage_stats', 'deny']).waitFor();
java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'pm', 'revoke', APP_ID, 'android.permission.READ_CONTACTS']).waitFor();
java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'am', 'force-stop', APP_ID]).waitFor();
