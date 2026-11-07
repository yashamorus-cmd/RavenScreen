package com.example.ravenscreen

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
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusDot: MaterialCardView
    private lateinit var setupHint: View
    private lateinit var notifWarning: TextView
    private lateinit var lastAnswerText: TextView
    private lateinit var lastScreenshot: ImageView
    private lateinit var screenshotSection: View
    private lateinit var pageHome: View
    private lateinit var pageAnswer: View
    private lateinit var bottomNav: BottomNavigationView

    // Коли служба записує нову відповідь — одразу оновлюємо екран,
    // а якщо користувач зараз на «Головній», ставимо крапку на вкладці «Відповідь».
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ScreenshotAccessibilityService.KEY_LAST_ANSWER) runOnUiThread {
            refreshUi()
            if (bottomNav.selectedItemId != R.id.nav_answer) {
                bottomNav.getOrCreateBadge(R.id.nav_answer).isVisible = true
            }
        }
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
            // Знизу відступ не потрібен: BottomNavigationView сам відсувається від системної панелі.
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        apiKeyInput = findViewById(R.id.apiKeyInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        setupHint = findViewById(R.id.setupHint)
        notifWarning = findViewById(R.id.notifWarning)
        lastAnswerText = findViewById(R.id.lastAnswerText)
        lastScreenshot = findViewById(R.id.lastScreenshot)
        screenshotSection = findViewById(R.id.screenshotSection)
        pageHome = findViewById(R.id.pageHome)
        pageAnswer = findViewById(R.id.pageAnswer)
        bottomNav = findViewById(R.id.bottomNav)

        apiKeyInput.setText(prefs().getString(ScreenshotAccessibilityService.KEY_API, ""))

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }
        bottomNav.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
    }

    // onResume спрацьовує й тоді, коли користувач повертається з налаштувань —
    // так екран одразу показує, чи службу вже увімкнено.
    override fun onResume() {
        super.onResume()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        // Після повороту екрана панель сама пам'ятає вкладку, а сторінку показуємо ми.
        showPage(bottomNav.selectedItemId)
        refreshUi()
    }

    /** Показує сторінку вибраної вкладки й ховає іншу. */
    private fun showPage(itemId: Int) {
        val answer = itemId == R.id.nav_answer
        pageHome.visibility = if (answer) View.GONE else View.VISIBLE
        pageAnswer.visibility = if (answer) View.VISIBLE else View.GONE
        if (answer) bottomNav.removeBadge(R.id.nav_answer)
    }

    override fun onPause() {
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    /** Показує актуальний стан: чи увімкнена служба, останню відповідь і скрін. */
    private fun refreshUi() {
        showRunning(ScreenshotAccessibilityService.isEnabled(this))

        // Без дозволу на сповіщення ні кнопка, ні відповідь не з'являться в шторці.
        val notifsOn = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notifWarning.visibility = if (notifsOn) View.GONE else View.VISIBLE

        lastAnswerText.text = prefs().getString(ScreenshotAccessibilityService.KEY_LAST_ANSWER, null)
            ?: getString(R.string.last_answer_empty)

        // Розпакування JPEG займає час — робимо це у фоні, щоб екран не «підвисав».
        lifecycleScope.launch {
            val file = File(cacheDir, ScreenshotAccessibilityService.LAST_SCREENSHOT_FILE)
            val bitmap = withContext(Dispatchers.IO) {
                if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            }
            lastScreenshot.setImageBitmap(bitmap)
            screenshotSection.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        }
    }

    private fun onStartClicked() {
        val key = apiKeyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.error_no_key, Toast.LENGTH_SHORT).show()
            return
        }
        prefs().edit { putString(ScreenshotAccessibilityService.KEY_API, key) }

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
            showRunning(false)
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /** Текст і крапка статусу, підказка й кнопка «Вимкнути» — завжди узгоджені між собою. */
    private fun showRunning(running: Boolean) {
        statusText.setText(if (running) R.string.status_running else R.string.status_idle)
        statusDot.setCardBackgroundColor(
            ContextCompat.getColor(this, if (running) R.color.raven_ok else R.color.raven_text_muted)
        )
        setupHint.visibility = if (running) View.GONE else View.VISIBLE
        stopButton.isEnabled = running
    }

    private fun prefs() =
        getSharedPreferences(ScreenshotAccessibilityService.PREFS, Context.MODE_PRIVATE)
}
