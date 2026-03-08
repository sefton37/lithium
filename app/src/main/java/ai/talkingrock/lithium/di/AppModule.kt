package ai.talkingrock.lithium.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Application-scoped singleton bindings.
 *
 * Provides:
 * - [Context] (application context, via @ApplicationContext — Hilt built-in qualifier)
 * - [EncryptedSharedPreferences] for user settings and config values that must
 *   survive process death but never appear in backups.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * EncryptedSharedPreferences backed by an AES-256-GCM key stored in
     * the Android Keystore. Used for:
     * - User preferences (theme, notification sound, digest schedule)
     * - Feature flags (onboarding complete, permission grant state)
     *
     * NOT used for the database key. The database key lives in DatabaseModule
     * and is derived via a separate Keystore key.
     */
    @Provides
    @Singleton
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "lithium_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
