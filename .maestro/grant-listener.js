// Re-grant the notification listener — used by 12_permission_revoke.yaml
const LISTENER = 'ai.talkingrock.lithium.debug/ai.talkingrock.lithium.service.LithiumNotificationListener';

java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'cmd', 'notification', 'allow_listener', LISTENER]).waitFor();
