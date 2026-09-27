package app.sunnyside.news.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.R
import app.sunnyside.news.data.PetKind
import app.sunnyside.news.ui.HomeViewModel
import app.sunnyside.news.ui.components.EmptyState
import app.sunnyside.news.ui.components.FeedFooter
import app.sunnyside.news.ui.components.PinnedPetsCard
import app.sunnyside.news.ui.components.PostCallbacks
import app.sunnyside.news.ui.components.SortBar
import app.sunnyside.news.ui.components.postItems
import app.sunnyside.news.util.timeAgo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    callbacks: PostCallbacks,
    snackbar: SnackbarHostState,
    onOpenPets: (PetKind) -> Unit,
    onSearch: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val updatedAt by viewModel.updatedAt.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose { }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(40.dp))
                    Text(
                        "sunnyside",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            actions = {
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        )

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                item(key = "sort") {
                    SortBar(
                        sort = settings.sort,
                        view = settings.view,
                        updatedLabel = updatedAt?.let { "Updated ${timeAgo(it).lowercase()}" },
                        onSort = viewModel::setSort,
                        onToggleView = viewModel::toggleView,
                    )
                }
                item(key = "pets") {
                    PinnedPetsCard(
                        state.kitten, state.puppy, onOpen = onOpenPets,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                if (state.loaded && state.posts.isEmpty() && !refreshing) {
                    item(key = "empty") {
                        EmptyState(
                            "🌤️",
                            if (settings.joined.isEmpty()) "Your feed is empty" else "The sun is still rising",
                            if (settings.joined.isEmpty()) "Join some communities to fill your feed." else "Pull down to fetch today's good news.",
                        )
                    }
                }
                postItems(state.posts, settings.view, user, callbacks)
                if (state.posts.isNotEmpty()) item(key = "footer") { FeedFooter() }
            }
        }
    }
}

