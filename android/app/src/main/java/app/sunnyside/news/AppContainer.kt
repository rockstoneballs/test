package app.sunnyside.news

import android.content.Context
import android.os.Build
import app.sunnyside.news.account.Accounts
import app.sunnyside.news.account.Ads
import app.sunnyside.news.account.Billing
import app.sunnyside.news.data.Downers
import app.sunnyside.news.data.FeedbackSender
import app.sunnyside.news.data.NewsRepository
import app.sunnyside.news.data.SettingsRepository
import app.sunnyside.news.data.local.AppDatabase
import app.sunnyside.news.data.remote.DirectSources
import app.sunnyside.news.data.remote.FeedApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency container; one per process, owned by [SunnysideApp]. */
class AppContainer(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Sunnyside/${BuildConfig.VERSION_NAME} (Android)").build())
        }
        .build()

    private val database = AppDatabase.create(context)

    val newsRepository = NewsRepository(
        context = context,
        db = database,
        feedApi = FeedApi(http, json, BuildConfig.FEED_URL),
        direct = DirectSources(http, json),
    )

    val settingsRepository = SettingsRepository(context)

    val feedbackSender = FeedbackSender(
        http = http,
        json = json,
        endpoint = BuildConfig.FEEDBACK_URL,
        repo = BuildConfig.REPO,
        appVersion = BuildConfig.VERSION_NAME,
        androidVersion = Build.VERSION.RELEASE,
    )

    /** For work that should outlive the screen that started it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downers = Downers(database, feedbackSender, appScope)

    val accounts = Accounts(context)
    val billing = Billing(context, accounts, appScope)
    val ads = Ads(context, appScope)

    /** No ads for subscribers: by Play's record on this phone, or the server's (bought on another device). */
    val adFree: StateFlow<Boolean> = combine(billing.ownsAdFree, accounts.adFreeOnServer) { local, server -> local || server }
        .stateIn(appScope, SharingStarted.Eagerly, false)
}
