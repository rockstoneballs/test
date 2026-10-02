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
    val videoUrl: String? = null,
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
    /** The opening paragraphs of the article, separated by blank lines. */
    val body: String = "",
    /** "claude" when the summary was written by Claude, "keywords" otherwise. */
    val checkedBy: String? = null,
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
    /** A directly playable MP4 for video posts. */
    val videoUrl: String? = null,
    /** The opening paragraphs of the article, separated by blank lines ("" if none). */
    val body: String = "",
    /** The summary was written by Claude from the article (rather than taken from the feed). */
    val writtenSummary: Boolean = false,
) {
    val paragraphs: List<String>
        get() = body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Width / height of the image, clamped to something that fits on a phone screen. */
    val aspectRatio: Float?
        get() = if (imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0) {
            (imageWidth.toFloat() / imageHeight).coerceIn(0.6f, 2f)
        } else {
            null
        }

    /** Where it came from in plain words: "Good News Network", "Reddit", "9GAG"… */
    val platform: String
        get() = when {
            source.startsWith("r/") -> "Reddit"
            source.startsWith("Lemmy") -> "Lemmy"
            source.startsWith("9GAG") -> "9GAG"
            source.startsWith("Imgur") -> "Imgur"
            else -> source
        }

    /** "u/someone" for Reddit/Lemmy/Imgur posts; null for articles. */
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
    Ai("AI", "AI for good", "🤖", 0xFF2F80ED),
    Pets("Pets", "Cats & dogs", "😺", 0xFFB7791F),
    Culture("Culture", "Culture", "🎨", 0xFFC9533A);

    companion object {
        fun from(key: String?): Topic = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Kindness
    }
}

enum class Region(val label: String, val emoji: String) {
    UkIreland("UK & Ireland", "🌍"),
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

enum class SortMode(val label: String, val emoji: String) { Hot("Top stories", "⭐"), New("Latest", "🕒") }

enum class ViewMode { Card, Compact }

/** "Top stories" ranking, shared with the website (web/app.js): uplift, decaying with age. */
object Ranking {
    fun hot(story: Story, now: Long = System.currentTimeMillis()): Double {
        val ageHours = (now - story.publishedAtMillis) / 3_600_000.0
        // For memes and animal photos, popularity on the source site picks the best ones (never shown).
        val popular = min(3.0, 0.75 * log10(1.0 + max(story.score ?: 0, 0)))
        // Most readers are in the UK and Ireland: their stories get a small nudge up.
        val home = if (story.region == Region.UkIreland) HOME_BONUS else 0.0
        return story.uplift + popular + (if (story.imageUrl != null) 0.5 else 0.0) + home - ageHours / 6
    }

    const val HOME_BONUS = 1.0

    /** "Top stories" shows this many news stories for every meme / cute-animal post. */
    const val NEWS_PER_SOCIAL = 3

    /** Random cat and dog photos aren't ranked: one follows every this many posts. */
    const val POSTS_PER_PET = 6

    fun sort(stories: List<Story>, mode: SortMode, now: Long = System.currentTimeMillis()): List<Story> {
        val (petPhotos, rest) = stories.partition { it.topic == Topic.Pets }
        val pets = petPhotos.sortedByDescending { it.publishedAtMillis }
        if (mode == SortMode.New) return intersperse(rest.sortedByDescending { it.publishedAtMillis }, pets)
        val comparator = compareByDescending<Story> { hot(it, now) }
        val (news, social) = rest.partition { it.kind == PostKind.Article }
        return intersperse(blend(news.sortedWith(comparator), social.sortedWith(comparator)), pets)
    }

    /** A cat or dog photo after every [POSTS_PER_PET] posts (same as the website). */
    fun intersperse(posts: List<Story>, pets: List<Story>): List<Story> {
        if (posts.isEmpty()) return pets
        val out = ArrayList<Story>(posts.size + pets.size)
        var k = 0
        posts.forEachIndexed { i, post ->
            out += post
            if ((i + 1) % POSTS_PER_PET == 0 && k < pets.size) out += pets[k++]
        }
        return out
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
