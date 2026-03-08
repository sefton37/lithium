package ai.talkingrock.lithium.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * AI engine dependency bindings (M3).
 *
 * [ai.talkingrock.lithium.ai.AiEngine] and [ai.talkingrock.lithium.ai.NotificationClassifier]
 * are both annotated with @Singleton and @Inject constructor, so Hilt binds them automatically
 * without explicit @Provides methods in this module.
 *
 * This module is retained as the designated home for future AI bindings:
 * - OrtEnvironment / OrtSession when full ONNX inference is wired (PLAN.md §M3.2)
 * - Model file path qualifier when multiple models are supported (M4+)
 * - Tokenizer binding if a separate Tokenizer.kt is added (PLAN.md §M3.2 option 2)
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    // Explicit @Provides entries will be added here when OrtEnvironment and OrtSession
    // need to be provided (M3 full ONNX integration). See PLAN.md §M3.1.
}
