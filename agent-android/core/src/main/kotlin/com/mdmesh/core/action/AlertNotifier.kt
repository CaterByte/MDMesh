package com.mdmesh.core.action

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mdmesh.core.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts an admin alert as a high-priority heads-up notification. MeinConnect fork: and shows it full-screen through
 * the app's alert activity ([ACTION_SHOW]) — on a kiosk phone a banner alone is easily missed or suppressed.
 */
@Singleton
class AlertNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun show(title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.mc_alert_channel_name)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_ID, n)
        present(title, body)
    }

    /** Best effort: the Device Owner may start activities from the background; a refusal leaves the notification. */
    private fun present(title: String, body: String) {
        runCatching {
            context.startActivity(
                Intent(ACTION_SHOW)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_BODY, body)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    companion object {
        const val ACTION_SHOW = "com.mdmesh.agent.action.SHOW_ALERT"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        private const val CHANNEL = "mdm_alert"
        private const val ALERT_ID = 0x4D44 // 'MD'
    }
}
