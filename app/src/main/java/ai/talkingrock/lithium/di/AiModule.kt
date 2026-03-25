package ai.talkingrock.lithium.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * AI engine dependency bindings.
 *
 * Provides the model directory path used by [ai.talkingrock.lithium.ai.AiEngine] (ONNX)
 * and [ai.talkingrock.lithium.ai.LlamaEngine] (llama.cpp) to locate sideloaded models.
 *
 * Model files are sideloaded via ADB into `context.filesDir/models/`:
 * ```
 * adb push classification_v1.onnx /data/data/ai.talkingrock.lithium.debug/files/models/
 * adb push vocab.txt /data/data/ai.talkingrock.lithium.debug/files/models/
 * adb push llm_v1.gguf /data/data/ai.talkingrock.lithium.debug/files/models/
 * ```
 *
 * [ai.talkingrock.lithium.ai.AiEngine] and [ai.talkingrock.lithium.ai.NotificationClassifier]
 * are @Singleton @Inject constructor, so Hilt binds them automatically without explicit
 * @Provides methods.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    /**
     * Provides the absolute path to the models directory.
     *
     * Both ONNX and llama.cpp models live in the same directory:
     * - `*.onnx` files are loaded by AiEngine
     * - `vocab.txt` is loaded by WordPieceTokenizer (via AiEngine)
     * - `*.gguf` files are loaded by LlamaEngine
     */
    @Provides
    @Singleton
    @Named("modelDir")
    fun provideModelDir(@ApplicationContext context: Context): String {
        val dir = java.io.File(context.filesDir, "models")
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("AiModule", "provideModelDir: failed to create $dir")
        }
        return dir.absolutePath
    }
}
