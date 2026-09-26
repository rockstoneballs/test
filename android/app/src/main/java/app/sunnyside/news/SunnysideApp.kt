package app.sunnyside.news

import android.app.Application
import app.sunnyside.news.work.Notifications
import app.sunnyside.news.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SunnysideApp : Application() {
    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        appScope.launch {
            val settings = container.settingsRepository.current()
            Scheduler.schedulePeriodicRefresh(this@SunnysideApp)
            Scheduler.scheduleMorningBriefing(this@SunnysideApp, settings)
        }
    }
}
