package com.example.wolfscreen

import android.os.SystemClock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Відповідає за один запит до безкоштовного Google Gemini API:
 * приймає API-ключ і скрін (base64 JPEG), повертає текст відповіді.
 *
 * Ключ береться безкоштовно тут: https://aistudio.google.com/app/apikey
 */
object GeminiClient {

    // Мітка для системного журналу (adb logcat -s WolfScreen). Ключ туди не пишемо.
    private const val TAG = "WolfScreen"

    // «-latest» — псевдонім, який Google сам переводить на актуальну модель,
    // тож код не зламається, коли стару версію вимкнуть.
    // Спершу легша lite-модель: відповідає за 2–3 с і її рідше перевантажують.
    // Повна flash — запасна: точніша, але повільніша й має малий безкоштовний ліміт на день.
    private val MODELS = listOf("gemini-flash-lite-latest", "gemini-flash-latest")

    private const val BASE =
        "https://generativelanguage.googleapis.com/v1beta/models"

    private const val PROMPT =
        "Це скріншот мого екрана. Якщо на ньому є питання, тест або завдання — " +
        "дай коротку й точну відповідь українською. Якщо це тест із варіантами, " +
        "назви правильний варіант і поясни одним реченням. Відповідай стисло. " +
        // Сповіщення й картка показують текст як є, тож розмітка виглядала б як «**» і «$».
        "Пиши звичайним текстом: без Markdown (без зірочок, решіток, «---») і без LaTeX. " +
        "Формули записуй простими символами, наприклад: I = U / R = 6 / 3 = 2 А."

    // Скільки разів обходимо список моделей, якщо всі перевантажені, і пауза між обходами.
    private const val ROUNDS = 2
    private const val RETRY_DELAY_MS = 2000L

    // Без «обдумування» відповідь приходить за кілька секунд. Якщо за 30 с нічого немає —
    // модель «зависла» від перевантаження, і швидше спитати запасну, ніж чекати далі.
    private const val FAST_CALL_TIMEOUT_S = 30L
    // Звичайний режим (з «обдумуванням») буває довгим — йому даємо більше часу.
    private const val SLOW_CALL_TIMEOUT_S = 90L

    /**
     * Як попросити модель не «обдумувати» відповідь (так вона відповідає за секунди).
     * Різні покоління Gemini розуміють різні налаштування, а на чужі відповідають
     * помилкою 400, тому пробуємо по черзі: від найшвидшого до звичайного режиму.
     */
    private enum class Thinking {
        MINIMAL_LEVEL, // нові моделі (Gemini 3): thinkingLevel = "minimal"
        LOW_LEVEL,     // нові моделі, які не знають «minimal»: thinkingLevel = "low"
        ZERO_BUDGET,   // старші (Gemini 2.5): thinkingBudget = 0
        DEFAULT,       // без налаштувань: повільніше, але приймає будь-яка модель
    }

    // З якого режиму починати для кожної моделі: відхилені (400) пропускаємо, щоб наступні
    // скріни не витрачали на них час. Живе, поки працює служба (весь час, доки WolfScreen увімкнений).
    private val startMode = ConcurrentHashMap<String, Thinking>()

    /** Чим закінчилася спроба з однією моделлю. */
    private sealed class Outcome {
        class Answer(val text: String) : Outcome()
        /** Відповіді немає, але інша модель може впоратися. [message] — причина для користувача. */
        class Failure(val message: String) : Outcome()
    }

    private val jsonType = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        // За замовчуванням OkHttp чекає відповідь лише 10 с — для ШІ над картинкою це мало.
        // Загальний ліміт на запит задаємо окремо для кожного режиму (див. askModel).
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(SLOW_CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** Робить запит і повертає відповідь текстом або кидає IOException з поясненням. */
    fun ask(apiKey: String, base64Jpeg: String): String {
        var lastError = "Сервіс недоступний, спробуй ще раз"

        repeat(ROUNDS) { round ->
            if (round > 0) Thread.sleep(RETRY_DELAY_MS)
            // Перевантажена чи «зависла» модель — одразу пробуємо наступну, легшу.
            for (model in MODELS) {
                when (val result = askModel(model, apiKey, base64Jpeg)) {
                    is Outcome.Answer -> return result.text
                    is Outcome.Failure -> lastError = result.message
                }
            }
        }

        throw IOException(lastError)
    }

    /**
     * Питає одну модель. Повертає відповідь або [Outcome.Failure], якщо варто спробувати іншу
     * модель (перевантаження, немає відповіді). Помилки, які інша модель не виправить
     * (напр. поганий ключ), кидає одразу як IOException.
     */
    private fun askModel(model: String, apiKey: String, base64Jpeg: String): Outcome {
        var mode = startMode[model] ?: Thinking.MINIMAL_LEVEL
        while (true) {
            val request = Request.Builder()
                .url("$BASE/$model:generateContent")
                // Ключ у заголовку, а не в адресі: адреси часто потрапляють у логи.
                .header("x-goog-api-key", apiKey)
                .post(buildBody(base64Jpeg, mode).toString().toRequestBody(jsonType))
                .build()
            val call = http.newCall(request)
            val limit = if (mode == Thinking.DEFAULT) SLOW_CALL_TIMEOUT_S else FAST_CALL_TIMEOUT_S
            call.timeout().timeout(limit, TimeUnit.SECONDS)

            val startedAt = SystemClock.elapsedRealtime()
            val response = try {
                call.execute()
            } catch (e: IOException) {
                Log.w(TAG, "$model/$mode: ${e.message} (${elapsedMs(startedAt)} мс)")
                return Outcome.Failure("Немає зв'язку з сервером: ${e.message}")
            }
            val code = response.code
            val text = response.use { it.body?.string().orEmpty() }
            val root = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()

            if (response.isSuccessful) {
                Log.i(TAG, "$model/$mode: OK (${elapsedMs(startedAt)} мс)")
                return Outcome.Answer(extractText(root))
            }

            val message = root.optJSONObject("error")?.optString("message") ?: text
            Log.w(TAG, "$model/$mode: HTTP $code (${elapsedMs(startedAt)} мс): $message")

            // Модель не прийняла цей спосіб вимкнути «обдумування» — запам'ятовуємо й пробуємо
            // наступний. Google часто відповідає загальним «invalid argument», тож дивимось лише на код.
            if (code == 400 && mode != Thinking.DEFAULT) {
                mode = Thinking.entries[mode.ordinal + 1]
                startMode[model] = mode
                continue
            }
            return when (code) {
                // Такої моделі вже немає.
                404 -> Outcome.Failure("Модель $model недоступна")
                // Тимчасове перевантаження.
                503, 429 -> Outcome.Failure("Сервери Google зараз перевантажені, спробуй за хвилину. ($message)")
                // Решта (напр. поганий ключ) — інша модель не допоможе.
                else -> throw IOException("Помилка API ($code): $message")
            }
        }
    }

    private fun elapsedMs(startedAt: Long) = SystemClock.elapsedRealtime() - startedAt

    private fun buildBody(base64Jpeg: String, mode: Thinking): JSONObject {
        val textPart = JSONObject().put("text", PROMPT)
        val imagePart = JSONObject().put(
            "inline_data",
            JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", base64Jpeg)
        )
        val content = JSONObject().put("parts", JSONArray().put(textPart).put(imagePart))
        val body = JSONObject().put("contents", JSONArray().put(content))
        val thinking = when (mode) {
            Thinking.MINIMAL_LEVEL -> JSONObject().put("thinkingLevel", "minimal")
            Thinking.LOW_LEVEL -> JSONObject().put("thinkingLevel", "low")
            Thinking.ZERO_BUDGET -> JSONObject().put("thinkingBudget", 0)
            Thinking.DEFAULT -> null
        }
        if (thinking != null) {
            body.put("generationConfig", JSONObject().put("thinkingConfig", thinking))
        }
        return body
    }

    /** Відповідь Gemini: candidates[0].content.parts[].text. */
    private fun extractText(root: JSONObject): String {
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        if (parts != null) {
            val builder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                // Нові моделі можуть додавати «думки» окремими частинами — їх не показуємо.
                if (part.optBoolean("thought")) continue
                builder.append(part.optString("text"))
            }
            val result = builder.toString().trim()
            if (result.isNotEmpty()) return result
        }

        val block = root.optJSONObject("promptFeedback")?.optString("blockReason")
        return if (!block.isNullOrEmpty()) "(запит відхилено фільтром: $block)"
        else "(порожня відповідь)"
    }
}
