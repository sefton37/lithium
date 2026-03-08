package ai.talkingrock.lithium.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that tracks whether [LithiumNotificationListener] is currently connected.
 *
 * The listener updates this state in [onListenerConnected] and [onListenerDisconnected].
 * The Setup screen observes [isConnected] to display live permission status without polling
 * AppOpsManager on every frame.
 *
 * This is a simple in-memory state — it resets to false on process restart, which is correct:
 * the listener will call [onListenerConnected] again when it reconnects, updating the state.
 */
@Singleton
class ListenerState @Inject constructor() {

    private val _isConnected = MutableStateFlow(false)

    /** True when the NotificationListenerService is currently connected and receiving events. */
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /** Called from [LithiumNotificationListener.onListenerConnected]. */
    fun onConnected() {
        _isConnected.value = true
    }

    /** Called from [LithiumNotificationListener.onListenerDisconnected]. */
    fun onDisconnected() {
        _isConnected.value = false
    }
}
