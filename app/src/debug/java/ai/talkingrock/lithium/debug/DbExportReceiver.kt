package ai.talkingrock.lithium.debug

import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/**
 * Debug-only BroadcastReceiver that exports all notification records to a
 * plaintext JSON file at /sdcard/Download/lithium-notifications-export.json.
 *
 * The file is the seed for Phase 3 synthetic data generation.
 *
 * Trigger:
 *   adb shell am broadcast \
 *       -a ai.talkingrock.lithium.debug.EXPORT_NOTIFICATIONS_PLAINTEXT \
 *       -p ai.talkingrock.lithium.debug
 *
 * Watch output:
 *   adb logcat -s DbExportReceiver:*
 *
 * NOTE: This receiver intentionally injects NotificationDao directly (not
 * NotificationRepository) to achieve zero changes to app/src/main/.
 * Pattern is acceptable because this code lives exclusively in the debug
 * source set and is never compiled into the release APK.
 */
@AndroidEntryPoint
class DbExportReceiver : BroadcastReceiver() {

    @Inject lateinit var dao: NotificationDao

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPORT_NOTIFICATIONS_PLAINTEXT) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val records: List<NotificationRecord> = dao.getAll().first()
                val payload = ExportPayload(
                    exportedAtMs = System.currentTimeMillis(),
                    schemaVersion = 1,
                    count = records.size,
                    notifications = records.map { it.toExportRecord() },
                )
                val json = Json {
                    prettyPrint = true
                    allowSpecialFloatingPointValues = true
                }
                val outFile = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    "lithium-notifications-export.json"
                )
                outFile.writeText(json.encodeToString(payload))
                Log.i(TAG, "export complete: ${records.size} rows -> ${outFile.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "export failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DbExportReceiver"
        const val ACTION_EXPORT_NOTIFICATIONS_PLAINTEXT =
            "ai.talkingrock.lithium.debug.EXPORT_NOTIFICATIONS_PLAINTEXT"
    }
}

// ---------------------------------------------------------------------------
// Local DTOs — debug source set only, never compiled into release APK
// ---------------------------------------------------------------------------

@Serializable
private data class NotificationExportRecord(
    val id: Long,
    val packageName: String,
    val postedAtMs: Long,
    val title: String?,
    val text: String?,
    val channelId: String?,
    val category: String?,
    val isOngoing: Boolean,
    val removedAtMs: Long?,
    val removalReason: String?,
    val aiClassification: String?,
    val aiConfidence: Float?,
    val ruleIdMatched: Long?,
    val isFromContact: Boolean,
    val tier: Int,
    val tierReason: String?,
    val disposition: String?,
)

@Serializable
private data class ExportPayload(
    val exportedAtMs: Long,
    val schemaVersion: Int,
    val count: Int,
    val notifications: List<NotificationExportRecord>,
)

private fun NotificationRecord.toExportRecord() = NotificationExportRecord(
    id = id,
    packageName = packageName,
    postedAtMs = postedAtMs,
    title = title,
    text = text,
    channelId = channelId,
    category = category,
    isOngoing = isOngoing,
    removedAtMs = removedAtMs,
    removalReason = removalReason,
    aiClassification = aiClassification,
    aiConfidence = aiConfidence,
    ruleIdMatched = ruleIdMatched,
    isFromContact = isFromContact,
    tier = tier,
    tierReason = tierReason,
    disposition = disposition,
)
