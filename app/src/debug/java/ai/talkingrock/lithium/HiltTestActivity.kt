package ai.talkingrock.lithium

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Minimal activity used exclusively by @HiltAndroidTest Compose UI tests.
 *
 * This activity exists only to satisfy [dagger.hilt.android.testing.HiltAndroidTest]'s
 * requirement that an @AndroidEntryPoint activity exists as the Compose rule host.
 * It renders nothing itself — Compose tests call [androidx.compose.ui.test.junit4.createAndroidComposeRule]
 * and set their own content via [androidx.compose.ui.test.junit4.AndroidComposeTestRule.setContent].
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
