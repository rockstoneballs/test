package app.sunnyside.news.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sunnyside.news.SunnysideApp
import app.sunnyside.news.data.Settings
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Keeps the local cache fresh so the app opens instantly with today's news. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as SunnysideApp).container.newsRepository
        return runCatching { repo.refresh() }.fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
        )
    }
}

/** Fires once a day at the user's chosen time: refreshes, then posts the briefing. */
class MorningBriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as SunnysideApp).container
        if (!container.settingsRepository.current().morningBriefing) return Result.success()
        val repo = container.newsRepository
        runCatching { repo.refresh() } // Show whatever we have cached if this fails.
        val top = repo.topStory() ?: return Result.success()
        val count = repo.storiesSince(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24))
        Notifications.showMorningBriefing(applicationContext, top, count)
        return Result.success()
    }
}

object Scheduler {
    private const val REFRESH = "refresh"
    private const val MORNING = "morning-briefing"

    fun schedulePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** [reschedule] = true when the user changed the time; otherwise an existing schedule is kept. */
    fun scheduleMorningBriefing(context: Context, settings: Settings, reschedule: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!settings.morningBriefing) {
            wm.cancelUniqueWork(MORNING)
            return
        }
        val request = PeriodicWorkRequestBuilder<MorningBriefingWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntil(settings.briefingHour, settings.briefingMinute).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        val policy = if (reschedule) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(MORNING, policy, request)
    }

    internal fun delayUntil(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): Duration {
        var next = now.toLocalDate().atTime(LocalTime.of(hour, minute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
