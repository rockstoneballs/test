package app.sunnyside.news.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.sunnyside.news.data.Community
import app.sunnyside.news.data.Pet
import app.sunnyside.news.data.PetKind
import app.sunnyside.news.data.PostKind
import app.sunnyside.news.data.Region
import app.sunnyside.news.data.Story

@Entity(tableName = "stories")
data class StoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val imageUrl: String?,
    val source: String,
    val publishedAt: Long,
    val category: String,
    val region: String,
    val uplift: Int,
    val kind: String = "article",
    val author: String? = null,
    val score: Int? = null,
    val comments: Int? = null,
    val discussionUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

/** Saved posts are a separate copy so they survive the feed rolling over. */
@Entity(tableName = "saved_stories")
data class SavedStoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val imageUrl: String?,
    val source: String,
    val publishedAt: Long,
    val category: String,
    val region: String,
    val uplift: Int,
    val savedAt: Long,
    val kind: String = "article",
    val author: String? = null,
    val score: Int? = null,
    val comments: Int? = null,
    val discussionUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

/** Your own up/down votes. They stay on the device. */
@Entity(tableName = "votes")
data class VoteEntity(
    @PrimaryKey val id: String,
    val value: Int,
)

@Entity(tableName = "pets", primaryKeys = ["date", "kind"])
data class PetEntity(
    val date: String,
    val kind: String,
    val imageUrl: String,
    val name: String,
    val caption: String,
    val breed: String?,
)

private fun kindName(kind: PostKind) = kind.name.lowercase()

fun StoryEntity.toStory() = Story(
    id = id, title = title, summary = summary, url = url, imageUrl = imageUrl, source = source,
    publishedAtMillis = publishedAt, community = Community.from(category), region = Region.from(region), uplift = uplift,
    kind = PostKind.from(kind), author = author, score = score, comments = comments, discussionUrl = discussionUrl,
    imageWidth = imageWidth, imageHeight = imageHeight,
)

fun SavedStoryEntity.toStory() = Story(
    id = id, title = title, summary = summary, url = url, imageUrl = imageUrl, source = source,
    publishedAtMillis = publishedAt, community = Community.from(category), region = Region.from(region), uplift = uplift,
    kind = PostKind.from(kind), author = author, score = score, comments = comments, discussionUrl = discussionUrl,
    imageWidth = imageWidth, imageHeight = imageHeight,
)

fun Story.toSavedEntity(now: Long) = SavedStoryEntity(
    id = id, title = title, summary = summary, url = url, imageUrl = imageUrl, source = source,
    publishedAt = publishedAtMillis, category = community.label, region = region.label, uplift = uplift, savedAt = now,
    kind = kindName(kind), author = author, score = score, comments = comments, discussionUrl = discussionUrl,
    imageWidth = imageWidth, imageHeight = imageHeight,
)

fun PetEntity.toPet() = Pet(
    kind = if (kind == "puppy") PetKind.Puppy else PetKind.Kitten,
    date = date,
    imageUrl = imageUrl,
    name = name,
    caption = caption,
    breed = breed,
)
