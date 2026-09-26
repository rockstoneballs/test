package app.sunnyside.news.data

import kotlinx.serialization.Serializable

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
    val source: String,
    val sourceHomepage: String? = null,
    val publishedAt: String,
    val category: String = Category.Community.label,
    val region: String = Region.Global.label,
    val uplift: Int = 5,
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

/** A story as the UI sees it. */
data class Story(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val imageUrl: String?,
    val source: String,
    val publishedAtMillis: Long,
    val category: Category,
    val region: Region,
    val uplift: Int,
)

enum class PetKind { Kitten, Puppy }

data class Pet(
    val kind: PetKind,
    val date: String,
    val imageUrl: String,
    val name: String,
    val caption: String,
    val breed: String?,
)

enum class Category(val label: String, val emoji: String) {
    Science("Science", "🔭"),
    Environment("Environment", "🌿"),
    Health("Health", "💚"),
    Animals("Animals", "🐾"),
    Community("Community", "🤝"),
    Innovation("Innovation", "💡"),
    Culture("Culture", "🎨"),
    Sport("Sport", "🏅");

    companion object {
        fun from(label: String): Category = entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: Community
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
