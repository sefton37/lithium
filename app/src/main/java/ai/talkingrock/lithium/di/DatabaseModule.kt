package ai.talkingrock.lithium.di

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.SessionDao
import ai.talkingrock.lithium.data.db.RuleDao
import ai.talkingrock.lithium.data.db.ReportDao
import ai.talkingrock.lithium.data.db.SuggestionDao
import ai.talkingrock.lithium.data.db.QueueDao
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Singleton

/**
 * Room + SQLCipher database bindings.
 *
 * Key derivation strategy:
 * ---
 * SQLCipher requires a passphrase. We derive it from the Android Keystore:
 *
 * 1. Generate (or retrieve) an AES-256-GCM key under alias "lithium_db_key_v1"
 *    in the Android Keystore. This key is hardware-backed on devices that
 *    support StrongBox or TEE.
 *
 * 2. Use that key to AES-GCM encrypt a 32-byte application constant (DB_KEY_PLAINTEXT).
 *    The IV is a fixed 12-byte compile-time constant (DB_KEY_IV). Using a fixed IV is
 *    intentional and safe here: this is key *derivation*, not data encryption. The
 *    security property is "the Keystore key is hardware-protected," not "the IV is
 *    unpredictable." A random IV would require persistence (and risk of loss), which
 *    would mean losing database access.
 *
 * 3. The resulting 48-byte ciphertext (32 bytes data + 16 bytes GCM auth tag) is the
 *    SQLCipher passphrase byte array. It is the same on every call as long as the
 *    Keystore key and the two compile-time constants remain unchanged.
 *
 * 4. The passphrase is passed to [SupportOpenHelperFactory] and never stored.
 *
 * Threat model: the database is unreadable without the Keystore key. On a non-rooted
 * device, the Keystore key cannot be extracted. On a rooted device, all bets are off
 * for any local-storage security scheme — SQLCipher is not a defense against root.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val KEYSTORE_ALIAS = "lithium_db_key_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /**
     * Migration from schema version 1 (Phase 0/M1 scaffold) to version 2 (M2 Correlate).
     *
     * Changes:
     * - notifications: add `is_from_contact` INTEGER NOT NULL DEFAULT 0
     * - sessions: add `package_name` TEXT NOT NULL DEFAULT '',
     *             add `duration_ms` INTEGER (nullable)
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE notifications ADD COLUMN is_from_contact INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN package_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE sessions ADD COLUMN duration_ms INTEGER")
        }
    }

    /**
     * Fixed 32-byte plaintext that the Keystore key encrypts to produce the passphrase.
     * This is NOT a secret — the security comes from the Keystore key, not this value.
     * Changing this constant will make the existing database unreadable.
     */
    private val DB_KEY_PLAINTEXT = byteArrayOf(
        0x4c, 0x69, 0x74, 0x68, 0x69, 0x75, 0x6d, 0x44, // LithiumD
        0x61, 0x74, 0x61, 0x62, 0x61, 0x73, 0x65, 0x4b, // atabaseK
        0x65, 0x79, 0x56, 0x65, 0x72, 0x73, 0x69, 0x6f, // eyVersio
        0x6e, 0x4f, 0x6e, 0x65, 0x00, 0x00, 0x00, 0x00  // nOne\0\0\0\0
    )

    /**
     * Fixed 12-byte GCM IV. See class-level doc for why this is safe.
     * Changing this constant will make the existing database unreadable.
     */
    private val DB_KEY_IV = byteArrayOf(
        0x4c, 0x54, 0x48, 0x4d, 0x44, 0x42, 0x49, 0x56, // LTHMDBIV
        0x30, 0x30, 0x31, 0x00                            // 001\0
    )

    /**
     * Retrieves or generates the AES-256-GCM Keystore key used for passphrase derivation.
     */
    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        // Return existing key if present
        (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?.let { return it }

        // Generate a new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Do not require user authentication — the database must open on boot
            // for the NotificationListenerService to function.
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Derives the SQLCipher passphrase from the Keystore key.
     *
     * Returns a 48-byte array: the AES-GCM ciphertext of DB_KEY_PLAINTEXT using
     * DB_KEY_IV. The result is deterministic given the same Keystore key.
     */
    private fun derivePassphrase(): ByteArray {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val ivSpec = GCMParameterSpec(128, DB_KEY_IV)
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)
        return cipher.doFinal(DB_KEY_PLAINTEXT)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LithiumDatabase {
        val passphrase = derivePassphrase()
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(passphrase)

        return Room.databaseBuilder(
            context,
            LithiumDatabase::class.java,
            "lithium.db"
        )
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2)
            // No fallback destructive migration — force explicit migrations.
            // If a migration is missing, the app crashes loudly rather than
            // silently wiping user data.
            .build()
    }

    @Provides
    fun provideNotificationDao(db: LithiumDatabase): NotificationDao = db.notificationDao()

    @Provides
    fun provideSessionDao(db: LithiumDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideRuleDao(db: LithiumDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideReportDao(db: LithiumDatabase): ReportDao = db.reportDao()

    @Provides
    fun provideSuggestionDao(db: LithiumDatabase): SuggestionDao = db.suggestionDao()

    @Provides
    fun provideQueueDao(db: LithiumDatabase): QueueDao = db.queueDao()
}
