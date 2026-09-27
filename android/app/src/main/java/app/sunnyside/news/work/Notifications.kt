package app.sunnyside.news.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sunnyside.news.MainActivity
import app.sunnyside.news.R
import app.sunnyside.news.data.Story

object Notifications {
    private const val CHANNEL_MORNING = "morning"
    private const val ID_MORNING = 1

    fun createChannels(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_MORNING,
            context.getString(R.string.notification_channel_morning),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_morning_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked by canPost().
    fun showMorningBriefing(context: Context, top: Story, count: Int) {
        if (!canPost(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_STORY_ID, top.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (count > 1) "☀️ Good morning! $count good things happened" else "☀️ Good morning!"
        val notification = NotificationCompat.Builder(context, CHANNEL_MORNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(top.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(top.title))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_MORNING, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}
