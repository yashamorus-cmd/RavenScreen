package com.example.ravenscreen

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Служба «Спеціальних можливостей». Живе, поки її увімкнено в налаштуваннях.
 *
 * Навіщо саме вона: це єдиний спосіб для звичайного додатка зробити ОДИН знімок
 * екрана без постійного запису. Постійний запис (MediaProjection) змушував
 * Android ховати текст сповіщень і вміст «чутливих» додатків.
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Обробка картинки й запит до ШІ — не на головному потоці, щоб не гальмувати телефон.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    // Відкрита картка з відповіддю (або null). Чіпаємо лише з головного потоку.
    private var overlay: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        createChannels(this)
        notifications.notify(NOTIF_CONTROL, buildControlNotification())
    }

    // Події інтерфейсу нам не потрібні: служба лише робить знімки на вимогу.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instanceRef = null
        hideOverlay()
        notifications.cancel(NOTIF_CONTROL)
        worker.shutdown()
        super.onDestroy()
    }

    /** Викликається з CaptureActivity. Чекаємо, поки шторка сховається, і робимо знімок. */
    fun requestCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            report(getString(R.string.notif_error_title), getString(R.string.error_old_android))
            return
        }
        // Попередня картка з відповіддю не повинна потрапити на новий знімок.
        hideOverlay()
        mainHandler.postDelayed({ takeOneScreenshot() }, CAPTURE_DELAY_MS)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeOneScreenshot() {
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val jpeg = try {
                    // Знімок приходить у пам'яті відеокарти — копіюємо в звичайну картинку.
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?: throw IllegalStateException("порожній знімок")
                    val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    toJpeg(downscale(bitmap, maxSide = 1568))
                } catch (e: Exception) {
                    report(getString(R.string.notif_error_title), "Скрін не вдався: ${e.message}")
                    return
                } finally {
                    buffer.close()
                }

                // Зберігаємо останній скрін, щоб у додатку було видно, що саме сфотографовано.
                runCatching { File(cacheDir, LAST_SCREENSHOT_FILE).writeBytes(jpeg) }
                askAi(Base64.encodeToString(jpeg, Base64.NO_WRAP))
            }

            override fun onFailure(errorCode: Int) {
                report(getString(R.string.notif_error_title), "Скрін не вдався (код $errorCode)")
            }
        })
    }

    private fun askAi(base64Jpeg: String) {
        val apiKey = prefs().getString(KEY_API, null)
        if (apiKey.isNullOrBlank()) {
            report(getString(R.string.notif_error_title), getString(R.string.error_no_key))
            return
        }
        showNotification(getString(R.string.app_name), getString(R.string.notif_working))
        try {
            report(getString(R.string.notif_answer_title), GeminiClient.ask(apiKey, base64Jpeg))
        } catch (e: Exception) {
            report(getString(R.string.notif_error_title), e.message ?: "невідома помилка")
        }
    }

    /** Показує результат сповіщенням і зберігає його, щоб він був видний і в додатку. */
    private fun report(title: String, text: String) {
        prefs().edit { putString(KEY_LAST_ANSWER, "$title: $text") }
        showNotification(title, text)
        // Сповіщення обрізає довгий текст, тому повну відповідь показуємо ще й карткою.
        mainHandler.post { showOverlay(title, text) }
        // Кожен знімок закінчується тут (відповіддю чи помилкою), тож служба далі не потрібна.
        AiRequestService.stop(this)
    }

    /**
     * Картка з повною відповіддю поверх поточного додатка. Тип TYPE_ACCESSIBILITY_OVERLAY
     * доступний лише службам «Спеціальних можливостей», тому окремий дозвіл не потрібен.
     * Викликати тільки з головного потоку.
     */
    // У окремого вікна немає «батька», тому root = null тут правильний.
    @SuppressLint("InflateParams")
    private fun showOverlay(title: String, text: String) {
        hideOverlay()
        // Тема додатка — щоб картка мала кольори Material і світлий/темний режим телефона.
        val themed = ContextThemeWrapper(this, R.style.Theme_RavenScreen)
        val view = LayoutInflater.from(themed).inflate(R.layout.answer_overlay, null)
        view.findViewById<TextView>(R.id.overlayTitle).text = title
        view.findViewById<TextView>(R.id.overlayText).apply {
            this.text = text
            movementMethod = ScrollingMovementMethod()
        }
        view.findViewById<View>(R.id.overlayClose).setOnClickListener { hideOverlay() }
        view.findViewById<View>(R.id.overlayCopy).setOnClickListener { copyToClipboard(text) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Не забираємо клавіатуру, а дотики поза карткою йдуть у додаток під нею.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Внизу: питання зазвичай угорі екрана, і картка не повинна його закривати.
            gravity = Gravity.BOTTOM
        }

        // Якщо система не дала показати вікно — лишається сповіщення, тож не падаємо.
        runCatching { windowManager().addView(view, params) }
            .onSuccess { overlay = view }
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { windowManager().removeViewImmediate(it) } }
        overlay = null
    }

    private fun windowManager() = getSystemService(WindowManager::class.java)

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // З Android 13 система сама показує підтвердження копіювання — дубль не потрібен.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.overlay_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /** Зменшуємо картинку, щоб запит був швидший. */
    private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
    }

    // JPEG у кілька разів менший за PNG — на мобільному інтернеті це помітно.
    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun buildControlNotification(): Notification {
        val captureIntent = Intent(this, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val capturePending = PendingIntent.getActivity(
            this, 1, captureIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_CONTROL)
            .setContentTitle(getString(R.string.notif_control_title))
            .setContentText(getString(R.string.notif_control_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_camera,
                getString(R.string.action_screenshot),
                capturePending
            )
            .build()
    }

    private fun showNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, CH_ANSWER)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        notifications.notify(NOTIF_ANSWER, notif)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "raven_prefs"
        const val KEY_API = "api_key"
        const val KEY_LAST_ANSWER = "last_answer"
        const val LAST_SCREENSHOT_FILE = "last_screenshot.jpg"

        // Слабке посилання: статичне поле не втримає службу в пам'яті після її знищення.
        @Volatile
        private var instanceRef: WeakReference<ScreenshotAccessibilityService>? = null

        /** Працююча служба (або null, якщо її вимкнено). Потрібна, щоб кнопка могла її покликати. */
        val instance: ScreenshotAccessibilityService?
            get() = instanceRef?.get()

        /** Канали сповіщень. Повторне створення безпечне: Android просто нічого не змінює. */
        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CH_CONTROL, context.getString(R.string.channel_control_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CH_ANSWER, context.getString(R.string.channel_answer_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        /** Чи увімкнена служба в налаштуваннях телефона. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, ScreenshotAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        const val CH_CONTROL = "control"
        const val CH_ANSWER = "answer"
        private const val NOTIF_CONTROL = 1
        private const val NOTIF_ANSWER = 2

        // Пауза, щоб шторка встигла повністю сховатися й не потрапила в кадр.
        private const val CAPTURE_DELAY_MS = 800L
    }
}
