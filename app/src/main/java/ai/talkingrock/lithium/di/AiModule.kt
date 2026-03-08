package ai.talkingrock.lithium.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * AI engine dependency bindings — stub for Phase 0.
 *
 * Populated in M3: OnnxRuntime session, model file path, inference executor.
 * The module exists now so M3 can add bindings without touching other modules.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    // M3: provide OrtEnvironment, OrtSession, and AI worker bindings here.
}
