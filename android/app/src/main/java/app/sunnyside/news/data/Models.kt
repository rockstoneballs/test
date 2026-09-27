package app.sunnyside.news.data

import kotlinx.serialization.Serializable
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** The feed published by the scraper (see scraper/goodnews/scrape.py). */
@Serializable
data class FeedDto(
    val version: Int = 1,
    val generatedAt: String? = null,
    val stories: List<StoryDto> = emptyList(),
    val pets: List<PetDto> = emptyList(),
)

@Serializable
data class StoryDto(
    val id: String,
    val title: String,
    val summary: String = "",
    val url: String,
    val imageUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val source: String,
    val sourceHomepage: String? = null,
    val author: String? = null,
    val publishedAt: String,
    val kind: String = "article",
    val community: String? = null,
    val category: String = Community.Community.label,
    val region: String = Region.Global.label,
    val uplift: Int = 5,
    val score: Int? = null,
    val comments: Int? = null,
    val discussionUrl: String? = null,
)

@Serializable
data class PetDto(
    val kind: String,
    val date: String,
    val imageUrl: String,
    val name: String,
    val caption: String,
    val breed: String? = null,
)

enum class PostKind { Article, Image, Video;
    companion object {
        fun from(value: String?): PostKind = when (value) {
            "image" -> Image
            "video" -> Video
            else -> Article
        }
    }
}

/** A post as the UI sees it: a news story, a meme, or a cute photo. */
data class Story(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val imageUrl: String?,
    val source: String,
    val publishedAtMillis: Long,
    val community: Community,
    val region: Region,
    val uplift: Int,
    val kind: PostKind = PostKind.Article,
    val author: String? = null,
    val score: Int? = null,
    val comments: Int? = null,
    val discussionUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
) {
    /** Width / height of the image, clamped to something that fits on a phone screen. */
    val aspectRatio: Float?
        get() = if (imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0) {
            (imageWidth.toFloat() / imageHeight).coerceIn(0.6f, 2f)
        } else {
            null
        }

    val byline: String get() = if (author != null && author != source) "$author · $source" else source
}

enum class PetKind { Kitten, Puppy }

data class Pet(
    val kind: PetKind,
    val date: String,
    val imageUrl: String,
    val name: String,
    val caption: String,
    val breed: String?,
)

/** Communities are shown as s/<label>. News topics plus the social communities. */
enum class Community(val label: String, val emoji: String, val color: Long, val about: String) {
    WholesomeMemes("WholesomeMemes", "😂", 0xFFF2A516, "Memes that make you feel good about the world."),
    Aww("Aww", "🥹", 0xFFE86A92, "Cute animals. That's it. That's the community."),
    MadeMeSmile("MadeMeSmile", "😊", 0xFFF28C28, "Small moments of pure joy and people being lovely."),
    Science("Science", "🔭", 0xFF5B6CD9, "Discoveries, space and the wonders of research."),
    Environment("Environment", "🌿", 0xFF2E9D5B, "Climate wins, rewilding and a greener planet."),
    Health("Health", "💚", 0xFF0F9D8F, "Medical breakthroughs and healthier lives."),
    Animals("Animals", "🐾", 0xFFE07A1F, "Wildlife comebacks and animal news."),
    Community("Community", "🤝", 0xFFD9477A, "Kindness, neighbours and people helping people."),
    Innovation("Innovation", "💡", 0xFF8A56D6, "Clever ideas making life better."),
    Culture("Culture", "🎨", 0xFFC9533A, "Art, music, books and joy."),
    Sport("Sport", "🏅", 0xFF2C88C9, "Triumphs, comebacks and good sportsmanship.");

    companion object {
        fun from(label: String?): Community =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: Community.Community
    }
}

enum class Region(val label: String, val emoji: String) {
    Africa("Africa", "🌍"),
    Asia("Asia", "🌏"),
    Europe("Europe", "🌍"),
    LatinAmerica("Latin America", "🌎"),
    MiddleEast("Middle East", "🌍"),
    NorthAmerica("North America", "🌎"),
    Oceania("Oceania", "🌏"),
    Global("Global", "🌐");

    companion object {
        fun from(label: String): Region = entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: Global
    }
}

enum class SortMode(val label: String, val emoji: String) { Hot("Hot", "🔥"), New("New", "✨"), Top("Top", "🏆") }

enum class ViewMode { Card, Compact }

/** Ranking shared with the website (web/app.js): uplift + votes, decaying with age. */
object Ranking {
    fun points(story: Story, myVote: Int): Int = (story.score ?: 0) + myVote

    fun hot(story: Story, myVote: Int, now: Long = System.currentTimeMillis()): Double {
        val ageHours = (now - story.publishedAtMillis) / 3_600_000.0
        val votes = max(points(story, myVote), 0)
        // Votes help, but are capped so news (which has no Reddit votes) isn't buried under memes.
        val social = min(3.0, 0.75 * log10(1.0 + votes))
        return story.uplift + social + (if (story.imageUrl != null) 0.5 else 0.0) + 2 * myVote - ageHours / 6
    }

    fun sort(stories: List<Story>, mode: SortMode, votes: Map<String, Int>, now: Long = System.currentTimeMillis()): List<Story> =
        when (mode) {
            SortMode.New -> stories.sortedByDescending { it.publishedAtMillis }
            SortMode.Top -> stories.sortedWith(
                compareByDescending<Story> { points(it, votes[it.id] ?: 0) }.thenByDescending { it.uplift },
            )
            SortMode.Hot -> stories.sortedByDescending { hot(it, votes[it.id] ?: 0, now) }
        }
}
