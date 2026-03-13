java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'cmd', 'notification', 'post', '-t', 'Test Notification 1', 'tag1', 'This is a test notification']).waitFor();
java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'cmd', 'notification', 'post', '-t', 'Test Notification 2', 'tag2', 'Another test notification']).waitFor();
java.lang.Runtime.getRuntime().exec(['adb', 'shell', 'cmd', 'notification', 'post', '-t', 'Test Notification 3', 'tag3', 'Third test notification']).waitFor();
