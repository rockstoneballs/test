package app.sunnyside.news.data.remote

import app.sunnyside.news.data.Topic
import app.sunnyside.news.data.Region
import androidx.core.text.HtmlCompat

/**
 * A small on-device port of the scraper's keyword rules, used only by the
 * direct-RSS fallback. The published feed is already filtered and tagged.
 */
object StoryHeuristics {
    private val hardBlock = Regex(
        "\\b(killed|killing|murder|massacre|rape|suicide|terror|shooting|stabbing|genocide|bombing|death toll|fatal|hostage)",
        RegexOption.IGNORE_CASE,
    )

    private val categoryWords: Map<Topic, Regex> = mapOf(
        Topic.Animals to "animal|wildlife|species|dog|pupp|cats?\\b|kitten|bird|whale|dolphin|turtle|elephant|bees?\\b|sanctuary|otter|koala|penguin",
        Topic.Environment to "climate|emission|renewable|solar|carbon|forest|trees?\\b|ocean|river|plastic|recycl|rewild|conservation|nature|biodiversity|reef",
        Topic.Health to "health|vaccine|cancer|disease|patient|hospital|doctor|medic|therapy|treatment|mental",
        Topic.Science to "scientist|research|study|discover|astronom|space|nasa|planet|telescope|fossil|dinosaur|archaeolog",
        Topic.Innovation to "invent|technology|robot|startup|engineer|3d print|battery|prototype|innovation",
        Topic.Kindness to "community|volunteer|neighbo|donat|charity|school|student|teacher|homeless|kindness|village",
        Topic.Culture to "\\bart\\b|\\barts\\b|music|film|book|museum|festival|artist|concert|theat|poet|dance",
        Topic.Sport to "sport|football|soccer|olympic|paralympic|marathon|athlete|tennis|cricket|rugby|medal|champion",
    ).mapValues { Regex("\\b(?:${it.value})", RegexOption.IGNORE_CASE) }

    private val regionWords: Map<Region, Regex> = mapOf(
        Region.Africa to "africa|nigeria|kenya|ethiopia|ghana|uganda|tanzania|rwanda|senegal|egypt|morocco|zimbabwe|zambia|malawi|botswana|namibia|madagascar",
        Region.Asia to "asia|china|japan|india|pakistan|bangladesh|indonesia|philippines|vietnam|thailand|malaysia|singapore|korea|nepal|sri lanka|taiwan",
        Region.Europe to "europe|uk|britain|british|england|scotland|wales|ireland|france|germany|spain|italy|portugal|netherlands|dutch|sweden|norway|denmark|finland|poland|greece|london|paris",
        Region.LatinAmerica to "latin america|south america|mexico|brazil|argentina|chile|colombia|peru|ecuador|bolivia|costa rica|caribbean|cuba",
        Region.MiddleEast to "middle east|saudi|emirates|dubai|qatar|oman|jordan|lebanon|iraq|iran|turkey|yemen",
        Region.NorthAmerica to "united states|u\\.s\\.|usa|american|canada|canadian|california|texas|new york|florida|chicago|alaska|hawaii|toronto",
        Region.Oceania to "australia|new zealand|fiji|samoa|tonga|papua new guinea|great barrier reef|sydney|melbourne|auckland",
    ).mapValues { Regex("\\b(?:${it.value})\\b", RegexOption.IGNORE_CASE) }

    private val wpFooter = Regex("The post .{0,300}? appeared first on .{0,200}?\\.?$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun isHardBlocked(title: String): Boolean = hardBlock.containsMatchIn(title)

    fun cleanText(raw: String): String =
        HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace('￼', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    fun cleanSummary(raw: String, limit: Int = 400): String {
        val text = cleanText(raw).replace(wpFooter, "").trim()
        if (text.length <= limit) return text
        return text.take(limit).substringBeforeLast(' ').trimEnd(',', ';', ':', '-') + "…"
    }

    fun guessCategory(title: String, summary: String): Topic =
        best(categoryWords, title, summary) ?: Topic.Kindness

    fun guessRegion(title: String, summary: String): Region =
        best(regionWords, title, summary) ?: Region.Global

    private fun <K> best(rules: Map<K, Regex>, title: String, summary: String): K? =
        rules.mapValues { (_, rx) -> 2 * rx.findAll(title).count() + rx.findAll(summary).count() }
            .filterValues { it > 0 }
            .maxByOrNull { it.value }
            ?.key
}
