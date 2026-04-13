package ai.talkingrock.lithium.ui.setup

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.talkingrock.lithium.ui.theme.AccentPrimary
import ai.talkingrock.lithium.ui.theme.OnDarkMuted
import kotlinx.coroutines.launch

/**
 * Multi-page onboarding screen shown to first-time users.
 *
 * Pages:
 * 0 — Title: branded "LITHIUM" screen (matches Helm's title style)
 * 1 — Welcome: what Lithium does
 * 2 — Privacy Promise: data never leaves device
 * 3 — Notification Access permission
 * 4 — Battery Optimization exemption (skippable)
 * 4b (5) — Shade Ownership explanation (informational)
 * 6 — Usage Access permission (skippable)
 * 7 — Contacts permission (optional, skippable)
 * 8 — Learning Period explanation + "Get Started"
 *
 * If [SetupViewModel.onboardingComplete] is true (returning user whose notification
 * access was revoked), the pager starts at the Notification Access page (index 3).
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Refresh permission state when returning from system dialogs (battery, usage, contacts)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Returning users who lost permission skip the intro pages
    val initialPage = if (viewModel.onboardingComplete) 3 else 0
    val pageCount = 9
    val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* State updates via ViewModel flow on recomposition */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp)
    ) {
        // Pager content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false  // navigate via buttons only
        ) { page ->
            when (page) {
                0 -> TitlePage()
                1 -> WelcomePage()
                2 -> PrivacyPage()
                3 -> NotificationAccessPage(
                    granted = uiState.notificationAccessGranted,
                    onGrant = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    }
                )
                4 -> BatteryOptimizationPage(
                    exempt = uiState.batteryOptimizationExempt,
                    onRequest = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
                5 -> ShadeOwnershipPage()
                6 -> UsageAccessPage(
                    granted = uiState.usageAccessGranted,
                    onGrant = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                )
                7 -> ContactsPage(
                    granted = uiState.contactsGranted,
                    onGrant = {
                        contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                    }
                )
                8 -> LearningPeriodPage()
            }
        }

        // Page indicator dots (hidden on title page for cleaner look)
        if (pagerState.currentPage > 0) {
            PageIndicator(
                currentPage = pagerState.currentPage,
                pageCount = pageCount,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )
        } else {
            Spacer(Modifier.height(40.dp))
        }

        // Navigation buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button (hidden on first two pages and last page)
            if (pagerState.currentPage > 1 && pagerState.currentPage < pageCount - 1) {  // pageCount is now 9
                TextButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }
                ) {
                    Text("Back")
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }

            when (pagerState.currentPage) {
                // Title page — tap anywhere or button to proceed
                0 -> {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Next")
                    }
                }
                // Welcome and Privacy pages — simple "Next"
                1, 2 -> {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Next")
                    }
                }
                // Notification Access — must be granted to proceed
                3 -> {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(4) } },
                        enabled = uiState.notificationAccessGranted,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Next")
                    }
                }
                // Battery Optimization — skippable
                4 -> {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(5) } },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(if (uiState.batteryOptimizationExempt) "Next" else "Skip")
                    }
                }
                // Shade Ownership — informational only, always Next
                5 -> {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(6) } },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Next")
                    }
                }
                // Usage Access — can proceed even if not granted
                6 -> {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(7) } },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(if (uiState.usageAccessGranted) "Next" else "Skip")
                    }
                }
                // Contacts — optional, skip or continue
                7 -> {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(8) } },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(if (uiState.contactsGranted) "Next" else "Skip")
                    }
                }
                // Learning period — final page, "Get Started"
                8 -> {
                    Button(
                        onClick = {
                            viewModel.markOnboardingComplete()
                            onSetupComplete()
                        },
                        enabled = uiState.notificationAccessGranted,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Get Started")
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// Pages
// -----------------------------------------------------------------------------------------

@Composable
private fun TitlePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(80.dp))

        // Brand title
        Text(
            text = "L I T H I U M",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Light,
                fontSize = 28.sp,
                letterSpacing = 6.sp
            ),
            color = AccentPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "by Talking Rock",
            style = MaterialTheme.typography.bodyMedium,
            color = OnDarkMuted
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A private mobile attention manager",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Normal
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(48.dp))

        // Mission
        Text(
            text = "Mission",
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 2.sp
            ),
            color = AccentPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Center your data around you, not a data center, so that your " +
                    "attention is centered on what you value. Local, zero-trust AI. " +
                    "Small models and footprint, outsized impact and trust.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(32.dp))

        // Vision
        Text(
            text = "Vision",
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 2.sp
            ),
            color = AccentPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "AI that partners with you and your values, not to automate you. " +
                    "Intent always verified, permission always requested, all learning " +
                    "reviewable, editable, and auditable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPage(
        title = "Welcome to Lithium",
        body = "Lithium is a private notification manager built for people who " +
                "feel overwhelmed by the constant buzz of their phone.\n\n" +
                "It watches your notifications, learns which ones actually matter to you, " +
                "and helps you silence the noise — so the important stuff always gets through.\n\n" +
                "No accounts. No cloud. Just your phone, working for you."
    )
}

@Composable
private fun PrivacyPage() {
    OnboardingPage(
        title = "Your Data Stays Here",
        body = "Lithium has no internet permission. Your notification data physically " +
                "cannot leave your device.\n\n" +
                "Everything is stored in an encrypted database on your phone. " +
                "There are no analytics, no tracking, no cloud syncing, " +
                "and no third-party services.\n\n" +
                "The AI that classifies your notifications runs entirely on-device. " +
                "No one — not even us — can see your data.\n\n" +
                "This isn't a privacy policy. It's a technical guarantee.\n\n" +
                "Lithium is open source under the MIT license. " +
                "You can read every line of code that runs on your phone — " +
                "the source will be published at github.com/sefton37/Lithium."
    )
}

@Composable
private fun NotificationAccessPage(granted: Boolean, onGrant: () -> Unit) {
    PermissionPage(
        title = "Notification Access",
        explanation = "Lithium needs to see your notifications to learn which ones " +
                "matter to you and which ones are noise.\n\n" +
                "This permission lets Lithium read notification titles and content " +
                "so it can classify them into categories like personal messages, " +
                "social media engagement bait, promotions, and system alerts.\n\n" +
                "Without this, Lithium can't do anything — it's the one required permission.",
        granted = granted,
        grantLabel = "Open Settings",
        onGrant = onGrant,
        isRequired = true
    )
}

@Composable
private fun BatteryOptimizationPage(exempt: Boolean, onRequest: () -> Unit) {
    // Detect OEM for device-specific guidance
    val manufacturer = Build.MANUFACTURER.lowercase()
    val oemGuidance: String? = when {
        manufacturer.contains("samsung") ->
            "On Samsung devices, also go to Settings \u2192 Battery \u2192 Background usage limits " +
            "and make sure Lithium is not in the \u2018Sleeping apps\u2019 or \u2018Deep sleeping apps\u2019 lists."
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
            "On Xiaomi devices, go to Settings \u2192 Battery \u2192 App battery saver \u2192 Lithium \u2192 " +
            "No restrictions. Also enable Autostart in Security \u2192 Manage apps."
        manufacturer.contains("oneplus") || manufacturer.contains("realme") || manufacturer.contains("oppo") ->
            "On OnePlus devices, go to Settings \u2192 Battery \u2192 Battery optimization \u2192 " +
            "Lithium \u2192 Don\u2019t optimize."
        manufacturer.contains("huawei") || manufacturer.contains("honor") ->
            "On Huawei devices, go to Settings \u2192 Battery \u2192 App launch \u2192 Lithium \u2192 " +
            "Manage manually and enable all three toggles."
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Battery Optimization",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Android may stop Lithium from running in the background to save battery. " +
                    "Exempting Lithium ensures it can always monitor your notifications.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
        )

        Spacer(Modifier.height(32.dp))

        if (exempt) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Exempted",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Battery optimization exempted",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            OutlinedButton(
                onClick = onRequest,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Request Exemption")
            }
        }

        if (oemGuidance != null) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = oemGuidance,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2
            )
        }
    }
}

@Composable
private fun UsageAccessPage(granted: Boolean, onGrant: () -> Unit) {
    PermissionPage(
        title = "Usage Access",
        explanation = "This lets Lithium measure how long you spend in an app after " +
                "tapping a notification.\n\n" +
                "A notification that leads to 20 minutes of scrolling tells Lithium " +
                "something different than one you glance at for 5 seconds. " +
                "This helps distinguish genuinely useful notifications from time sinks.\n\n" +
                "Recommended but not required.",
        granted = granted,
        grantLabel = "Open Settings",
        onGrant = onGrant,
        isRequired = false
    )
}

@Composable
private fun ContactsPage(granted: Boolean, onGrant: () -> Unit) {
    PermissionPage(
        title = "Contacts",
        explanation = "When Lithium can see your contacts, it can recognize messages " +
                "from people you know and prioritize them.\n\n" +
                "A text from Mom is different from a marketing push from a brand. " +
                "Contact matching helps Lithium make that distinction automatically.\n\n" +
                "Your contacts are never uploaded or shared — they're only used " +
                "for local matching on your device.",
        granted = granted,
        grantLabel = "Allow Access",
        onGrant = onGrant,
        isRequired = false
    )
}

@Composable
private fun ShadeOwnershipPage() {
    OnboardingPage(
        title = "Shade Mode",
        body = "Lithium is already in control of your notification shade. It intercepts every " +
                "notification, suppresses noise, and queues the rest for your briefing. Calls " +
                "and alarms always get through. Tier 3 notifications — texts from contacts, " +
                "2FA codes — pass through by default. You can turn Shade Mode off anytime in Settings."
    )
}

@Composable
private fun LearningPeriodPage() {
    OnboardingPage(
        title = "Give It a Few Days",
        body = "Lithium needs time to observe your notification patterns " +
                "before it can make good recommendations.\n\n" +
                "For the first few days, it will quietly watch and learn:\n" +
                "  \u2022  Which apps send you the most notifications\n" +
                "  \u2022  Which ones you tap vs. dismiss\n" +
                "  \u2022  How long you spend in each app\n" +
                "  \u2022  What types of notifications you care about\n\n" +
                "Once Lithium has enough data, it will notify you that your " +
                "first briefing is ready. Until then, all your notifications " +
                "will arrive normally — nothing is filtered yet."
    )
}

// -----------------------------------------------------------------------------------------
// Shared composables
// -----------------------------------------------------------------------------------------

@Composable
private fun OnboardingPage(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
        )
    }
}

@Composable
private fun PermissionPage(
    title: String,
    explanation: String,
    granted: Boolean,
    grantLabel: String,
    onGrant: () -> Unit,
    isRequired: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (isRequired) {
            Text(
                text = "Required",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = explanation,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
        )

        Spacer(Modifier.height(32.dp))

        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Granted",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Permission granted",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            OutlinedButton(
                onClick = onGrant,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(grantLabel)
            }
        }
    }
}

@Composable
private fun PageIndicator(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val color = if (index == currentPage)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
