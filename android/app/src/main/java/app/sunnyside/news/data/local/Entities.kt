package app.sunnyside.news.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.sunnyside.news.data.Category
import app.sunnyside.news.data.Pet
import app.sunnyside.news.data.PetKind
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
)

/** Saved stories are a separate copy so they survive the feed rolling over. */
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

fun StoryEntity.toStory() = Story(
    id, title, summary, url, imageUrl, source, publishedAt, Category.from(category), Region.from(region), uplift,
)

fun SavedStoryEntity.toStory() = Story(
    id, title, summary, url, imageUrl, source, publishedAt, Category.from(category), Region.from(region), uplift,
)

fun Story.toSavedEntity(now: Long) = SavedStoryEntity(
    id, title, summary, url, imageUrl, source, publishedAtMillis, category.label, region.label, uplift, now,
)

fun PetEntity.toPet() = Pet(
    kind = if (kind == "puppy") PetKind.Puppy else PetKind.Kitten,
    date = date,
    imageUrl = imageUrl,
    name = name,
    caption = caption,
    breed = breed,
)
