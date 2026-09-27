package app.sunnyside.news.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.data.Community
import app.sunnyside.news.ui.CommunitiesViewModel
import app.sunnyside.news.ui.CommunityViewModel
import app.sunnyside.news.ui.components.CommunityAvatar
import app.sunnyside.news.ui.components.EmptyState
import app.sunnyside.news.ui.components.FeedFooter
import app.sunnyside.news.ui.components.PostCallbacks
import app.sunnyside.news.ui.components.SortBar
import app.sunnyside.news.ui.components.postItems
import app.sunnyside.news.ui.theme.accent

@Composable
private fun JoinButton(joined: Boolean, onClick: () -> Unit) {
    if (joined) {
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Joined") }
    } else {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Join") }
    }
}

/** A single community: banner, join button, sorted posts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(viewModel: CommunityViewModel, callbacks: PostCallbacks, onBack: () -> Unit) {
    val posts by viewModel.posts.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val community = viewModel.community

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("s/${community.label}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            item(key = "banner") {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .background(Brush.horizontalGradient(listOf(community.accent(), community.accent().copy(alpha = 0.5f)))),
                    )
                    Row(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .offset(y = (-24).dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        CommunityAvatar(
                            community, 68.dp,
                            Modifier.border(4.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("s/${community.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text("${posts?.size ?: 0} posts this week", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        JoinButton(community in settings.joined) { viewModel.toggleJoined(community) }
                    }
                    Text(
                        community.about,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).offset(y = (-12).dp),
                    )
                }
            }
            item(key = "sort") {
                SortBar(settings.sort, settings.view, null, onSort = viewModel::setSort, onToggleView = viewModel::toggleView)
            }
            val list = posts
            if (list != null && list.isEmpty()) {
                item(key = "empty") { EmptyState(community.emoji, "Nothing here right now", "New posts arrive every half hour.") }
            }
            if (list != null) {
                postItems(list, settings.view, user, callbacks, showCommunity = false)
                if (list.isNotEmpty()) item(key = "footer") { FeedFooter() }
            }
        }
    }
}

/** The Communities tab: every community with a join toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunitiesScreen(viewModel: CommunitiesViewModel, onOpen: (Community) -> Unit) {
    val communities by viewModel.communities.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Communities") }, scrollBehavior = scroll) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Text(
                    "Join the communities you want on your Home feed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(communities, key = { it.community.name }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(item.community) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommunityAvatar(item.community, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("s/${item.community.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            item.community.about,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        Text("${item.posts} posts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    JoinButton(item.joined) { viewModel.toggleJoined(item.community) }
                }
                HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
