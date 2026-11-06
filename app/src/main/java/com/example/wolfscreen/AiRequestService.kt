package com.example.wolfscreen

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Служба «переднього плану» (foreground), яка живе лише поки йде запит до ШІ.
 *
 * Навіщо: коли кнопка «📸 Скрін» відпрацювала, WolfScreen опиняється у фоні.
 * У режимі «Енергозбереження» Android забороняє фоновим додаткам інтернет —
 * запит до Google «зависав» і закінчувався помилкою timeout.
 * Поки працює foreground-служба, додаток вважається активним і мережу не блокують.
 * Сама служба нічого не робить: запит виконує ScreenshotAccessibilityService.
 */
class AiRequestService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, ScreenshotAccessibilityService.CH_CONTROL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_working))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        // Якщо система вб'є процес, перезапускати службу не треба: запит уже втрачено.
        return START_NOT_STICKY
    }

    // Android 15+: система обмежує час роботи таких служб. Коректно зупиняємося, щоб не було збою.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        private const val NOTIF_ID = 3

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AiRequestService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AiRequestService::class.java))
        }
    }
}
