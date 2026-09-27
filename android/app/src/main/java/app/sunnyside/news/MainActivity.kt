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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingStoryId by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) lifecycleScope.launch { container.settingsRepository.setMorningBriefing(false) }
    }

    private val container get() = (application as SunnysideApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingStoryId = intent.getStringExtra(EXTRA_STORY_ID)

        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = Settings())
            SunnysideTheme(mode = settings.theme) {
                SunnysideNavHost(openStoryId = pendingStoryId, onStoryOpened = { pendingStoryId = null })
            }
        }

        askForNotificationsOnFirstLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_STORY_ID)?.let { pendingStoryId = it }
    }

    /** The morning briefing is the heart of the app, so offer it once on first launch. */
    private fun askForNotificationsOnFirstLaunch() {
        lifecycleScope.launch {
            val repo = container.settingsRepository
            if (repo.current().onboarded) return@launch
            repo.setOnboarded()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(this@MainActivity)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_STORY_ID = "story_id"
    }
}
