package app.sunnyside.news

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.sunnyside.news.data.Settings
import app.sunnyside.news.ui.SunnysideNavHost
import app.sunnyside.news.ui.theme.SunnysideTheme
import app.sunnyside.news.work.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingStoryId by mutableStateOf<String?>(null)

    /** An emailed sign-in link handed back by the website (sunnyside://finish-signin?link=…). */
    private var pendingSignInLink by mutableStateOf<String?>(null)

    /** Becomes true once first-launch prompts are out of the way, so the ad consent message doesn't collide with them. */
    private val promptsDone = MutableStateFlow(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) lifecycleScope.launch { container.settingsRepository.setMorningBriefing(false) }
        promptsDone.value = true
    }

    private val container get() = (application as SunnysideApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            pendingStoryId = intent.getStringExtra(EXTRA_STORY_ID)
            pendingSignInLink = signInLinkFrom(intent)
        }

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = Settings())
            SunnysideTheme(mode = settings.theme) {
                SunnysideNavHost(
                    openStoryId = pendingStoryId,
                    onStoryOpened = { pendingStoryId = null },
                    signInLink = pendingSignInLink,
                    onSignInLinkHandled = { pendingSignInLink = null },
                )
            }
        }

        askForNotificationsOnFirstLaunch()
        startAdsUnlessAdFree()
    }

    override fun onResume() {
        super.onResume()
        container.billing.refresh() // picks up purchases made elsewhere, renewals and cancellations
    }

    /**
     * Readers who see ads get Google's consent message (UK/EEA) and AdMob. Waits until Play has
     * said whether they subscribe, so subscribers are never asked.
     */
    private fun startAdsUnlessAdFree() {
        lifecycleScope.launch {
            promptsDone.first { it }
            container.billing.checked.first { it }
            container.adFree.first { adFree -> !adFree }
            container.ads.start(this@MainActivity)
        }
    }

    private fun signInLinkFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "sunnyside" || data.host != "finish-signin") return null
        return data.getQueryParameter("link")?.takeIf { container.accounts.isEmailLink(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_STORY_ID)?.let { pendingStoryId = it }
        signInLinkFrom(intent)?.let { pendingSignInLink = it }
    }

    /** The morning briefing is the heart of the app, so offer it once on first launch. */
    private fun askForNotificationsOnFirstLaunch() {
        lifecycleScope.launch {
            val repo = container.settingsRepository
            if (repo.current().onboarded) {
                promptsDone.value = true
                return@launch
            }
            repo.setOnboarded()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(this@MainActivity)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                promptsDone.value = true
            }
        }
    }

    companion object {
        const val EXTRA_STORY_ID = "story_id"
    }
}
