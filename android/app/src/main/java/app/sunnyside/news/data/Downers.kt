package app.sunnyside.news.data

import app.sunnyside.news.data.local.AppDatabase
import app.sunnyside.news.data.local.DownerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The Downer button: one tap hides a post that isn't good news and, when a feedback form
 * service is configured, tells us about it so the filters can be tuned. Work runs in the
 * app's [scope] so it finishes even if the screen that asked for it closes.
 */
class Downers(
    private val db: AppDatabase,
    private val feedback: FeedbackSender,
    private val scope: CoroutineScope,
) {
    /** Whether marks are sent to us, or only hide the post on this phone. */
    val reported: Boolean get() = feedback.configured

    fun mark(story: Story) {
        scope.launch {
            val now = System.currentTimeMillis()
            db.downers().add(DownerEntity(story.id, now))
            db.downers().deleteBefore(now - TimeUnit.DAYS.toMillis(30))
            feedback.sendDowner(story)
        }
    }

    fun undo(id: String) {
        scope.launch { db.downers().remove(id) }
    }
}
