package app.sunnyside.news

import app.sunnyside.news.data.FeedbackDraft
import app.sunnyside.news.data.FeedbackSender
import app.sunnyside.news.data.Topic
import app.sunnyside.news.data.PostKind
import app.sunnyside.news.data.Ranking
import app.sunnyside.news.data.SortMode
import app.sunnyside.news.data.Story
import app.sunnyside.news.data.Region
import app.sunnyside.news.data.remote.PetNames
import app.sunnyside.news.data.remote.RssParser
import app.sunnyside.news.data.remote.StoryHeuristics
import app.sunnyside.news.work.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class LogicTest {
    @Test
    fun briefingIsScheduledForLaterToday() {
        val now = LocalDateTime.of(2026, 9, 26, 5, 30)
        assertEquals(Duration.ofMinutes(90), Scheduler.delayUntil(7, 0, now))
    }

    @Test
    fun briefingRollsOverToTomorrow() {
        val now = LocalDateTime.of(2026, 9, 26, 7, 0)
        assertEquals(Duration.ofHours(24), Scheduler.delayUntil(7, 0, now))
    }

    @Test
    fun parsesRssAndAtomDates() {
        assertEquals("2026-09-26T08:00:00Z", RssParser.parseDate("Sat, 26 Sep 2026 08:00:00 +0000"))
        assertEquals("2026-09-26T07:00:00Z", RssParser.parseDate("2026-09-26T08:00:00+01:00"))
        assertEquals(null, RssParser.parseDate("not a date"))
    }

    @Test
    fun guessesTopicAndRegion() {
        assertEquals(Topic.Animals, StoryHeuristics.guessCategory("Baby elephant born at Kenyan sanctuary", ""))
        assertEquals(Region.Africa, StoryHeuristics.guessRegion("Baby elephant born in Kenya", ""))
        assertEquals(Region.Global, StoryHeuristics.guessRegion("Scientists find a new way to recycle plastic", ""))
        assertEquals(Region.UkIreland, StoryHeuristics.guessRegion("Otters return to Scottish rivers", ""))
        assertEquals(Region.UkIreland, Region.from("UK & Ireland"))
    }

    @Test
    fun skipsSport() {
        assertTrue(StoryHeuristics.isSport("Underdog football team wins the league"))
        assertFalse(StoryHeuristics.isSport("Volunteers plant a million trees"))
    }

    @Test
    fun blocksDarkHeadlines() {
        assertTrue(StoryHeuristics.isHardBlocked("Two killed in crash"))
        assertFalse(StoryHeuristics.isHardBlocked("Volunteers plant a million trees"))
    }

    @Test
    fun homeStoriesGetANudge() {
        val uk = post("uk", hoursAgo = 2).copy(region = Region.UkIreland)
        val us = post("us", hoursAgo = 2).copy(region = Region.NorthAmerica)
        assertEquals(listOf("uk", "us"), Ranking.sort(listOf(us, uk), SortMode.Hot, NOW).map { it.id })
    }

    @Test
    fun feedbackReportsCarryTheStoryAndFallBackToGitHub() {
        val sender = FeedbackSender(OkHttpClient(), Json, endpoint = "", repo = "owner/repo", appVersion = "0.4.0", androidVersion = "15")
        val story = post("otters", hoursAgo = 1).copy(title = "Otters return")
        val payload = sender.payload(FeedbackDraft("clickbait", "It's clickbait", "  ", "", story))
        assertEquals("report", payload.kind)
        assertEquals("clickbait", payload.reason)
        assertEquals("Report: It's clickbait — Otters return", payload.subject)
        assertEquals(null, payload.email)
        assertEquals("otters", payload.story?.id)
        val url = sender.githubIssueUrl(payload)
        assertTrue(url.startsWith("https://github.com/owner/repo/issues/new?title=Report%3A+It%27s+clickbait"))
        assertTrue("Sunnyside+app+0.4.0" in url)
        val general = sender.payload(FeedbackDraft("idea", "An idea or suggestion", "More UK news", "me@example.com"))
        assertEquals("idea", general.kind)
        assertEquals("Feedback: An idea or suggestion", general.subject)
        assertEquals("me@example.com", general.email)
    }

    @Test
    fun skipsClickbait() {
        assertTrue(StoryHeuristics.isClickbait("This Dad's Reaction Will Melt Your Heart"))
        assertTrue(StoryHeuristics.isClickbait("10 Things That Made Us Smile This Week"))
        assertTrue(StoryHeuristics.isClickbait("Could this new battery change everything?"))
        assertFalse(StoryHeuristics.isClickbait("400 volunteers plant 10,000 trees in Devon"))
        assertFalse(StoryHeuristics.isClickbait("Rare white kiwi hatches at Pukaha"))
    }

    @Test
    fun storyBodySplitsIntoParagraphs() {
        val story = post("a", hoursAgo = 1).copy(body = "First paragraph.\n\nSecond paragraph.\n \nThird.")
        assertEquals(listOf("First paragraph.", "Second paragraph.", "Third."), story.paragraphs)
        assertEquals(emptyList<String>(), post("b", hoursAgo = 1).paragraphs)
    }

    @Test
    fun petNamesAreStablePerDay() {
        val day = LocalDate.of(2026, 9, 26)
        assertEquals(PetNames.pick(PetNames.kittenNames, day, 1), PetNames.pick(PetNames.kittenNames, day, 1))
    }

    @Test
    fun newsLeadsAndSocialIsSprinkledIn() {
        val cats = (1..5).map { post("cat$it", hoursAgo = 1, score = 5000, kind = PostKind.Image) }
        val news = (1..6).map { post("news$it", hoursAgo = it) }
        val sorted = Ranking.sort(cats + news, SortMode.Hot, NOW).map { it.id }
        assertEquals(
            listOf("news1", "news2", "news3", "cat1", "news4", "news5", "news6", "cat2", "cat3", "cat4", "cat5"),
            sorted,
        )
    }

    private fun post(id: String, hoursAgo: Int, score: Int? = null, uplift: Int = 7, kind: PostKind = PostKind.Article) = Story(
        id = id, title = id, summary = "", url = "https://x/$id", imageUrl = null, source = "s",
        publishedAtMillis = NOW - hoursAgo * 3_600_000L, topic = Topic.Aww, region = Region.Global,
        uplift = uplift, score = score, kind = kind,
    )

    @Test
    fun topStoriesBalancePopularityAndFreshness() {
        val fresh = post("fresh", hoursAgo = 1)
        val popularButOld = post("old", hoursAgo = 40, score = 50_000)
        val popularAndRecent = post("hit", hoursAgo = 3, score = 20_000)
        val sorted = Ranking.sort(listOf(popularButOld, fresh, popularAndRecent), SortMode.Hot, NOW)
        assertEquals(listOf("hit", "fresh", "old"), sorted.map { it.id })
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
