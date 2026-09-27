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
    val category: String = Topic.Kindness.key,
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
    val topic: Topic,
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

    /** Where it came from in plain words: "Good News Network", "Reddit", "Mastodon"… */
    val platform: String
        get() = when {
            source.startsWith("r/") -> "Reddit"
            source.startsWith("Lemmy") -> "Lemmy"
            source.startsWith("#") -> "Mastodon"
            else -> source
        }

    /** "u/someone" for Reddit/Lemmy/Mastodon posts; null for articles. */
    val poster: String? get() = author?.takeIf { platform != source && it != source }
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

/**
 * Topic of a post, shown as a small flair tag like Reddit's post flair.
 * [key] is the value in the feed's "community" field.
 */
enum class Topic(val key: String, val label: String, val emoji: String, val color: Long) {
    Memes("WholesomeMemes", "Meme", "😂", 0xFFF2A516),
    Aww("Aww", "Cute", "🥹", 0xFFE86A92),
    Smiles("MadeMeSmile", "Wholesome", "😊", 0xFFF28C28),
    Science("Science", "Science", "🔭", 0xFF5B6CD9),
    Environment("Environment", "Environment", "🌿", 0xFF2E9D5B),
    Health("Health", "Health", "💚", 0xFF0F9D8F),
    Animals("Animals", "Animals", "🐾", 0xFFE07A1F),
    Kindness("Community", "Kindness", "🤝", 0xFFD9477A),
    Innovation("Innovation", "Innovation", "💡", 0xFF8A56D6),
    Culture("Culture", "Culture", "🎨", 0xFFC9533A),
    Sport("Sport", "Sport", "🏅", 0xFF2C88C9);

    companion object {
        fun from(key: String?): Topic = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Kindness
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

    /** Hot and Top show this many news stories for every meme / cute-animal post. */
    const val NEWS_PER_SOCIAL = 3

    fun sort(stories: List<Story>, mode: SortMode, votes: Map<String, Int>, now: Long = System.currentTimeMillis()): List<Story> {
        if (mode == SortMode.New) return stories.sortedByDescending { it.publishedAtMillis }
        val comparator: Comparator<Story> = when (mode) {
            // News has no upvotes of its own, so it ranks by uplift, then freshness.
            SortMode.Top -> compareByDescending<Story> { points(it, votes[it.id] ?: 0) }
                .thenByDescending { it.uplift }
                .thenByDescending { it.publishedAtMillis }
            else -> compareByDescending { hot(it, votes[it.id] ?: 0, now) }
        }
        val (news, social) = stories.partition { it.kind == PostKind.Article }
        return blend(news.sortedWith(comparator), social.sortedWith(comparator))
    }

    /** News leads the feed; the fun stuff is sprinkled through it. */
    fun blend(news: List<Story>, social: List<Story>): List<Story> {
        val out = ArrayList<Story>(news.size + social.size)
        var n = 0
        var s = 0
        while (n < news.size || s < social.size) {
            repeat(NEWS_PER_SOCIAL) { if (n < news.size) out += news[n++] }
            if (s < social.size) out += social[s++]
        }
        return out
    }
}
