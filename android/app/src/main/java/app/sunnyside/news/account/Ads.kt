package app.sunnyside.news.account

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.sunnyside.news.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How often ads appear in the feed: after the 4th post, then after every 8 more. */
object FeedAds {
    const val FIRST_AFTER = 4
    const val EVERY = 8

    /** Is there an ad slot right after the post at [index] (0-based)? */
    fun slotAfter(index: Int): Boolean = index + 1 >= FIRST_AFTER && (index + 1 - FIRST_AFTER) % EVERY == 0
}

/**
 * AdMob, behind Google's consent message. In the UK and EEA, Google requires a consent
 * prompt before personalised ads; the User Messaging Platform shows it (designed in the
 * AdMob console under Privacy & messaging) and remembers the answer. Ads are only
 * requested once consent allows it ([ready]).
 */
class Ads(private val context: Context, private val scope: CoroutineScope) {
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    private val _ready = MutableStateFlow(false)
    /** Ads may be requested. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _privacyOptions = MutableStateFlow(false)
    /** Readers in consent regions must be able to change their choice (Settings → Ad privacy choices). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptions.asStateFlow()

    private var started = false

    /** Asks for consent if needed, then starts AdMob. Called only for readers who see ads. */
    fun start(activity: Activity) {
        if (started) return
        started = true
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    updateState()
                }
            },
            { updateState() }, // offline: use the answer from last time, if any
        )
        updateState()
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { updateState() }
    }

    private fun updateState() {
        _privacyOptions.value =
            consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (consent.canRequestAds() && !_ready.value) {
            scope.launch(Dispatchers.IO) {
                // Sunnyside is calm and family-friendly: no ads above a PG rating. Sensitive
                // categories (gambling, dating, politics…) are blocked in the AdMob console too.
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG)
                        .build(),
                )
                MobileAds.initialize(context) {}
                _ready.value = true
            }
        }
    }
}

/**
 * An ad between posts: an inline adaptive banner in a card labelled "Advertisement", with
 * a way to go ad-free. Nothing is shown if no ad loads.
 */
@Composable
fun FeedAd(onGoAdFree: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val width = LocalConfiguration.current.screenWidthDp - 48 // card margins and padding
    var loaded by remember { mutableStateOf(false) }
    val adView = remember {
        AdView(context).apply {
            adUnitId = BuildConfig.ADMOB_FEED_AD_UNIT
            setAdSize(AdSize.getInlineAdaptiveBannerAdSize(width, 320))
            adListener = object : AdListener() {
                override fun onAdLoaded() { loaded = true }
                override fun onAdFailedToLoad(error: LoadAdError) { loaded = false }
            }
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) { onDispose { adView.destroy() } }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = if (loaded) 4.dp else 0.dp, bottom = if (loaded) 12.dp else 0.dp)) {
            if (loaded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Advertisement", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onGoAdFree) { Text("Go ad-free") }
                }
            }
            // Always composed (so it can load), but takes no space until an ad arrives.
            AndroidView(factory = { adView }, modifier = if (loaded) Modifier.fillMaxWidth() else Modifier)
        }
    }
}
