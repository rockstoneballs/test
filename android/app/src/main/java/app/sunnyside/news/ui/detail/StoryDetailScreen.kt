package app.sunnyside.news.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.data.PostKind
import app.sunnyside.news.data.Region
import app.sunnyside.news.data.Story
import app.sunnyside.news.ui.DetailViewModel
import app.sunnyside.news.ui.components.EmptyState
import app.sunnyside.news.ui.feedback.FeedbackDialog
import app.sunnyside.news.ui.components.PostCallbacks
import app.sunnyside.news.ui.components.PostHeader
import app.sunnyside.news.ui.components.PostMedia
import app.sunnyside.news.ui.components.PostTitle
import app.sunnyside.news.ui.components.SectionHeader
import app.sunnyside.news.ui.components.postItems
import app.sunnyside.news.data.Topic
import app.sunnyside.news.data.ViewMode
import app.sunnyside.news.util.domainOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryDetailScreen(
    viewModel: DetailViewModel,
    callbacks: PostCallbacks,
    onBack: () -> Unit,
    onReadArticle: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val story = state.story
    var reporting by remember { mutableStateOf(false) }
    if (reporting && story != null) FeedbackDialog(story = story, onDismiss = { reporting = false })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Post") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (story != null) {
                        val saved = story.id in user.savedIds
                        if (story.topic != Topic.Pets) {
                            IconButton(onClick = { callbacks.downer(story); onBack() }) {
                                Icon(Icons.Outlined.Cloud, contentDescription = "Not good news: hide this post")
                            }
                        }
                        IconButton(onClick = { reporting = true }) { Icon(Icons.Outlined.Flag, contentDescription = "Report this post") }
                        IconButton(onClick = { callbacks.share(story) }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                        IconButton(onClick = { callbacks.toggleSave(story) }) {
                            Icon(
                                if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = if (saved) "Remove from saved" else "Save",
                                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
    ) { padding ->
        if (story == null) {
            if (state.loaded) {
                EmptyState("🍃", "Post not found", "It may have drifted out of the feed. Posts stay for about a week.", Modifier.padding(padding))
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Column(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(16.dp),
                ) {
                    PostHeader(story)
                    Spacer(Modifier.height(10.dp))
                    PostTitle(story, MaterialTheme.typography.headlineSmall)
                    if (story.region != Region.Global) {
                        Text(
                            "📍 ${story.region.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    PostMedia(story, Modifier.padding(top = 12.dp), large = true)
                    StoryText(story)
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (story.kind == PostKind.Article) {
                            Button(
                                onClick = { onReadArticle(story.url) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 14.dp),
                            ) {
                                Text("${if (story.body.isNotBlank()) "Continue reading" else "Read the full story"} on ${domainOf(story.url)}")
                                Spacer(Modifier.size(8.dp))
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                        // Memes and animal photos: credit where they were first posted.
                        if (story.kind != PostKind.Article) {
                            story.discussionUrl?.let { original ->
                                Button(
                                    onClick = { callbacks.openOriginal(story) },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 14.dp),
                                ) {
                                    if (story.kind == PostKind.Video) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.size(8.dp))
                                    }
                                    Text((if (story.kind == PostKind.Video) "Watch it on " else "View the original post on ") + domainOf(original))
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (state.related.isNotEmpty()) {
                item { SectionHeader("More good news like this", Modifier.padding(top = 20.dp, bottom = 8.dp)) }
                postItems(state.related, ViewMode.Compact, user, callbacks)
            }
        }
    }
}

/**
 * The story itself: Claude's summary (when there is one) and the article's opening
 * paragraphs, credited to the outlet. Without an excerpt, just the summary.
 */
@Composable
private fun StoryText(story: Story) {
    val paragraphs = story.paragraphs
    if (paragraphs.isEmpty()) {
        if (story.summary.isNotBlank()) {
            Text(story.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        }
        return
    }
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (story.writtenSummary && story.summary.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("In short: ") }
                        append(story.summary)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        paragraphs.forEach { paragraph ->
            Text(
                paragraph,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, lineHeight = 27.sp),
            )
        }
        Text(
            "The opening of the story, from ${story.source}.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
