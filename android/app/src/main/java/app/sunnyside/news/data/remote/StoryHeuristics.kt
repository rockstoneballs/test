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
        Topic.Ai to "artificial intelligence|machine learning|deepmind|alphafold|\\bai\\b",
        Topic.Animals to "animal|wildlife|species|dog|pupp|cats?\\b|kitten|bird|whale|dolphin|turtle|elephant|bees?\\b|sanctuary|otter|koala|penguin",
        Topic.Environment to "climate|emission|renewable|solar|carbon|forest|trees?\\b|ocean|river|plastic|recycl|rewild|conservation|nature|biodiversity|reef",
        Topic.Health to "health|vaccine|cancer|disease|patient|hospital|doctor|medic|therapy|treatment|mental",
        Topic.Science to "scientist|research|study|discover|astronom|space|nasa|planet|telescope|fossil|dinosaur|archaeolog",
        Topic.Innovation to "invent|technology|robot|startup|engineer|3d print|battery|prototype|innovation",
        Topic.Kindness to "community|volunteer|neighbo|donat|charity|school|student|teacher|homeless|kindness|village",
        Topic.Culture to "\\bart\\b|\\barts\\b|music|film|book|museum|festival|artist|concert|theat|poet|dance",
    ).mapValues { Regex("\\b(?:${it.value})", RegexOption.IGNORE_CASE) }

    private val regionWords: Map<Region, Regex> = mapOf(
        Region.Africa to "africa|nigeria|kenya|ethiopia|ghana|uganda|tanzania|rwanda|senegal|egypt|morocco|zimbabwe|zambia|malawi|botswana|namibia|madagascar",
        Region.Asia to "asia|china|japan|india|pakistan|bangladesh|indonesia|philippines|vietnam|thailand|malaysia|singapore|korea|nepal|sri lanka|taiwan",
        Region.UkIreland to "uk|britain|british|england|scotland|scottish|wales|welsh|ireland|irish|london|manchester|birmingham|glasgow|edinburgh|cardiff|belfast|dublin|cork|galway|yorkshire|cornwall|devon",
        Region.Europe to "europe|france|germany|spain|italy|portugal|netherlands|dutch|sweden|norway|denmark|finland|poland|greece|paris",
        Region.LatinAmerica to "latin america|south america|mexico|brazil|argentina|chile|colombia|peru|ecuador|bolivia|costa rica|caribbean|cuba",
        Region.MiddleEast to "middle east|saudi|emirates|dubai|qatar|oman|jordan|lebanon|iraq|iran|turkey|yemen",
        Region.NorthAmerica to "united states|u\\.s\\.|usa|american|canada|canadian|california|texas|new york|florida|chicago|alaska|hawaii|toronto",
        Region.Oceania to "australia|new zealand|fiji|samoa|tonga|papua new guinea|great barrier reef|sydney|melbourne|auckland",
    ).mapValues { Regex("\\b(?:${it.value})\\b", RegexOption.IGNORE_CASE) }

    private val wpFooter = Regex("The post .{0,300}? appeared first on .{0,200}?\\.?$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun isHardBlocked(title: String): Boolean = hardBlock.containsMatchIn(title)

    /** Sunnyside has no sport (same rule as the scraper). */
    private val sport = Regex(
        "\\b(sport|football|soccer|olympic|paralympic|marathon|athlete|tennis|cricket|rugby|basketball|medal|champion|league|tournament|golf|boxing)",
        RegexOption.IGNORE_CASE,
    )

    fun isSport(title: String): Boolean = sport.containsMatchIn(title)

    /** Teasers, hype, listicles, questions and advice pieces (a short version of the scraper's rule). */
    private val clickbait = Regex(
        "you won'?t believe|will (make you|restore your|melt your)|\\byou(r|'re|'ll)?\\b|\\bhere'?s (why|what|how)|" +
            "\\bthis is (why|what|how)\\b|(goes|went) viral|\\bviral\\b|\\binternet (is|can'?t|goes)|" +
            "\\bhow to\\b|\\btips\\b|\\bhacks?\\b|\\bdeals?\\b|\\bincredible\\b|\\bamazing\\b|\\bshocking|" +
            "^(watch|video|photos?|quiz|opinion)\\s*[:|-]|^good news in history|^(the )?\\d+ (\\w+ ){0,2}(things|ways|reasons|photos|moments)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isClickbait(title: String): Boolean {
        val t = title.trim()
        return clickbait.containsMatchIn(t) || t.endsWith("?") || t.endsWith("…") || t.endsWith("...") || '!' in t
    }

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
