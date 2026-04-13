package ai.talkingrock.lithium.ai.eval

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Synthetic data loaded from app `assets/eval/` at runtime.
 *
 * All three files ship with the APK so the eval harness runs identically on any device
 * regardless of the user's real notification history or contacts.
 */
@Serializable
data class AppsBundle(val apps: List<AppEntry>)

@Serializable
data class AppEntry(val packageName: String, val label: String)

@Serializable
data class ContactsBundle(val contacts: List<String>)

@Serializable
data class ScenarioBundle(val scenarios: List<Scenario>)

@Serializable
data class Scenario(
    val id: String,
    val expected: Expected,
    val acceptable: Acceptable? = null,
    val phrasings: List<Phrasing>,
)

@Serializable
data class Expected(
    val packageName: String? = null,
    val channelId: String? = null,
    val category: String? = null,
    val notFromContact: Boolean = false,
    val action: String = "suppress",
)

/** Each list names field values that should also be scored as correct if the model returns them. */
@Serializable
data class Acceptable(
    val packageName: List<String?>? = null,
    val channelId: List<String?>? = null,
    val category: List<String?>? = null,
)

@Serializable
data class Phrasing(val tone: String, val text: String)

object EvalDataset {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadApps(context: Context): AppsBundle =
        json.decodeFromString(context.assets.open("eval/apps.json").bufferedReader().use { it.readText() })

    fun loadContacts(context: Context): ContactsBundle =
        json.decodeFromString(context.assets.open("eval/contacts.json").bufferedReader().use { it.readText() })

    fun loadScenarios(context: Context): ScenarioBundle =
        json.decodeFromString(context.assets.open("eval/test_cases.json").bufferedReader().use { it.readText() })
}
