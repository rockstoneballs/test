package app.sunnyside.news

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import app.sunnyside.news.work.Notifications
import app.sunnyside.news.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SunnysideApp : Application(), ImageLoaderFactory {
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

    /** Animated GIFs are common in memes, so teach Coil to play them. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
