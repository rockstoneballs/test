package app.sunnyside.news.ui.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.data.Category
import app.sunnyside.news.data.Region
import app.sunnyside.news.ui.ExploreViewModel
import app.sunnyside.news.ui.components.EmptyState
import app.sunnyside.news.ui.components.SectionHeader
import app.sunnyside.news.ui.components.StoryRow

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ExploreScreen(viewModel: ExploreViewModel, onOpenStory: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    LazyColumn(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                Text("Explore", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Find good news by place, topic or keyword",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.background) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search puppies, solar, Kenya…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        item {
            Column {
                SectionHeader("Around the world", Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Region.entries.forEach { region ->
                        FilterChip(
                            selected = state.region == region,
                            onClick = { viewModel.setRegion(if (state.region == region) null else region) },
                            label = { Text("${region.emoji} ${region.label} (${state.regionCounts[region] ?: 0})") },
                        )
                    }
                }
            }
        }

        if (!state.filtering) {
            item {
                Column {
                    SectionHeader("Topics", Modifier.padding(top = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Category.entries.chunked(2).forEach { row ->
                            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { category ->
                                    TopicCard(
                                        category = category,
                                        count = state.categoryCounts[category] ?: 0,
                                        onClick = { viewModel.setCategory(category) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                androidx.compose.foundation.layout.Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    val label = listOfNotNull(state.category?.let { "${it.emoji} ${it.label}" }, state.region?.label)
                        .joinToString(" · ").ifEmpty { "Results" }
                    Text(
                        "$label — ${state.results.size} ${if (state.results.size == 1) "story" else "stories"}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clear) { Text("Clear") }
                }
            }
            if (state.results.isEmpty()) {
                item { EmptyState("🔍", "No matches", "Try another place or a different word.") }
            }
            items(state.results, key = { it.id }) { story ->
                Column {
                    StoryRow(
                        story = story,
                        saved = story.id in state.savedIds,
                        onClick = { onOpenStory(story.id) },
                        onToggleSave = { viewModel.toggleSaved(story) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun TopicCard(category: Category, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(category.emoji, fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))
            Text(category.label, style = MaterialTheme.typography.titleMedium)
            Text(
                "$count ${if (count == 1) "story" else "stories"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
