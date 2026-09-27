package app.sunnyside.news.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sunnyside.news.data.Community
import app.sunnyside.news.data.Pet
import app.sunnyside.news.data.PetKind
import app.sunnyside.news.data.PostKind
import app.sunnyside.news.data.Ranking
import app.sunnyside.news.data.SortMode
import app.sunnyside.news.data.Story
import app.sunnyside.news.data.ViewMode
import app.sunnyside.news.ui.theme.DownvoteColor
import app.sunnyside.news.ui.theme.UpvoteColor
import app.sunnyside.news.ui.theme.accent
import app.sunnyside.news.util.compactCount
import app.sunnyside.news.util.domainOf
import app.sunnyside.news.util.timeAgo
import coil.compose.SubcomposeAsyncImage

/** Everything a post can do. Screens build one of these and hand it to every post. */
class PostCallbacks(
    val open: (Story) -> Unit,
    val openCommunity: (Community) -> Unit,
    val vote: (Story, Int) -> Unit,
    val toggleSave: (Story) -> Unit,
    val share: (Story) -> Unit,
    val openDiscussion: (Story) -> Unit,
)

/** Your votes and saves, needed to draw each post's buttons. */
data class PostUserState(val votes: Map<String, Int> = emptyMap(), val savedIds: Set<String> = emptySet())

/** Adds [stories] to a LazyColumn in the chosen layout. */
fun LazyListScope.postItems(
    stories: List<Story>,
    view: ViewMode,
    user: PostUserState,
    callbacks: PostCallbacks,
    showCommunity: Boolean = true,
) {
    items(stories, key = { it.id }, contentType = { view }) { story ->
        val vote = user.votes[story.id] ?: 0
        val saved = story.id in user.savedIds
        when (view) {
            ViewMode.Card -> PostCard(story, vote, saved, callbacks, showCommunity, Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            ViewMode.Compact -> {
                PostRow(story, vote, saved, callbacks)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
fun CommunityAvatar(community: Community, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(community.accent().copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(community.emoji, fontSize = (size.value * 0.55f).sp)
    }
}

/** Post image with a soft community-coloured placeholder while loading or when missing. */
@Composable
fun PostImage(url: String?, community: Community, modifier: Modifier = Modifier, emojiSize: Int = 40, contentScale: ContentScale = ContentScale.Crop) {
    val placeholder = @Composable {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(listOf(community.accent().copy(alpha = 0.35f), community.accent().copy(alpha = 0.12f))),
                ),
            contentAlignment = Alignment.Center,
        ) { Text(community.emoji, fontSize = emojiSize.sp) }
    }
    Box(modifier) {
        if (url == null) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = { placeholder() },
                error = { placeholder() },
            )
        }
    }
}

@Composable
fun PostHeader(story: Story, callbacks: PostCallbacks, showCommunity: Boolean = true, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showCommunity) {
            CommunityAvatar(story.community, 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "s/${story.community.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(role = Role.Button) { callbacks.openCommunity(story.community) },
            )
            Text(" • ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "${timeAgo(story.publishedAtMillis)} • ${story.byline}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun PillSurface(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun ActionPill(icon: ImageVector, label: String?, contentDescription: String?, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    PillSurface(onClick = onClick) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.Bold)
    }
}

/** ▲ score ▼, horizontal (cards) or vertical (compact rows). */
@Composable
fun VoteControl(story: Story, myVote: Int, onVote: (Int) -> Unit, vertical: Boolean = false) {
    val scoreColor = when {
        myVote > 0 -> UpvoteColor
        myVote < 0 -> DownvoteColor
        else -> MaterialTheme.colorScheme.onSurface
    }
    val label = if (story.score == null && myVote == 0) "Vote" else compactCount(Ranking.points(story, myVote))
    val up = @Composable {
        Icon(
            Icons.Filled.ArrowUpward,
            contentDescription = if (myVote > 0) "Remove upvote" else "Upvote",
            tint = if (myVote > 0) UpvoteColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button) { onVote(1) }
                .padding(6.dp)
                .size(20.dp),
        )
    }
    val down = @Composable {
        Icon(
            Icons.Filled.ArrowDownward,
            contentDescription = if (myVote < 0) "Remove downvote" else "Downvote",
            tint = if (myVote < 0) DownvoteColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button) { onVote(-1) }
                .padding(6.dp)
                .size(20.dp),
        )
    }
    val score = @Composable {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = scoreColor, textAlign = TextAlign.Center)
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) { up(); score(); down() }
    } else {
        val bg = when {
            myVote > 0 -> UpvoteColor.copy(alpha = 0.14f)
            myVote < 0 -> DownvoteColor.copy(alpha = 0.14f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(50))
                .background(bg),
            verticalAlignment = Alignment.CenterVertically,
        ) { up(); Box(Modifier.widthIn(min = 24.dp), contentAlignment = Alignment.Center) { score() }; down() }
    }
}

@Composable
private fun PostActions(story: Story, myVote: Int, saved: Boolean, callbacks: PostCallbacks) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VoteControl(story, myVote, onVote = { callbacks.vote(story, it) })
        if (story.discussionUrl != null) {
            ActionPill(
                Icons.AutoMirrored.Outlined.Comment,
                story.comments?.let(::compactCount) ?: "Discuss",
                contentDescription = "Comments",
                onClick = { callbacks.openDiscussion(story) },
            )
        }
        ActionPill(Icons.Outlined.Share, null, contentDescription = "Share", onClick = { callbacks.share(story) })
        ActionPill(
            if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            null,
            contentDescription = if (saved) "Remove from saved" else "Save",
            onClick = { callbacks.toggleSave(story) },
            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun VideoBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(" Video", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LinkChip(url: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(domainOf(url), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Media block for a post: full image for memes/photos, 16:9 banner for articles. */
@Composable
fun PostMedia(story: Story, modifier: Modifier = Modifier, large: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    if (story.kind == PostKind.Article) {
        if (story.imageUrl == null && !large) return
        PostImage(
            story.imageUrl, story.community,
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape),
            emojiSize = 56,
        )
        return
    }
    val ratio = story.aspectRatio ?: 1f
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        PostImage(
            story.imageUrl, story.community,
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .heightIn(max = if (large) 720.dp else 560.dp),
            emojiSize = 56,
            contentScale = ContentScale.Fit,
        )
        if (story.kind == PostKind.Video) VideoBadge(Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

// ------------------------------------------------------------------ posts

/** Card layout: header, title, media, snippet, action pills. */
@Composable
fun PostCard(story: Story, myVote: Int, saved: Boolean, callbacks: PostCallbacks, showCommunity: Boolean = true, modifier: Modifier = Modifier) {
    Surface(
        onClick = { callbacks.open(story) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            PostHeader(story, callbacks, showCommunity)
            Spacer(Modifier.height(8.dp))
            Text(story.title, style = MaterialTheme.typography.titleMedium)
            PostMedia(story, Modifier.padding(top = 10.dp))
            if (story.summary.isNotBlank()) {
                Text(
                    story.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (story.kind == PostKind.Article) LinkChip(story.url, Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(10.dp))
            PostActions(story, myVote, saved, callbacks)
        }
    }
}

/** Compact layout: vote column, thumbnail, title and meta. */
@Composable
fun PostRow(story: Story, myVote: Int, saved: Boolean, callbacks: PostCallbacks) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { callbacks.open(story) }
            .padding(start = 2.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        VoteControl(story, myVote, onVote = { callbacks.vote(story, it) }, vertical = true)
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(width = 76.dp, height = 60.dp)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            PostImage(story.imageUrl, story.community, Modifier.fillMaxSize(), emojiSize = 26)
            if (story.kind == PostKind.Video) {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(story.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PostHeader(story, callbacks)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (story.discussionUrl != null) {
                    Text(
                        "💬 ${story.comments?.let(::compactCount) ?: "Discuss"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { callbacks.openDiscussion(story) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
                Icon(
                    if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (saved) "Remove from saved" else "Save",
                    tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { callbacks.toggleSave(story) }
                        .padding(6.dp)
                        .size(18.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ feed chrome

/** Hot / New / Top + card/compact toggle, like the bar at the top of a subreddit. */
@Composable
fun SortBar(
    sort: SortMode,
    view: ViewMode,
    updatedLabel: String?,
    onSort: (SortMode) -> Unit,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SortMode.entries.forEach { mode ->
            val selected = mode == sort
            Text(
                "${mode.emoji} ${mode.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSort(mode) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Text(
            updatedLabel.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        Text(
            if (view == ViewMode.Card) "☰" else "▦",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (view == ViewMode.Card) "Compact view" else "Card view") { onToggleView() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** The pinned "Kitten & Puppy of the Day" post at the top of Home. */
@Composable
fun PinnedPetsCard(kitten: Pet?, puppy: Pet?, onOpen: (PetKind) -> Unit, modifier: Modifier = Modifier) {
    if (kitten == null && puppy == null) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "📌 Pinned • Fresh every morning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text("Today's Kitten & Puppy of the Day", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                kitten?.let { PetTile(it, onClick = { onOpen(PetKind.Kitten) }, modifier = Modifier.weight(1f)) }
                puppy?.let { PetTile(it, onClick = { onOpen(PetKind.Puppy) }, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun PetTile(pet: Pet, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val kitten = pet.kind == PetKind.Kitten
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            SubcomposeAsyncImage(
                model = pet.imageUrl,
                contentDescription = "${pet.name}, the ${if (kitten) "kitten" else "puppy"} of the day",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (kitten) "🐱" else "🐶", fontSize = 40.sp) } },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (kitten) "🐱" else "🐶", fontSize = 40.sp) } },
            )
            Text(
                if (kitten) "🐱 Kitten" else "🐶 Puppy",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(pet.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
            pet.breed?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Text(
                pet.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(emoji: String, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 56.sp)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun FeedFooter() {
    Text(
        "You're all caught up ☀️",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(PaddingValues(24.dp)),
    )
}
