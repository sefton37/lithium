package ai.talkingrock.lithium.engine

import android.Manifest
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves whether the sender of a notification is in the device contacts.
 *
 * Extraction order:
 * 1. MessagingStyle EXTRA_PEOPLE_LIST — covers modern messaging apps (SMS, WhatsApp, etc.)
 * 2. Email heuristic on EXTRA_TEXT — covers Gmail and email clients
 * 3. Package-specific extras for well-known messaging apps
 *
 * Lookups are cached in an [LruCache] (capacity 500) to avoid repeated ContentResolver
 * queries on the notification callback thread. The cache key is the sender identifier
 * (URI, email address, or phone number as a string).
 *
 * When READ_CONTACTS permission is absent, returns false immediately. No crash.
 * Contact data itself is never persisted — only the boolean result is stored.
 */
@Singleton
class ContactsResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val cache = LruCache<String, Boolean>(CACHE_CAPACITY)

    /**
     * Returns true if any extractable sender identity for [sbn] is found in the device contacts.
     * Returns false if the sender cannot be identified, is not in contacts, or READ_CONTACTS
     * permission is not granted.
     */
    fun isSenderInContacts(sbn: StatusBarNotification): Boolean {
        if (!hasContactsPermission()) {
            return false
        }

        val identifiers = extractSenderIdentifiers(sbn)
        if (identifiers.isEmpty()) {
            return false
        }

        return identifiers.any { identifier ->
            cache.get(identifier) ?: lookupContact(identifier).also { result ->
                cache.put(identifier, result)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Contact lookup '$identifier' → $result (cache miss)")
                }
            }
        }
    }

    /** Invalidate the LRU cache, e.g. after receiving Intent.ACTION_CONTACTS_CHANGED. */
    fun invalidateCache() {
        cache.evictAll()
        Log.d(TAG, "Contact cache invalidated")
    }

    // -----------------------------------------------------------------------------------
    // Sender extraction
    // -----------------------------------------------------------------------------------

    private fun extractSenderIdentifiers(sbn: StatusBarNotification): List<String> {
        val extras = sbn.notification.extras
        val identifiers = mutableListOf<String>()

        // 1. MessagingStyle EXTRA_PEOPLE_LIST (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val people: ArrayList<Person>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
            } else {
                extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
            }
            people?.forEach { person ->
                val uri = person.uri
                if (!uri.isNullOrBlank()) {
                    identifiers.add(uri)
                } else {
                    val name = person.name?.toString()
                    if (!name.isNullOrBlank()) {
                        identifiers.add(name)
                    }
                }
            }
        }

        // 2. Email heuristic on EXTRA_TEXT
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            EMAIL_REGEX.findAll(text).forEach { match ->
                identifiers.add(match.value)
            }
        }

        // 3. Package-specific extras for well-known messaging apps
        extractPackageSpecificIdentifier(sbn)?.let { identifiers.add(it) }

        return identifiers.distinct()
    }

    /**
     * Maps well-known package names to their sender extras.
     * Returns a sender string, or null if the package is not recognized or the extra is absent.
     */
    private fun extractPackageSpecificIdentifier(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras
        return when (sbn.packageName) {
            // WhatsApp stores the sender name in EXTRA_TITLE for 1:1 chats
            "com.whatsapp", "com.whatsapp.w4b" ->
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

            // Telegram stores the sender name in EXTRA_TITLE
            "org.telegram.messenger", "org.telegram.messenger.web" ->
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

            // Signal stores the sender in EXTRA_TITLE
            "org.thoughtcrime.securesms" ->
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

            else -> null
        }
    }

    // -----------------------------------------------------------------------------------
    // Contact lookup
    // -----------------------------------------------------------------------------------

    private fun lookupContact(identifier: String): Boolean {
        // URI-based lookup (from Person.getUri())
        if (identifier.startsWith("content://") || identifier.startsWith("android.resource://")) {
            return lookupByUri(Uri.parse(identifier))
        }

        // Email address lookup
        if (EMAIL_REGEX.matches(identifier)) {
            return lookupByEmail(identifier)
        }

        // Phone number lookup
        if (PHONE_REGEX.matches(identifier)) {
            return lookupByPhone(identifier)
        }

        // Name-based lookup (last resort — matches on display name, may have false positives)
        return lookupByDisplayName(identifier)
    }

    private fun lookupByUri(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "lookupByUri failed for $uri: ${e.message}")
            false
        }
    }

    private fun lookupByEmail(email: String): Boolean {
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID),
                "${ContactsContract.CommonDataKinds.Email.ADDRESS} = ?",
                arrayOf(email),
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "lookupByEmail failed for $email: ${e.message}")
            false
        }
    }

    private fun lookupByPhone(phone: String): Boolean {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "lookupByPhone failed for $phone: ${e.message}")
            false
        }
    }

    private fun lookupByDisplayName(name: String): Boolean {
        return try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
                arrayOf(name),
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "lookupByDisplayName failed for $name: ${e.message}")
            false
        }
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "ContactsResolver"
        private const val CACHE_CAPACITY = 500

        private val EMAIL_REGEX = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""")
        private val PHONE_REGEX = Regex("""[+\d][\d\s\-().]{6,}""")
    }
}
