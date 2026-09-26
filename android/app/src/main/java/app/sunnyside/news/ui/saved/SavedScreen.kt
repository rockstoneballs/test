package app.sunnyside.news.ui.saved

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.ui.SavedViewModel
import app.sunnyside.news.ui.components.EmptyState
import app.sunnyside.news.ui.components.StoryRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(viewModel: SavedViewModel, onOpenStory: (String) -> Unit) {
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Saved") }, scrollBehavior = scroll) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        val list = stories ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyState(
                "🔖",
                "Nothing saved yet",
                "Tap the bookmark on any story to keep it here for a rainy day.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(list, key = { it.id }) { story ->
                Column {
                    StoryRow(
                        story = story,
                        saved = true,
                        onClick = { onOpenStory(story.id) },
                        onToggleSave = { viewModel.remove(story) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}
