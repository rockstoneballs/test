package app.sunnyside.news.data

import android.content.Context
import app.sunnyside.news.data.local.AppDatabase
import app.sunnyside.news.data.local.StoryEntity
import app.sunnyside.news.data.local.toSavedEntity
import app.sunnyside.news.data.local.toStory
import app.sunnyside.news.data.remote.DirectSources
import app.sunnyside.news.data.remote.FeedApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class NewsRepository(
    context: Context,
    private val db: AppDatabase,
    private val feedApi: FeedApi,
    private val direct: DirectSources,
) {
    private val prefs = context.getSharedPreferences("feed_state", Context.MODE_PRIVATE)
    private val _feedUpdatedAt = MutableStateFlow(prefs.getLong(KEY_GENERATED, 0L).takeIf { it > 0 })

    /** When the scraper last published the feed we're showing (null if unknown). */
    val feedUpdatedAt: StateFlow<Long?> = _feedUpdatedAt.asStateFlow()

    /** When this device last refreshed successfully. */
    val lastRefreshAt: Long get() = prefs.getLong(KEY_REFRESHED, 0L)

    val stories: Flow<List<Story>> = db.stories().observeAll().map { list -> list.map { it.toStory() } }

    val savedStories: Flow<List<Story>> = db.saved().observeAll().map { list -> list.map { it.toStory() } }

    val savedIds: Flow<Set<String>> = db.saved().observeIds().map { it.toSet() }


    /** A post from the live feed, or from the saved list if it has rolled off the feed. */
    fun story(id: String): Flow<Story?> =
        combine(db.stories().observe(id), db.saved().observe(id)) { live, saved ->
            live?.toStory() ?: saved?.toStory()
        }

    suspend fun toggleSaved(story: Story, currentlySaved: Boolean) {
        if (currentlySaved) db.saved().remove(story.id) else db.saved().save(story.toSavedEntity(System.currentTimeMillis()))
    }

    /**
     * Refreshes from the published feed, falling back to reading good-news RSS
     * feeds directly if the feed is unreachable. Throws only if both fail.
     */
    suspend fun refresh(): RefreshSource {
        val today = LocalDate.now(ZoneOffset.UTC)
        val feed = runCatching { feedApi.fetch() }
        val source: RefreshSource
        val stories: List<StoryDto>

        if (feed.isSuccess && feed.getOrThrow().stories.isNotEmpty()) {
            source = RefreshSource.Feed
            stories = feed.getOrThrow().stories
            feed.getOrThrow().generatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let {
                prefs.edit().putLong(KEY_GENERATED, it).apply()
                _feedUpdatedAt.value = it
            }
        } else {
            val news = direct.fetchStories()
            if (news.isEmpty()) throw feed.exceptionOrNull() ?: IllegalStateException("No stories available")
            stories = news + direct.fetchPetPosts()
            source = RefreshSource.Direct
            _feedUpdatedAt.value = System.currentTimeMillis()
        }

        db.stories().replaceAll(stories.mapNotNull { it.toEntity() })

        // Kitten and Puppy of the Day were retired (cat and dog photos are feed posts now).
        db.pets().deleteBefore(today.plusDays(1).toString())
        prefs.edit().putLong(KEY_REFRESHED, System.currentTimeMillis()).apply()
        return source
    }

    suspend fun topStory(): Story? = db.stories().topStory()?.toStory()

    suspend fun storiesSince(millis: Long): Int = db.stories().countSince(millis)

    private fun StoryDto.toEntity(): StoryEntity? {
        val published = runCatching { Instant.parse(publishedAt).toEpochMilli() }.getOrNull() ?: return null
        return StoryEntity(
            id = id,
            title = title,
            summary = summary,
            url = url,
            imageUrl = imageUrl,
            source = source,
            publishedAt = published,
            category = Topic.from(community ?: category).key,
            region = Region.from(region).label,
            uplift = uplift,
            kind = PostKind.from(kind).name.lowercase(),
            author = author,
            score = score,
            comments = comments,
            discussionUrl = discussionUrl,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            videoUrl = videoUrl?.takeIf { it.startsWith("https://") },
            body = body,
            checkedBy = checkedBy,
        )
    }

    private companion object {
        const val KEY_GENERATED = "generated_at"
        const val KEY_REFRESHED = "refreshed_at"
    }
}

enum class RefreshSource { Feed, Direct }
