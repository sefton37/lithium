package ai.talkingrock.lithium.debug

import ai.talkingrock.lithium.ai.scoring.ScoringRefit
import ai.talkingrock.lithium.data.db.ImplicitJudgmentDao
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Debug-only ADB entry points for the scoring/implicit-signals pipeline.
 *
 *   adb shell am broadcast -a ai.talkingrock.lithium.RUN_REFIT     -p ai.talkingrock.lithium.debug
 *   adb shell am broadcast -a ai.talkingrock.lithium.DUMP_IMPLICIT -p ai.talkingrock.lithium.debug
 *
 * Watch output with:
 *   adb logcat -s DevRefit:* DevImplicit:*
 */
@AndroidEntryPoint
class ScoringDevReceiver : BroadcastReceiver() {

    @Inject lateinit var refit: ScoringRefit
    @Inject lateinit var implicitDao: ImplicitJudgmentDao

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        when (intent.action) {
            ACTION_RUN_REFIT -> scope.launch {
                try {
                    Log.i(TAG_REFIT, "invoked via broadcast")
                    refit.refit()
                    Log.i(TAG_REFIT, "completed")
                } catch (t: Throwable) {
                    Log.e(TAG_REFIT, "failed", t)
                } finally { pending.finish() }
            }
            ACTION_DUMP_IMPLICIT -> scope.launch {
                try {
                    val total = implicitDao.count()
                    val rows = implicitDao.getRecent(20)
                    Log.i(TAG_DUMP, "total=$total showing=${rows.size}")
                    rows.forEach { r ->
                        Log.i(TAG_DUMP, formatRow(r))
                    }
                } catch (t: Throwable) {
                    Log.e(TAG_DUMP, "failed", t)
                } finally { pending.finish() }
            }
            else -> pending.finish()
        }
    }

    private fun formatRow(r: ai.talkingrock.lithium.data.model.ImplicitJudgment): String {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(r.createdAtMs))
        return "[$ts] ${r.kind} winner=${r.winnerPackage}/${r.winnerChannelId} " +
            "loser=${r.loserPackage}/${r.loserChannelId} " +
            "winRank=${r.winnerRank} loseRank=${r.loserRank} " +
            "cohort=${r.cohortSize} screen=${r.screenWasOn}"
    }

    companion object {
        private const val TAG_REFIT = "DevRefit"
        private const val TAG_DUMP = "DevImplicit"
        const val ACTION_RUN_REFIT = "ai.talkingrock.lithium.RUN_REFIT"
        const val ACTION_DUMP_IMPLICIT = "ai.talkingrock.lithium.DUMP_IMPLICIT"
    }
}
