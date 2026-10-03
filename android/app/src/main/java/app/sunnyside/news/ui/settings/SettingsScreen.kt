package app.sunnyside.news.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.BuildConfig
import app.sunnyside.news.data.ThemeMode
import app.sunnyside.news.ui.SettingsViewModel
import app.sunnyside.news.ui.feedback.FeedbackDialog
import app.sunnyside.news.util.openInBrowser
import app.sunnyside.news.work.Notifications
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val SOURCES = listOf(
    "Good News Network", "Positive News", "Reasons to be Cheerful", "The Optimist Daily",
    "YES! Magazine", "The Guardian — The Upside", "Good Good Good", "Nice News", "Squirrel News",
    "BBC", "The Guardian", "Sky News", "RTÉ", "TheJournal.ie", "Irish Examiner", "BreakingNews.ie",
    "NPR", "DW", "France 24", "CBC", "ABC Australia", "RNZ",
    "ScienceDaily", "Phys.org", "NASA", "Mongabay", "Google News",
    "Reddit", "Lemmy", "9GAG", "Imgur",
)

private const val PRIVACY_POLICY_URL = "https://rockstoneballs.github.io/test/privacy.html"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setMorningBriefing(granted)
    }

    fun toggleBriefing(enabled: Boolean) {
        if (enabled && !Notifications.canPost(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setMorningBriefing(enabled)
        }
    }

    val time = LocalTime.of(settings.briefingHour, settings.briefingMinute)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Settings") }, scrollBehavior = scroll) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("Morning briefing")
            ListItem(
                headlineContent = { Text("Daily good-news notification") },
                supportingContent = { Text("Start the day with something nice instead of doomscrolling") },
                leadingContent = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null) },
                trailingContent = { Switch(checked = settings.morningBriefing, onCheckedChange = ::toggleBriefing) },
                modifier = Modifier.clickable { toggleBriefing(!settings.morningBriefing) },
            )
            ListItem(
                headlineContent = { Text("Delivery time") },
                supportingContent = { Text(time) },
                leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                modifier = Modifier.clickable(enabled = settings.morningBriefing) {
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> viewModel.setBriefingTime(hour, minute) },
                        settings.briefingHour,
                        settings.briefingMinute,
                        DateFormat.is24HourFormat(context),
                    ).show()
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Appearance")
            ThemeMode.entries.forEach { mode ->
                ListItem(
                    headlineContent = {
                        Text(
                            when (mode) {
                                ThemeMode.System -> "Match system"
                                ThemeMode.Light -> "Light"
                                ThemeMode.Dark -> "Dark"
                            },
                        )
                    },
                    leadingContent = {
                        val icon = when (mode) {
                            ThemeMode.System -> Icons.Outlined.BrightnessAuto
                            ThemeMode.Light -> Icons.Outlined.LightMode
                            ThemeMode.Dark -> Icons.Outlined.DarkMode
                        }
                        Icon(icon, contentDescription = null)
                    },
                    trailingContent = { RadioButton(selected = settings.theme == mode, onClick = null) },
                    modifier = Modifier.selectable(
                        selected = settings.theme == mode,
                        onClick = { viewModel.setTheme(mode) },
                        role = Role.RadioButton,
                    ),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Where the good news comes from")
            Text(
                "New posts are gathered every half hour. Dedicated good-news publications and " +
                    "wholesome meme and cute-animal pages are included as-is; stories from general news outlets are " +
                    "only included when they pass our positivity filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                SOURCES.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Cat photos: The Cat API · Dog photos: Dog CEO",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            var showFeedback by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("Send feedback") },
                supportingContent = { Text("Ideas, problems, or a source we should add") },
                leadingContent = { Icon(Icons.Outlined.Feedback, contentDescription = null) },
                modifier = Modifier.clickable(onClickLabel = "Send feedback") { showFeedback = true },
            )
            if (showFeedback) FeedbackDialog(story = null, onDismiss = { showFeedback = false })
            val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
            ListItem(
                headlineContent = { Text("Privacy policy") },
                supportingContent = { Text("No accounts, ads or tracking") },
                leadingContent = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
                modifier = Modifier.clickable(onClickLabel = "Open the privacy policy") {
                    openInBrowser(context, PRIVACY_POLICY_URL, toolbarColor)
                },
            )
            ListItem(
                headlineContent = { Text("Sunnyside ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("Only good news. Every morning. ☀️") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
