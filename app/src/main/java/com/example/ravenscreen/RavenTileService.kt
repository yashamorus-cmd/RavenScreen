package com.example.ravenscreen

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Плитка «RavenScreen» у швидких налаштуваннях (шторці): один дотик — скрін і запит до ШІ.
 *
 * Сама плитка скрін не робить: вона відкриває ту саму невидиму CaptureActivity,
 * що й кнопка у сповіщенні. Так шторка закривається системою, а логіка знімка
 * живе в одному місці (ScreenshotAccessibilityService).
 */
class RavenTileService : TileService() {

    // Викликається, коли шторку відкрили й плитку видно: оновлюємо її вигляд.
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val running = ScreenshotAccessibilityService.isEnabled(this)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (running) R.string.tile_subtitle_on else R.string.tile_subtitle_off)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        // На заблокованому екрані спершу просимо розблокувати: скрін екрана блокування нікому не потрібен.
        if (isLocked) unlockAndRun { launch() } else launch()
    }

    private fun launch() {
        // Служба вимкнена — відкриваємо застосунок, де є кроки, як її увімкнути.
        val target = if (ScreenshotAccessibilityService.instance != null) {
            CaptureActivity::class.java
        } else {
            MainActivity::class.java
        }
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // З Android 14 варіант із простим Intent заборонений — лише через PendingIntent.
            val pending = PendingIntent.getActivity(
                this, target.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            launchBeforeAndroid14(intent)
        }
    }

    // Lint не бачить перевірки версії вище: сюди потрапляємо лише на Android 13 і старших,
    // де варіанта з PendingIntent ще немає, а цей дозволений.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchBeforeAndroid14(intent: Intent) {
        startActivityAndCollapse(intent)
    }
}
