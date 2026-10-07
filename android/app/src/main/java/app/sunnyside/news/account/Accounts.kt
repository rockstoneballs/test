package app.sunnyside.news.account

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import app.sunnyside.news.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/** A signed-in reader. */
data class Account(val uid: String, val name: String?, val email: String?)

/** Where the website's sign-in page lives; email links come back through it (web/signin.html). */
private const val EMAIL_LINK_PAGE = "https://rockstoneballs.github.io/test/signin.html"

/** Cloud Functions run in London (see firebase/functions/index.js). */
private const val FUNCTIONS_REGION = "europe-west2"

/**
 * Sunnyside accounts, on Firebase: sign in with Google, Apple or an emailed link; whether
 * the reader is ad-free (written by the server after checking Google Play); and deleting
 * the account. Builds without Firebase settings have no accounts ([available] is false).
 */
class Accounts(private val context: Context) {

    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    /** Whether this build has Firebase settings (see android/app/build.gradle.kts). */
    val available: Boolean = initFirebase(context)

    private val auth: FirebaseAuth? = if (available) FirebaseAuth.getInstance() else null

    /** The signed-in reader, or null. Emits on every sign-in and sign-out. */
    val account: Flow<Account?> = auth?.let { a ->
        callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAccount()) }
            a.addAuthStateListener(listener)
            awaitClose { a.removeAuthStateListener(listener) }
        }
    } ?: flowOf(null)

    val current: Account? get() = auth?.currentUser?.toAccount()

    /** True while the server says this reader has a paid-up ad-free subscription. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val adFreeOnServer: Flow<Boolean> = account.flatMapLatest { acc ->
        if (acc == null) {
            flowOf(false)
        } else {
            callbackFlow {
                val reg = FirebaseFirestore.getInstance().document("users/${acc.uid}").addSnapshotListener { snap, _ ->
                    val until = snap?.getLong("adFreeUntil") ?: 0L
                    trySend(snap?.getBoolean("adFree") == true && until > System.currentTimeMillis())
                }
                awaitClose { reg.remove() }
            }
        }
    }

    // ------------------------------------------------------------------ signing in

    /** Google, through Android's Credential Manager (the one-tap account picker). */
    suspend fun signInWithGoogle(activity: Activity): SignInResult = attempt {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.FIREBASE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (_: GetCredentialCancellationException) {
            return@attempt SignInResult.Cancelled
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return@attempt SignInResult.Failed("Google didn't return an account.")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        requireAuth().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        SignInResult.SignedIn
    }

    /** Apple, in a browser tab run by Firebase. */
    suspend fun signInWithApple(activity: Activity): SignInResult = attempt {
        val a = requireAuth()
        val provider = OAuthProvider.newBuilder("apple.com").setScopes(listOf("email", "name")).build()
        (a.pendingAuthResult ?: a.startActivityForSignInWithProvider(activity, provider)).await()
        SignInResult.SignedIn
    }

    /** Emails a sign-in link. Tapping it opens the website, which hands it back to the app. */
    suspend fun sendEmailLink(email: String): SignInResult = attempt {
        val settings = ActionCodeSettings.newBuilder()
            .setUrl(EMAIL_LINK_PAGE)
            .setHandleCodeInApp(true)
            .build()
        requireAuth().sendSignInLinkToEmail(email.trim(), settings).await()
        prefs.edit { putString(KEY_PENDING_EMAIL, email.trim()) }
        SignInResult.LinkSent
    }

    /** The email address a link was last sent to on this phone, if any. */
    val pendingEmail: String? get() = prefs.getString(KEY_PENDING_EMAIL, null)

    fun isEmailLink(link: String): Boolean = auth?.isSignInWithEmailLink(link) == true

    /** Finishes an emailed sign-in. [email] must be the address the link was sent to. */
    suspend fun finishEmailLink(link: String, email: String): SignInResult = attempt {
        requireAuth().signInWithEmailLink(email.trim(), link).await()
        prefs.edit { remove(KEY_PENDING_EMAIL) }
        SignInResult.SignedIn
    }

    suspend fun signOut() {
        auth?.signOut()
        if (available) {
            // Also forget the Google account choice, so the picker shows next time.
            try {
                CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Deletes the reader's data and account on the server, then signs out here. It doesn't
     * cancel a Google Play subscription: the app tells readers to do that first.
     */
    suspend fun deleteAccount(): SignInResult = attempt {
        functions().getHttpsCallable("deleteAccount").call().await()
        auth?.signOut()
        SignInResult.SignedOut
    }

    // ------------------------------------------------------------------ subscriptions

    /** Asks the server to check a Google Play purchase and mark this account ad-free. */
    suspend fun claimPurchase(purchaseToken: String): Boolean = try {
        if (current == null) {
            false
        } else {
            functions().getHttpsCallable("verifyPlaySubscription").call(mapOf("purchaseToken" to purchaseToken)).await()
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun functions() = FirebaseFunctions.getInstance(FUNCTIONS_REGION)

    private fun requireAuth(): FirebaseAuth = auth ?: error("Accounts aren't set up in this build")

    private inline fun attempt(block: () -> SignInResult): SignInResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SignInResult.Failed(e.localizedMessage ?: "Something went wrong. Please try again.")
    }

    companion object {
        private const val KEY_PENDING_EMAIL = "pending_email"

        /**
         * The id Google Play stores with each purchase (obfuscatedAccountId): a one-way
         * hash of the user id, never the id itself. The server computes the same
         * (firebase/functions/entitlement.js) to check a purchase belongs to the reader.
         */
        fun accountHash(uid: String): String =
            MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun initFirebase(context: Context): Boolean {
            if (BuildConfig.FIREBASE_PROJECT_ID.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank() ||
                BuildConfig.FIREBASE_API_KEY.isBlank()
            ) {
                return false
            }
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            return true
        }

        private fun FirebaseUser.toAccount() = Account(
            uid = uid,
            name = displayName?.takeIf { it.isNotBlank() } ?: providerData.firstNotNullOfOrNull { it.displayName?.takeIf(String::isNotBlank) },
            email = email ?: providerData.firstNotNullOfOrNull { it.email },
        )
    }
}

sealed interface SignInResult {
    data object SignedIn : SignInResult
    data object SignedOut : SignInResult
    data object LinkSent : SignInResult
    data object Cancelled : SignInResult
    data class Failed(val message: String) : SignInResult
}
