package com.example.wolfscreen

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Невидима «проміжна» активність для кнопки «📸 Скрін».
 *
 * Навіщо: з Android 12 додаткам заборонено самим закривати шторку сповіщень
 * (ACTION_CLOSE_SYSTEM_DIALOGS кидає помилку). Але коли кнопка сповіщення
 * відкриває активність, система закриває шторку сама. Тож ми відкриваємо
 * цю прозору активність, вона просить службу зробити скрін і одразу закривається.
 */
class CaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = ScreenshotAccessibilityService.instance
        if (service != null) {
            // Запускаємо саме тут: поки ця активність на екрані, Android дозволяє старт
            // foreground-служби. Із фону (зі служби) він міг би заборонити.
            // Якщо все ж не вийде — скрін однаково робимо, просто без захисту від енергозбереження.
            runCatching { AiRequestService.start(this) }
            service.requestCapture()
        } else {
            Toast.makeText(this, R.string.error_service_off, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
