package app.sunnyside.news.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sunnyside.news.data.Category
import app.sunnyside.news.data.Pet
import app.sunnyside.news.data.PetKind
import app.sunnyside.news.data.Story
import app.sunnyside.news.ui.theme.accent
import app.sunnyside.news.util.timeAgo
import coil.compose.SubcomposeAsyncImage

/** Story image with a soft topic-coloured placeholder while loading or when missing. */
@Composable
fun StoryImage(url: String?, category: Category, modifier: Modifier = Modifier, emojiSize: Int = 40) {
    val placeholder = @Composable {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(category.accent().copy(alpha = 0.35f), category.accent().copy(alpha = 0.12f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) { Text(category.emoji, fontSize = emojiSize.sp) }
    }
    Box(modifier) {
        if (url == null) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { placeholder() },
                error = { placeholder() },
            )
        }
    }
}

@Composable
fun CategoryLabel(category: Category, modifier: Modifier = Modifier) {
    Text(
        text = category.label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = category.accent(),
        modifier = modifier,
    )
}

@Composable
fun StoryMeta(story: Story, modifier: Modifier = Modifier) {
    Text(
        text = "${story.source} · ${timeAgo(story.publishedAtMillis)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun SaveButton(saved: Boolean, onToggle: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = if (saved) "Remove from saved" else "Save for later",
            tint = if (saved) MaterialTheme.colorScheme.primary else tint,
        )
    }
}

/** Big lead story card with image on top. */
@Composable
fun HeroStoryCard(story: Story, saved: Boolean, onClick: () -> Unit, onToggleSave: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        StoryImage(
            story.imageUrl, story.category,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f),
            emojiSize = 64,
        )
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryLabel(story.category)
                Text(
                    "  ·  ${story.region.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                story.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(end = 12.dp),
            )
            if (story.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    story.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoryMeta(story, Modifier.weight(1f))
                SaveButton(saved, onToggleSave)
            }
        }
    }
}

/** Standard list row: text on the left, square thumbnail on the right. */
@Composable
fun StoryRow(story: Story, saved: Boolean, onClick: () -> Unit, onToggleSave: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            CategoryLabel(story.category)
            Spacer(Modifier.height(4.dp))
            Text(
                story.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoryMeta(story, Modifier.weight(1f))
                SaveButton(saved, onToggleSave)
            }
        }
        Spacer(Modifier.width(12.dp))
        StoryImage(
            story.imageUrl, story.category,
            Modifier
                .padding(top = 4.dp, end = 12.dp)
                .size(92.dp)
                .clip(RoundedCornerShape(16.dp)),
            emojiSize = 32,
        )
    }
}

/** Compact card for horizontal carousels. */
@Composable
fun StoryTile(story: Story, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.width(240.dp),
    ) {
        StoryImage(
            story.imageUrl, story.category,
            Modifier
                .fillMaxWidth()
                .height(130.dp),
        )
        Column(Modifier.padding(12.dp)) {
            Text(
                "${story.region.emoji} ${story.region.label}".uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                story.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                minLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Kitten / puppy of the day card. */
@Composable
fun PetCard(pet: Pet, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (pet.kind == PetKind.Kitten) "Kitten of the day" else "Puppy of the day"
    val emoji = if (pet.kind == PetKind.Kitten) "🐱" else "🐶"
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            SubcomposeAsyncImage(
                model = pet.imageUrl,
                contentDescription = "${pet.name}, the $label",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 48.sp) }
                },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 48.sp) }
                },
            )
            Text(
                "$emoji $label",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                pet.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                pet.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                maxLines = 3,
                minLines = 3,
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
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
