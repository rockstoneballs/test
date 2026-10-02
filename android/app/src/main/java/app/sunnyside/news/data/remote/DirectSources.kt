package app.sunnyside.news.data.remote

import android.util.Xml
import app.sunnyside.news.data.StoryDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Fallback used when the published feed can't be reached (e.g. before the
 * scraper workflow has been deployed): reads a few dedicated good-news outlets'
 * RSS directly on the device, and fetches a few cat and dog photos from their APIs.
 */
class DirectSources(private val client: OkHttpClient, private val json: Json) {

    private data class Outlet(val name: String, val url: String, val homepage: String)

    private val outlets = listOf(
        Outlet("Good News Network", "https://www.goodnewsnetwork.org/feed/", "https://www.goodnewsnetwork.org"),
        Outlet("Positive News", "https://www.positive.news/feed/", "https://www.positive.news"),
        Outlet("Reasons to be Cheerful", "https://reasonstobecheerful.world/feed/", "https://reasonstobecheerful.world"),
        Outlet("The Optimist Daily", "https://www.optimistdaily.com/feed/", "https://www.optimistdaily.com"),
    )

    suspend fun fetchStories(): List<StoryDto> = coroutineScope {
        outlets.map { outlet ->
            async(Dispatchers.IO) { runCatching { fetchOutlet(outlet) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().sortedByDescending { it.publishedAt }
    }

    private fun fetchOutlet(outlet: Outlet): List<StoryDto> {
        val request = Request.Builder().url(outlet.url).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val stream = response.body?.byteStream() ?: return emptyList()
            RssParser.parse(stream).mapNotNull { item ->
                if (item.link.isNullOrBlank() || item.title.isNullOrBlank()) return@mapNotNull null
                if (StoryHeuristics.isHardBlocked(item.title) || StoryHeuristics.isSport(item.title) ||
                    StoryHeuristics.isClickbait(item.title)
                ) {
                    return@mapNotNull null
                }
                val summary = StoryHeuristics.cleanSummary(item.description.orEmpty())
                StoryDto(
                    id = sha1(item.link).take(16),
                    title = StoryHeuristics.cleanText(item.title),
                    summary = summary,
                    url = item.link,
                    imageUrl = item.imageUrl,
                    source = outlet.name,
                    sourceHomepage = outlet.homepage,
                    publishedAt = item.published ?: Instant.now().toString(),
                    community = StoryHeuristics.guessCategory(item.title, summary).key,
                    region = StoryHeuristics.guessRegion(item.title, summary).label,
                )
            }
        }
    }

    /** A few random cat and dog photos as posts, for when the published feed can't be reached. */
    suspend fun fetchPetPosts(): List<StoryDto> = withContext(Dispatchers.IO) {
        val cats = runCatching {
            getJson("https://api.thecatapi.com/v1/images/search?mime_types=jpg,png&limit=3").jsonArray
                .map { it.jsonObject["url"]!!.jsonPrimitive.content }
        }.getOrDefault(emptyList())
        val dogs = runCatching {
            getJson("https://dog.ceo/api/breeds/image/random/3").jsonObject["message"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
        val now = Instant.now().toString()
        cats.map { petPost(it, cat = true, now) } + dogs.map { petPost(it, cat = false, now) }
    }

    private fun petPost(imageUrl: String, cat: Boolean, now: String): StoryDto {
        val breed = if (cat) null else imageUrl.substringAfter("/breeds/", "").substringBefore("/").takeIf { it.isNotBlank() }
            ?.split("-")?.reversed()?.joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        val name = PetNames.pick(if (cat) PetNames.kittenNames else PetNames.puppyNames, imageUrl, 1)
        val emoji = if (cat) "🐱" else "🐶"
        return StoryDto(
            id = sha1(imageUrl).take(16),
            title = if (breed != null) "Meet $name, ${if (breed.first().lowercaseChar() in "aeiou") "an" else "a"} $breed $emoji" else "Meet $name $emoji",
            summary = PetNames.pick(if (cat) PetNames.kittenCaptions else PetNames.puppyCaptions, imageUrl, 2),
            url = imageUrl,
            imageUrl = imageUrl,
            source = if (cat) "The Cat API" else "Dog CEO",
            publishedAt = now,
            kind = "image",
            community = "Pets",
        )
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Minimal RSS 2.0 / Atom reader built on the platform XmlPullParser. */
object RssParser {
    data class Item(
        val title: String?,
        val link: String?,
        val description: String?,
        val published: String?,
        val imageUrl: String?,
    )

    private val imgSrc = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    fun parse(input: InputStream): List<Item> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val items = mutableListOf<Item>()
        var inItem = false
        var title: String? = null
        var link: String? = null
        var description: String? = null
        var content: String? = null
        var published: String? = null
        var image: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "item" || name == "entry" -> {
                        inItem = true
                        title = null; link = null; description = null; content = null; published = null; image = null
                    }
                    !inItem -> Unit
                    name == "title" -> title = parser.nextText()
                    name == "link" -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null) {
                            val rel = parser.getAttributeValue(null, "rel")
                            if (rel == null || rel == "alternate") link = href
                        } else {
                            link = parser.nextText().trim()
                        }
                    }
                    name == "description" || name == "summary" -> description = parser.nextText()
                    name == "content:encoded" || name == "content" -> content = parser.nextText()
                    name == "pubDate" || name == "published" || name == "updated" || name == "dc:date" ->
                        if (published == null) published = parseDate(parser.nextText())
                    name == "media:content" || name == "media:thumbnail" -> {
                        val medium = parser.getAttributeValue(null, "medium")
                        if (image == null && (medium == null || medium == "image")) image = parser.getAttributeValue(null, "url")
                    }
                    name == "enclosure" -> {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        if (image == null && type.startsWith("image")) image = parser.getAttributeValue(null, "url")
                    }
                }
                XmlPullParser.END_TAG -> if (name == "item" || name == "entry") {
                    inItem = false
                    val img = image ?: imgSrc.find(content.orEmpty())?.groupValues?.get(1)
                        ?: imgSrc.find(description.orEmpty())?.groupValues?.get(1)
                    items += Item(
                        title = title,
                        link = link,
                        description = description ?: content,
                        published = published,
                        imageUrl = img?.takeIf { it.startsWith("http") },
                    )
                }
            }
            event = parser.next()
        }
        return items
    }

    /** Normalises RFC 822 (RSS) and ISO 8601 (Atom) dates to ISO instants. */
    fun parseDate(raw: String): String? {
        val text = raw.trim()
        return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().toString() }
            .recoverCatching { Instant.parse(text).toString() }
            .getOrNull()
    }
}

object PetNames {
    val kittenNames = listOf(
        "Biscuit", "Mochi", "Pumpkin", "Pickles", "Waffles", "Noodle", "Clementine", "Pepper",
        "Marshmallow", "Toffee", "Ziggy", "Olive", "Button", "Luna", "Miso", "Pudding",
    )
    val puppyNames = listOf(
        "Barnaby", "Pretzel", "Maple", "Rolo", "Bear", "Hazel", "Scout", "Nugget",
        "Daisy", "Otis", "Peanut", "Pancake", "Rosie", "Gus", "Honey", "Teddy",
    )
    val kittenCaptions = listOf(
        "Has officially approved your plans for today.",
        "Professional napper, part-time sunbeam inspector.",
        "Currently accepting chin scratches. No appointment needed.",
        "Sends you one slow blink, which in cat means 'I love you'.",
        "Tiny paws, enormous confidence.",
    )
    val puppyCaptions = listOf(
        "Thinks you're doing an amazing job. Genuinely.",
        "Has been a very good dog today. Possibly the best.",
        "Believes every day is the best day ever, and might be right.",
        "Tail currently set to maximum wag.",
        "Heard you were having a day, brought you this face.",
    )

    /** The same photo always gets the same name and caption. */
    fun pick(options: List<String>, key: String, salt: Int): String =
        options[Math.floorMod(key.hashCode() * 31 + salt * 17, options.size)]
}
