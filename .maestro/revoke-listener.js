// Revoke only the notification listener — used by 12_permission_revoke.yaml
// Leaves other permissions intact so the app continues to run.
const LISTENER = 'ai.talkingrock.lithium.debug/ai.talkingrock.lithium.service.LithiumNotificationListener';

java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'cmd', 'notification', 'disallow_listener', LISTENER]).waitFor();
