package com.example.wolfscreen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var setupHint: TextView
    private lateinit var notifWarning: TextView
    private lateinit var lastAnswerText: TextView
    private lateinit var lastScreenshot: ImageView

    // Коли служба записує нову відповідь — одразу оновлюємо екран.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ScreenshotAccessibilityService.KEY_LAST_ANSWER) runOnUiThread { refreshUi() }
    }

    // Після відповіді на запит сповіщень (будь-якої) ведемо в «Спеціальні можливості».
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { openAccessibilitySettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        apiKeyInput = findViewById(R.id.apiKeyInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        setupHint = findViewById(R.id.setupHint)
        notifWarning = findViewById(R.id.notifWarning)
        lastAnswerText = findViewById(R.id.lastAnswerText)
        lastScreenshot = findViewById(R.id.lastScreenshot)

        apiKeyInput.setText(prefs().getString(ScreenshotAccessibilityService.KEY_API, ""))

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }
    }

    // onResume спрацьовує й тоді, коли користувач повертається з налаштувань —
    // так екран одразу показує, чи службу вже увімкнено.
    override fun onResume() {
        super.onResume()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        refreshUi()
    }

    override fun onPause() {
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    /** Показує актуальний стан: чи увімкнена служба, останню відповідь і скрін. */
    private fun refreshUi() {
        val running = ScreenshotAccessibilityService.isEnabled(this)
        statusText.setText(if (running) R.string.status_running else R.string.status_idle)
        setupHint.visibility = if (running) View.GONE else View.VISIBLE
        stopButton.isEnabled = running

        // Без дозволу на сповіщення ні кнопка, ні відповідь не з'являться в шторці.
        val notifsOn = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notifWarning.visibility = if (notifsOn) View.GONE else View.VISIBLE

        lastAnswerText.text = prefs().getString(ScreenshotAccessibilityService.KEY_LAST_ANSWER, null)
            ?: getString(R.string.last_answer_empty)

        val file = File(cacheDir, ScreenshotAccessibilityService.LAST_SCREENSHOT_FILE)
        if (file.exists()) {
            lastScreenshot.setImageBitmap(BitmapFactory.decodeFile(file.path))
            lastScreenshot.visibility = View.VISIBLE
        } else {
            lastScreenshot.visibility = View.GONE
        }
    }

    private fun onStartClicked() {
        val key = apiKeyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.error_no_key, Toast.LENGTH_SHORT).show()
            return
        }
        prefs().edit().putString(ScreenshotAccessibilityService.KEY_API, key).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        if (ScreenshotAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.enable_in_settings, Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun onStopClicked() {
        val service = ScreenshotAccessibilityService.instance
        if (service != null) {
            // Служба сама вимикає себе в налаштуваннях і прибирає кнопку зі шторки.
            service.disableSelf()
            statusText.setText(R.string.status_idle)
            setupHint.visibility = View.VISIBLE
            stopButton.isEnabled = false
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun prefs() =
        getSharedPreferences(ScreenshotAccessibilityService.PREFS, Context.MODE_PRIVATE)
}
