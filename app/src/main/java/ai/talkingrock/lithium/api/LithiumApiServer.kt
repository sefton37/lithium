package ai.talkingrock.lithium.api

import android.util.Log
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.TierCount
import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LithiumApiServer"

/**
 * Embedded Ktor CIO HTTP server exposing Lithium notification data.
 *
 * Binds to 0.0.0.0:8400. Expected to be reachable only via the Tailscale virtual
 * interface (100.108.10.45) — no Android-layer firewall is available at this layer,
 * so access control depends on Tailscale ACLs.
 *
 * Owned and lifecycle-managed by [LithiumApiService].
 *
 * Endpoints:
 *   GET  /api/health                   — server status and uptime
 *   GET  /api/notifications             — recent notifications (query: since, limit)
 *   GET  /api/notifications/unresolved  — unclassified notifications
 *   GET  /api/stats                     — aggregate counts and noise ratio
 *   GET  /api/contacts                  — per-app engagement summary
 *   POST /api/classifications           — write refined classifications back to DB
 *   POST /api/dismiss                   — bulk mark notifications as dismissed
 */
@Singleton
class LithiumApiServer @Inject constructor(
    private val notificationDao: NotificationDao,
    private val behaviorProfileDao: AppBehaviorProfileDao,
) {
    private var server: ApplicationEngine? = null
    private val startedAtMs = System.currentTimeMillis()

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = PORT, host = BIND_ADDRESS) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = false
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
            routing { configureRoutes() }
        }.also {
            it.start(wait = false)
            Log.i(TAG, "API server started on $BIND_ADDRESS:$PORT")
        }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        server = null
        Log.i(TAG, "API server stopped")
    }

    private fun Routing.configureRoutes() {

        get("/api/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    uptimeMs = System.currentTimeMillis() - startedAtMs,
                    serverTimeMs = System.currentTimeMillis(),
                )
            )
        }

        // GET /api/notifications?since=<epoch_ms>&limit=N&tier=2&tier=3
        // tier parameter may be repeated to filter to multiple tiers.
        // Omit tier to get all notifications.
        get("/api/notifications") {
            val sinceMs = call.request.queryParameters["since"]?.toLongOrNull()
                ?: (System.currentTimeMillis() - DEFAULT_WINDOW_MS)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?: DEFAULT_LIMIT
            val tiers = call.request.queryParameters.getAll("tier")
                ?.mapNotNull { it.toIntOrNull() }

            val records = withContext(Dispatchers.IO) {
                if (tiers.isNullOrEmpty()) {
                    notificationDao.getAllSince(sinceMs)
                } else {
                    notificationDao.getAllSinceWithTiers(sinceMs, tiers)
                }
            }.take(limit)

            call.respond(records.map { it.toDto() })
        }

        // GET /api/notifications/unresolved — unclassified, oldest first
        get("/api/notifications/unresolved") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                ?: DEFAULT_LIMIT
            val records = withContext(Dispatchers.IO) {
                notificationDao.getUnclassified(limit)
            }
            call.respond(records.map { it.toDto() })
        }

        get("/api/stats") {
            val total = withContext(Dispatchers.IO) { notificationDao.count() }
            val classified = withContext(Dispatchers.IO) { notificationDao.countClassified() }
            val distinctApps = withContext(Dispatchers.IO) { notificationDao.countDistinctClassifiedApps() }
            val tierBreakdown = withContext(Dispatchers.IO) { notificationDao.getTierBreakdown() }
                .associate { it.tier to it.count }
            call.respond(
                StatsResponse(
                    totalNotifications = total,
                    classifiedNotifications = classified,
                    unclassifiedNotifications = total - classified,
                    distinctApps = distinctApps,
                    noiseRatio = if (total > 0) classified.toFloat() / total else 0f,
                    tierBreakdown = tierBreakdown,
                )
            )
        }

        // GET /api/contacts — per-app engagement summary derived from behavior profiles
        get("/api/contacts") {
            val profiles = withContext(Dispatchers.IO) {
                behaviorProfileDao.getAllProfiles()
            }
            val contacts = profiles
                .groupBy { it.packageName }
                .map { (pkg, pkgProfiles) ->
                    val totalReceived = pkgProfiles.sumOf { it.totalReceived }
                    val totalTapped = pkgProfiles.sumOf { it.totalTapped }
                    ContactEntry(
                        packageName = pkg,
                        totalNotifications = totalReceived,
                        tapRate = if (totalReceived > 0) totalTapped.toFloat() / totalReceived else 0f,
                        dominantCategory = pkgProfiles.maxByOrNull { it.totalReceived }?.dominantCategory
                            ?: "unknown",
                    )
                }
                .sortedByDescending { it.totalNotifications }
            call.respond(contacts)
        }

        // POST /api/classifications — Claude writes refined classification labels back
        post("/api/classifications") {
            val updates = call.receive<List<ClassificationUpdate>>()
            withContext(Dispatchers.IO) {
                for (update in updates) {
                    notificationDao.updateClassification(
                        id = update.id,
                        classification = update.classification,
                        confidence = update.confidence,
                    )
                }
            }
            call.respond(AckResponse(updated = updates.size))
        }

        // POST /api/dismiss — bulk mark notifications as API-dismissed
        post("/api/dismiss") {
            val req = call.receive<DismissRequest>()
            val nowMs = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                for (id in req.ids) {
                    notificationDao.updateRemoval(id, nowMs, "api_dismiss")
                }
            }
            call.respond(AckResponse(updated = req.ids.size))
        }
    }

    companion object {
        const val PORT = 8400
        const val BIND_ADDRESS = "0.0.0.0"

        private const val DEFAULT_WINDOW_MS = 24 * 60 * 60 * 1_000L // 24 hours
        private const val DEFAULT_LIMIT = 500
    }
}

// — Response / request DTOs ——————————————————————————————————————————————————

@Serializable
data class HealthResponse(
    val status: String,
    val uptimeMs: Long,
    val serverTimeMs: Long,
)

@Serializable
data class NotificationDto(
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
    val isFromContact: Boolean,
    val tier: Int,
    val tierReason: String?,
)

@Serializable
data class StatsResponse(
    val totalNotifications: Int,
    val classifiedNotifications: Int,
    val unclassifiedNotifications: Int,
    val distinctApps: Int,
    val noiseRatio: Float,
    /** Count of notifications per tier (0-3). Keys present only for tiers with at least one notification. */
    val tierBreakdown: Map<Int, Int> = emptyMap(),
)

@Serializable
data class ContactEntry(
    val packageName: String,
    val totalNotifications: Int,
    val tapRate: Float,
    val dominantCategory: String,
)

@Serializable
data class ClassificationUpdate(
    val id: Long,
    val classification: String,
    val confidence: Float,
)

@Serializable
data class DismissRequest(
    val ids: List<Long>,
)

@Serializable
data class AckResponse(
    val updated: Int,
)

private fun NotificationRecord.toDto() = NotificationDto(
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
    isFromContact = isFromContact,
    tier = tier,
    tierReason = tierReason,
)
