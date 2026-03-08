package ai.talkingrock.lithium.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for [DebugNotificationLogScreen].
 *
 * Exposes the 50 most recent notifications as a [StateFlow]. The underlying Flow is
 * [NotificationRepository.getAll] (already ordered newest-first by the DAO), capped to 50 items.
 *
 * This ViewModel only exists in debug builds — it is only navigated to when
 * [BuildConfig.DEBUG] is true in [MainActivity].
 */
@HiltViewModel
class DebugNotificationLogViewModel @Inject constructor(
    notificationRepository: NotificationRepository
) : ViewModel() {

    val recentNotifications: StateFlow<List<NotificationRecord>> =
        notificationRepository.getAll()
            .map { list -> list.take(50) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )
}
