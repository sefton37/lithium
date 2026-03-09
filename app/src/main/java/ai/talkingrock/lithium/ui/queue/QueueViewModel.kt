package ai.talkingrock.lithium.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.talkingrock.lithium.data.db.QueueDao
import ai.talkingrock.lithium.data.db.QueuedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for [QueueScreen].
 *
 * @param items Pending queued notifications, oldest first.
 * @param isLoading True on first emission before the DB has responded.
 */
data class QueueUiState(
    val items: List<QueuedItem> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * ViewModel for [QueueScreen].
 *
 * Exposes pending queued notifications joined with their source notification data.
 * Provides dismiss, release (mark reviewed), and clear-all-reviewed actions.
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val queueDao: QueueDao
) : ViewModel() {

    val uiState: StateFlow<QueueUiState> = queueDao.getPendingQueueItems()
        .map { items -> QueueUiState(items = items, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = QueueUiState(isLoading = true)
        )

    /**
     * Dismisses a single queued item without releasing the original notification.
     * Sets status to "dismissed".
     */
    fun dismiss(itemId: Long) {
        viewModelScope.launch {
            queueDao.markReviewed(
                id = itemId,
                action = "dismissed",
                actionedAtMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Marks a queued item as released/reviewed.
     * For MVP: marks status as "actioned". A future milestone can re-post the notification.
     */
    fun release(itemId: Long) {
        viewModelScope.launch {
            queueDao.markReviewed(
                id = itemId,
                action = "actioned",
                actionedAtMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Deletes all non-pending (reviewed/dismissed) entries from the queue.
     * Since the UI only shows pending items, this effectively clears invisible reviewed records.
     */
    fun clearReviewed() {
        viewModelScope.launch {
            queueDao.clearReviewed()
        }
    }
}
