package app.sunnyside.news.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** What the reader filled in. [story] is set when they're reporting a post. */
data class FeedbackDraft(
    val kind: String,
    val label: String,
    val message: String,
    val email: String,
    val story: Story? = null,
)

/** Sent as JSON, the same shape as the website's feedback form. */
@Serializable
data class FeedbackPayload(
    @SerialName("_subject") val subject: String,
    val kind: String,
    val reason: String? = null,
    val message: String,
    val email: String? = null,
    val story: FeedbackStory? = null,
    val platform: String,
    val appVersion: String,
    val androidVersion: String,
)

@Serializable
data class FeedbackStory(val id: String, val title: String, val url: String, val source: String)

sealed interface FeedbackResult {
    data object Sent : FeedbackResult
    /** No form service is configured: finish sending on GitHub. */
    data class OpenInBrowser(val url: String) : FeedbackResult
    data object Failed : FeedbackResult
}

/**
 * Delivers feedback to the form service at [endpoint] (any service that accepts a JSON
 * POST, e.g. Formspree), or, while none is configured, hands back a pre-filled GitHub
 * issue link. Set the endpoint with the `sunnyside.feedbackUrl` Gradle property.
 */
class FeedbackSender(
    private val http: OkHttpClient,
    private val json: Json,
    private val endpoint: String,
    private val repo: String,
    private val appVersion: String,
    private val androidVersion: String,
) {
    fun payload(draft: FeedbackDraft): FeedbackPayload {
        val story = draft.story
        return FeedbackPayload(
            subject = if (story != null) "Report: ${draft.label} — ${story.title}".take(120) else "Feedback: ${draft.label}",
            kind = if (story != null) "report" else draft.kind,
            reason = if (story != null) draft.kind else null,
            message = draft.message.trim(),
            email = draft.email.trim().ifEmpty { null },
            story = story?.let { FeedbackStory(it.id, it.title, it.url, it.source) },
            platform = "android",
            appVersion = appVersion,
            androidVersion = androidVersion,
        )
    }

    /** Is a form service set up? Without one, feedback has to be finished on GitHub. */
    val configured: Boolean get() = endpoint.isNotBlank()

    /** Reports a post marked with the Downer button. Only sent when [configured]: there's no quiet way to file a GitHub issue. */
    suspend fun sendDowner(story: Story) {
        if (configured) send(FeedbackDraft("not-good-news", "It isn't good news", "Marked with the Downer button", "", story))
    }

    suspend fun send(draft: FeedbackDraft): FeedbackResult {
        val payload = payload(draft)
        if (endpoint.isBlank()) return FeedbackResult.OpenInBrowser(githubIssueUrl(payload))
        val body = json.encodeToString(FeedbackPayload.serializer(), payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).post(body).header("Accept", "application/json").build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { if (it.isSuccessful) FeedbackResult.Sent else FeedbackResult.Failed }
            } catch (_: IOException) {
                FeedbackResult.Failed
            }
        }
    }

    fun githubIssueUrl(p: FeedbackPayload): String {
        val body = buildString {
            append(p.message)
            p.story?.let { append("\n\n**Story:** [${it.title}](${it.url}) (${it.source}, id `${it.id}`)") }
            append("\n\n_Sent from the Sunnyside app ${p.appVersion}, Android ${p.androidVersion}_")
        }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://github.com/$repo/issues/new?title=${enc(p.subject)}&body=${enc(body)}&labels=feedback"
    }
}
