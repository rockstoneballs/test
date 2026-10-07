package app.sunnyside.news.account

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** The Play Console subscription that removes ads (see android/play/README.md). */
const val AD_FREE_PRODUCT = "ad_free"

/** Play's page for managing (or cancelling) the subscription. */
const val MANAGE_SUBSCRIPTION_URL =
    "https://play.google.com/store/account/subscriptions?sku=$AD_FREE_PRODUCT&package=app.sunnyside.news"

/**
 * Google Play Billing for "Sunnyside ad-free". Buying ties the purchase to the reader's
 * account; the server then checks it with Google, acknowledges it and marks the account
 * ad-free ([Accounts.claimPurchase]). Play's own record also counts here ([ownsAdFree]),
 * so a subscriber never sees ads just because the server is slow or offline.
 */
class Billing(
    context: Context,
    private val accounts: Accounts,
    private val scope: CoroutineScope,
) {
    private val _ownsAdFree = MutableStateFlow(false)
    /** Play says this phone's Google account has an active ad-free subscription. */
    val ownsAdFree: StateFlow<Boolean> = _ownsAdFree.asStateFlow()

    private val _checked = MutableStateFlow(false)
    /** Play has been asked about existing subscriptions at least once (or couldn't be reached). */
    val checked: StateFlow<Boolean> = _checked.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)
    /** The subscription's price as Play shows it locally (e.g. "£1.99"), once known. */
    val price: StateFlow<String?> = _price.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Something to tell the reader after a purchase attempt. */
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    private var product: ProductDetails? = null
    private val connecting = Mutex()
    private val claimed = mutableSetOf<String>()

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(PurchasesUpdatedListener { result, purchases -> onPurchasesUpdated(result, purchases) })
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** Connects to Play, looks up the price and checks for an existing subscription. */
    fun refresh() {
        scope.launch {
            try {
                if (!connect()) return@launch
                loadProduct()
                handle(queryPurchases())
            } finally {
                _checked.value = true
            }
        }
    }

    /** Opens Play's purchase sheet. The reader must be signed in when accounts are available. */
    fun buy(activity: Activity) {
        val details = product
        val offer = details?.subscriptionOfferDetails?.firstOrNull()
        if (details == null || offer == null) {
            _message.value = "The ad-free subscription isn't available right now. Is the app installed from Google Play?"
            refresh()
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
        accounts.current?.let { params.setObfuscatedAccountId(Accounts.accountHash(it.uid)) }
        client.launchBillingFlow(activity, params.build())
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch { handle(purchases.orEmpty(), justBought = true) }
            BillingResponseCode.USER_CANCELED -> Unit
            BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> _message.value = "Google Play couldn't complete the purchase. Please try again."
        }
    }

    private suspend fun handle(purchases: List<Purchase>, justBought: Boolean = false) {
        val ours = purchases.filter { AD_FREE_PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        _ownsAdFree.value = ours.isNotEmpty()
        for (purchase in ours) {
            val token = purchase.purchaseToken
            if (token in claimed) continue
            val claimedOnServer = accounts.available && accounts.claimPurchase(token)
            if (claimedOnServer) claimed += token
            // The server acknowledges purchases it records. Otherwise (no accounts in this build, or the
            // server unreachable for a day) acknowledge here: Play refunds purchases left unacknowledged
            // for three days.
            val waitedTooLong = System.currentTimeMillis() - purchase.purchaseTime > 24 * 3_600_000L
            if (!claimedOnServer && !purchase.isAcknowledged && (!accounts.available || waitedTooLong)) acknowledge(token)
        }
        if (justBought && ours.isNotEmpty()) _message.value = "Thank you! Sunnyside is now ad-free ☀️"
    }

    // ------------------------------------------------------------------ Play Billing plumbing

    private suspend fun connect(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    private suspend fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(AD_FREE_PRODUCT)
                        .setProductType(ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        val details = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, found ->
                val list = if (result.responseCode == BillingResponseCode.OK) found.productDetailsList else emptyList()
                if (cont.isActive) cont.resume(list.firstOrNull())
            }
        }
        product = details
        _price.value = details?.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
    }

    private suspend fun queryPurchases(): List<Purchase> = suspendCancellableCoroutine { cont ->
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()) { result, purchases ->
            if (cont.isActive) cont.resume(if (result.responseCode == BillingResponseCode.OK) purchases else emptyList())
        }
    }

    private suspend fun acknowledge(token: String) {
        suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) {
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}
