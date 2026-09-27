package app.sunnyside.news.ui.feedback

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sunnyside.news.SunnysideApp
import app.sunnyside.news.data.FeedbackDraft
import app.sunnyside.news.data.FeedbackResult
import app.sunnyside.news.data.Story
import app.sunnyside.news.util.openInBrowser
import kotlinx.coroutines.launch

private val FEEDBACK_KINDS = listOf(
    "idea" to "💡 An idea or suggestion",
    "bug" to "🐞 Something isn't working",
    "source" to "📰 A source we should add",
    "other" to "💬 Something else",
)

private val REPORT_REASONS = listOf(
    "not-good-news" to "It isn't good news",
    "clickbait" to "It's clickbait",
    "wrong" to "It's wrong or misleading",
    "broken" to "Broken link, picture or video",
    "other" to "Something else",
)

/** The feedback form. With a [story], it's a report about that post. */
@Composable
fun FeedbackDialog(story: Story?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sender = (context.applicationContext as SunnysideApp).container.feedbackSender
    val scope = rememberCoroutineScope()
    val options = if (story != null) REPORT_REASONS else FEEDBACK_KINDS
    var kind by rememberSaveable { mutableStateOf(options.first().first) }
    var message by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    val canSend = !sending && (story != null || message.isNotBlank())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 640.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (story != null) "Report this post" else "Send us feedback", style = MaterialTheme.typography.headlineSmall)
                if (story != null) {
                    Text(story.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        "Ideas, problems, sources we're missing: we read everything.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (story != null) "What's wrong with it?" else "What's it about?", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    options.forEach { (value, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = kind == value, role = Role.RadioButton, onClick = { kind = value })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = kind == value, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(2000) },
                    label = { Text(if (story != null) "Anything else? (optional)" else "Your message") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(200) },
                    label = { Text("Your email, if you'd like a reply (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = canSend,
                        onClick = {
                            sending = true
                            error = null
                            // "💡 An idea or suggestion" -> "An idea or suggestion"
                            val label = options.first { it.first == kind }.second.let { if (story == null) it.substringAfter(' ') else it }
                            scope.launch {
                                when (val result = sender.send(FeedbackDraft(kind, label, message, email, story))) {
                                    FeedbackResult.Sent -> {
                                        Toast.makeText(context, "Thanks! Your feedback was sent.", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                    is FeedbackResult.OpenInBrowser -> {
                                        openInBrowser(context, result.url, toolbarColor)
                                        onDismiss()
                                    }
                                    FeedbackResult.Failed -> {
                                        error = "Couldn't send that. Check your connection and try again."
                                        sending = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text(if (sending) "Sending…" else "Send") }
                }
            }
        }
    }
}
