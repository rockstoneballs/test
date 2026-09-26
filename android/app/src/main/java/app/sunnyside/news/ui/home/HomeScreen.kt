package app.sunnyside.news.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.data.Category
import app.sunnyside.news.data.PetKind
import app.sunnyside.news.data.Story
import app.sunnyside.news.ui.HomeState
import app.sunnyside.news.ui.HomeViewModel
import app.sunnyside.news.ui.components.EmptyState
import app.sunnyside.news.ui.components.HeroStoryCard
import app.sunnyside.news.ui.components.PetCard
import app.sunnyside.news.ui.components.SectionHeader
import app.sunnyside.news.ui.components.StoryRow
import app.sunnyside.news.ui.components.StoryTile
import app.sunnyside.news.util.greeting
import app.sunnyside.news.util.todayLabel

private const val DAY_MS = 24 * 60 * 60 * 1000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    snackbar: SnackbarHostState,
    onOpenStory: (String) -> Unit,
    onOpenPets: (PetKind) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val layout = remember(state.stories, state.category) { HomeLayout.from(state) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "header") { Greeting(todayCount = state.stories.count { it.publishedAtMillis > System.currentTimeMillis() - DAY_MS }) }

            stickyHeader(key = "chips") {
                CategoryChips(selected = state.category, onSelect = viewModel::selectCategory)
            }

            if (state.loaded && state.stories.isEmpty() && !refreshing) {
                item(key = "empty") {
                    EmptyState(
                        emoji = "🌤️",
                        title = if (state.category == null) "The sun is still rising" else "Nothing here yet",
                        body = if (state.category == null) {
                            "Pull down to fetch today's good news."
                        } else {
                            "No ${state.category?.label?.lowercase()} stories right now — check back soon."
                        },
                    )
                }
            }

            layout.hero?.let { hero ->
                item(key = "hero") {
                    HeroStoryCard(
                        story = hero,
                        saved = hero.id in state.savedIds,
                        onClick = { onOpenStory(hero.id) },
                        onToggleSave = { viewModel.toggleSaved(hero) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (state.category == null && (state.kitten != null || state.puppy != null)) {
                item(key = "pets") {
                    Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                        SectionHeader("Cuteness break", subtitle = "Today's furry friends, chosen fresh each morning")
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            state.kitten?.let { PetCard(it, onClick = { onOpenPets(PetKind.Kitten) }, modifier = Modifier.weight(1f)) }
                            state.puppy?.let { PetCard(it, onClick = { onOpenPets(PetKind.Puppy) }, modifier = Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (layout.top.isNotEmpty()) {
                item(key = "top-header") { SectionHeader("Top stories", Modifier.padding(top = 20.dp)) }
                storyRows(layout.top, state, viewModel, onOpenStory)
            }

            if (layout.world.isNotEmpty()) {
                item(key = "world") {
                    Column(Modifier.padding(top = 24.dp, bottom = 8.dp)) {
                        SectionHeader("Around the world", subtitle = "Good news from every corner of the planet")
                        Spacer(Modifier.height(12.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(layout.world, key = { it.id }) { story ->
                                StoryTile(story, onClick = { onOpenStory(story.id) })
                            }
                        }
                    }
                }
            }

            if (layout.more.isNotEmpty()) {
                item(key = "more-header") {
                    SectionHeader(if (state.category == null) "More good news" else "Latest", Modifier.padding(top = 20.dp))
                }
                storyRows(layout.more, state, viewModel, onOpenStory)
            }

            if (layout.more.isNotEmpty() || layout.top.isNotEmpty()) {
                item(key = "footer") {
                    Text(
                        "You're all caught up ☀️",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.storyRows(
    stories: List<Story>,
    state: HomeState,
    viewModel: HomeViewModel,
    onOpenStory: (String) -> Unit,
) {
    items(stories, key = { it.id }) { story ->
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

@Composable
private fun Greeting(todayCount: Int) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Text(
            "☀️  SUNNYSIDE  ·  ${todayLabel().uppercase()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text("${greeting()}!", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                todayCount > 1 -> "$todayCount good things happened in the last day. Here's what's going right."
                else -> "Here's what's going right in the world."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryChips(selected: Category?, onSelect: (Category?) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("✨ For you") })
            }
            items(Category.entries) { category ->
                FilterChip(
                    selected = selected == category,
                    onClick = { onSelect(if (selected == category) null else category) },
                    label = { Text("${category.emoji} ${category.label}") },
                )
            }
        }
    }
}

/** Splits the story list into the sections of the home page. */
private data class HomeLayout(
    val hero: Story?,
    val top: List<Story>,
    val world: List<Story>,
    val more: List<Story>,
) {
    companion object {
        fun from(state: HomeState): HomeLayout {
            val stories = state.stories
            if (stories.isEmpty()) return HomeLayout(null, emptyList(), emptyList(), emptyList())
            if (state.category != null) return HomeLayout(stories.first(), emptyList(), emptyList(), stories.drop(1))

            // Lead with the most uplifting of the freshest stories, preferring ones with a photo.
            val hero = stories.take(12).maxByOrNull { it.uplift * 10 + (if (it.imageUrl != null) 5 else 0) }!!
            val rest = stories - hero
            val top = rest.take(6)
            val world = (rest - top.toSet())
                .groupBy { it.region }
                .filterKeys { it != app.sunnyside.news.data.Region.Global }
                .map { (_, list) -> list.first() }
                .sortedByDescending { it.publishedAtMillis }
            val more = rest - top.toSet() - world.toSet()
            return HomeLayout(hero, top, world, more)
        }
    }
}
