package app.sunnyside.news

import app.sunnyside.news.data.Category
import app.sunnyside.news.data.Region
import app.sunnyside.news.data.remote.PetNames
import app.sunnyside.news.data.remote.RssParser
import app.sunnyside.news.data.remote.StoryHeuristics
import app.sunnyside.news.work.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(Category.Animals, StoryHeuristics.guessCategory("Baby elephant born at Kenyan sanctuary", ""))
        assertEquals(Region.Africa, StoryHeuristics.guessRegion("Baby elephant born in Kenya", ""))
        assertEquals(Region.Global, StoryHeuristics.guessRegion("Scientists find a new way to recycle plastic", ""))
    }

    @Test
    fun blocksDarkHeadlines() {
        assertTrue(StoryHeuristics.isHardBlocked("Two killed in crash"))
        assertFalse(StoryHeuristics.isHardBlocked("Volunteers plant a million trees"))
    }

    @Test
    fun petNamesAreStablePerDay() {
        val day = LocalDate.of(2026, 9, 26)
        assertEquals(PetNames.pick(PetNames.kittenNames, day, 1), PetNames.pick(PetNames.kittenNames, day, 1))
    }
}
