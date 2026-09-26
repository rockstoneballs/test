package app.sunnyside.news.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.ui.DetailViewModel
import app.sunnyside.news.ui.components.CategoryLabel
import app.sunnyside.news.ui.components.EmptyState
import app.sunnyside.news.ui.components.SaveButton
import app.sunnyside.news.ui.components.SectionHeader
import app.sunnyside.news.ui.components.StoryImage
import app.sunnyside.news.ui.components.StoryRow
import app.sunnyside.news.util.openInBrowser
import app.sunnyside.news.util.shareText
import app.sunnyside.news.util.timeAgo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryDetailScreen(viewModel: DetailViewModel, onBack: () -> Unit, onOpenStory: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    val story = state.story

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (story != null) {
                        IconButton(onClick = {
                            shareText(context, story.title, "${story.title}\n\n${story.url}\n\nShared from Sunnyside ☀️")
                        }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                        SaveButton(state.saved, onToggle = { viewModel.toggleSaved() }, tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (story == null) {
            if (state.loaded) {
                EmptyState("🍃", "Story not found", "This story has rolled off the feed.", Modifier.padding(padding))
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryLabel(story.category)
                        Text(
                            "  ·  ${story.region.emoji} ${story.region.label.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(story.title, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${story.source} · ${timeAgo(story.publishedAtMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
            item {
                StoryImage(
                    story.imageUrl, story.category,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f),
                    emojiSize = 72,
                )
            }
            item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (story.summary.isNotBlank()) {
                        Text(story.summary, style = MaterialTheme.typography.bodyLarge)
                    }
                    UpliftMeter(story.uplift)
                    Button(
                        onClick = { openInBrowser(context, story.url, toolbarColor) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Read the full story at ${story.source}")
                        Spacer(Modifier.size(8.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (state.related.isNotEmpty()) {
                item { SectionHeader("More ${story.category.label.lowercase()} news", Modifier.padding(top = 8.dp)) }
                items(state.related, key = { it.id }) { related ->
                    StoryRow(
                        story = related,
                        saved = related.id in state.savedIds,
                        onClick = { onOpenStory(related.id) },
                        onToggleSave = { viewModel.toggleSaved(related) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpliftMeter(uplift: Int) {
    val suns = (uplift.coerceIn(0, 10) + 1) / 2
    AssistChip(
        onClick = {},
        label = {
            Text("Mood boost  " + "☀️".repeat(suns.coerceAtLeast(1)))
        },
    )
}
