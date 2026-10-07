package app.sunnyside.news.ui.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sunnyside.news.BuildConfig
import app.sunnyside.news.SunnysideApp
import app.sunnyside.news.account.MANAGE_SUBSCRIPTION_URL
import app.sunnyside.news.account.SignInResult
import app.sunnyside.news.util.openInBrowser
import kotlinx.coroutines.launch

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val Context.container get() = (applicationContext as SunnysideApp).container

/**
 * Settings → "Account & ad-free": sign in or out, go ad-free (Google Play subscription),
 * manage the subscription, change ad privacy choices, delete the account.
 */
@Composable
fun AccountSettings(sectionLabel: @Composable (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val accounts = container.accounts
    val account by accounts.account.collectAsStateWithLifecycle(initialValue = accounts.current)
    val adFree by container.adFree.collectAsStateWithLifecycle()
    val price by container.billing.price.collectAsStateWithLifecycle()
    val privacyOptions by container.ads.privacyOptionsRequired.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    var signingIn by remember { mutableStateOf(false) }
    var signInReason by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (!accounts.available && !BuildConfig.ADS_ENABLED) return // nothing to show in this build
    sectionLabel(if (!BuildConfig.ADS_ENABLED) "Account" else if (accounts.available) "Account & ad-free" else "Ad-free")

    if (accounts.available) {
        val signedIn = account
        if (signedIn == null) {
            ListItem(
                headlineContent = { Text("Sign in") },
                supportingContent = { Text("Keep Sunnyside ad-free on all your devices") },
                leadingContent = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                modifier = Modifier.clickable { signInReason = null; signingIn = true },
            )
        } else {
            ListItem(
                headlineContent = { Text(signedIn.name ?: "Signed in") },
                supportingContent = { Text(signedIn.email ?: "Signed in") },
                leadingContent = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                trailingContent = { TextButton(onClick = { scope.launch { accounts.signOut() } }) { Text("Sign out") } },
            )
        }
    }

    if (adFree) {
        ListItem(
            headlineContent = { Text("You're ad-free ☀️") },
            supportingContent = { Text("Thank you for supporting good news") },
            leadingContent = { Icon(Icons.Outlined.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        )
        ListItem(
            headlineContent = { Text("Manage subscription") },
            supportingContent = { Text("Change or cancel it on Google Play") },
            leadingContent = { Icon(Icons.Outlined.WbSunny, contentDescription = null) },
            modifier = Modifier.clickable { openInBrowser(context, MANAGE_SUBSCRIPTION_URL, toolbarColor) },
        )
    } else if (BuildConfig.ADS_ENABLED) {
        ListItem(
            headlineContent = { Text("Go ad-free") },
            supportingContent = { Text(price?.let { "$it a month. No ads anywhere in the app." } ?: "No ads anywhere in the app, for a small monthly fee.") },
            leadingContent = { Icon(Icons.Outlined.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable {
                if (accounts.available && account == null) {
                    signInReason = "Sign in first, so your ad-free subscription stays with you on every device."
                    signingIn = true
                } else {
                    context.findActivity()?.let { container.billing.buy(it) }
                }
            },
        )
        if (privacyOptions) {
            ListItem(
                headlineContent = { Text("Ad privacy choices") },
                supportingContent = { Text("Change what you agreed to for personalised ads") },
                leadingContent = { Icon(Icons.Outlined.Policy, contentDescription = null) },
                modifier = Modifier.clickable { context.findActivity()?.let { container.ads.showPrivacyOptions(it) } },
            )
        }
    }

    if (accounts.available && account != null) {
        ListItem(
            headlineContent = { Text("Delete account", color = MaterialTheme.colorScheme.error) },
            supportingContent = { Text("Removes your account and everything stored with it") },
            leadingContent = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            modifier = Modifier.clickable { confirmDelete = true },
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    if (signingIn) SignInDialog(reason = signInReason, onDismiss = { signingIn = false })
    if (confirmDelete) {
        DeleteAccountDialog(
            subscribed = adFree,
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    val result = accounts.deleteAccount()
                    val text = if (result is SignInResult.Failed) result.message else "Your account has been deleted."
                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DeleteAccountDialog(subscribed: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your account?") },
        text = {
            Text(
                "This deletes your Sunnyside account and everything stored with it. It can't be undone." +
                    if (subscribed) "\n\nIt doesn't cancel your Google Play subscription: cancel that first under Manage subscription." else "",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}

/** Sign in with Google, Apple or an emailed link. */
@Composable
fun SignInDialog(reason: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accounts = context.container.accounts
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var linkSentTo by rememberSaveable { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> SignInResult) {
        busy = true
        error = null
        scope.launch {
            when (val result = action()) {
                SignInResult.SignedIn -> {
                    Toast.makeText(context, "Signed in ☀️", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                SignInResult.LinkSent -> linkSentTo = email.trim()
                is SignInResult.Failed -> error = result.message
                else -> Unit
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (linkSentTo != null) "Check your email" else "Sign in to Sunnyside") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val sentTo = linkSentTo
                if (sentTo != null) {
                    Text("We've sent a sign-in link to $sentTo. Open it on this phone to finish signing in.")
                    return@Column
                }
                reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = { context.findActivity()?.let { a -> run { accounts.signInWithGoogle(a) } } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue with Google") }
                OutlinedButton(
                    onClick = { context.findActivity()?.let { a -> run { accounts.signInWithApple(a) } } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue with Apple") }
                Text("or get a sign-in link by email", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(200) },
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { run { accounts.sendEmailLink(email) } },
                    enabled = !busy && email.contains('@'),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Email me a link") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "We only use your account to remember your ad-free subscription. See our privacy policy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (linkSentTo != null) "OK" else "Not now") } },
    )
}

/**
 * Finishes an emailed sign-in link that the website handed back to the app. Asks for the
 * address if the link was requested on another phone.
 */
@Composable
fun FinishEmailSignIn(link: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val accounts = context.container.accounts
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf(accounts.pendingEmail ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finish() {
        busy = true
        scope.launch {
            when (val result = accounts.finishEmailLink(link, email)) {
                SignInResult.SignedIn -> {
                    Toast.makeText(context, "Signed in ☀️", Toast.LENGTH_SHORT).show()
                    onDone()
                }
                is SignInResult.Failed -> error = result.message
                else -> onDone()
            }
            busy = false
        }
    }

    // The usual case: the link was asked for on this phone, so we know the address.
    androidx.compose.runtime.LaunchedEffect(link) { if (accounts.pendingEmail != null) finish() }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Finish signing in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (busy) "Signing you in…" else "Which email address did you ask for the link with?")
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(200) },
                    label = { Text("Email address") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = ::finish, enabled = !busy && email.contains('@')) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
