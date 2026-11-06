package com.example.wolfscreen

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Спрацьовує одразу після оновлення WolfScreen.
 *
 * Навіщо: Samsung після оновлення може вимкнути службу «Спеціальних можливостей».
 * Тоді кнопка «📸 Скрін» зникає зі шторки, і без підказки це легко не помітити.
 * Якщо служба вимкнена — нагадуємо сповіщенням, яке веде просто в потрібні налаштування.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (ScreenshotAccessibilityService.isEnabled(context)) return

        ScreenshotAccessibilityService.createChannels(context)
        val settings = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, ScreenshotAccessibilityService.CH_ANSWER)
            .setContentTitle(context.getString(R.string.notif_reenable_title))
            .setContentText(context.getString(R.string.notif_reenable_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_reenable_text)))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(settings)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    private companion object {
        const val NOTIF_ID = 4
    }
}
