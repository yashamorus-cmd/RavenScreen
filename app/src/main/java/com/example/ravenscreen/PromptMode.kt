package com.example.ravenscreen

import androidx.annotation.StringRes

/**
 * Режими запиту до ШІ: що саме ми просимо зробити зі скріншотом.
 *
 * [prefValue] зберігається в налаштуваннях. Це окремий рядок, а не назва константи,
 * щоб перейменування в коді не «губило» вибір користувача.
 */
enum class PromptMode(
    val prefValue: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val instruction: String,
) {
    SHORT(
        "short", R.string.mode_short, R.string.mode_short_desc,
        "Це скріншот мого екрана. Якщо на ньому є питання, тест або завдання — " +
            "дай коротку й точну відповідь українською. Якщо це тест із варіантами, " +
            "назви правильний варіант і поясни одним реченням. Відповідай стисло.",
    ),
    DETAILED(
        "detailed", R.string.mode_detailed, R.string.mode_detailed_desc,
        "Це скріншот мого екрана. Поясни українською покроково, що тут потрібно зробити " +
            "і як дійти до відповіді. Наприкінці окремим рядком напиши: «Відповідь: …».",
    ),
    TRANSLATE(
        "translate", R.string.mode_translate, R.string.mode_translate_desc,
        "Переклади весь текст зі скріншота українською. Якщо текст уже українською — " +
            "переклади його англійською. Зберігай порядок і абзаци, нічого не додавай від себе.",
    ),

    // Інструкцію пише користувач, тому тут вона порожня (див. buildPrompt).
    CUSTOM("custom", R.string.mode_custom, R.string.mode_custom_desc, "");

    companion object {
        /** Додається до кожного запиту: сповіщення й картка показують текст як є, без розмітки. */
        const val FORMAT_RULES =
            "Пиши звичайним текстом: без Markdown (без зірочок, решіток, «---») і без LaTeX. " +
                "Формули записуй простими символами, наприклад: I = U / R = 6 / 3 = 2 А."

        /** Режим зі збереженого значення; невідоме чи порожнє — «Коротко». */
        fun fromPref(value: String?): PromptMode =
            entries.firstOrNull { it.prefValue == value } ?: SHORT

        /** Який режим спрацює насправді: «Свій» без тексту працює як «Коротко». */
        fun effective(mode: PromptMode, customText: String?): PromptMode =
            if (mode == CUSTOM && customText.isNullOrBlank()) SHORT else mode

        /** Повний текст запиту до ШІ для вибраного режиму. */
        fun buildPrompt(mode: PromptMode, customText: String?): String {
            val actual = effective(mode, customText)
            val instruction = if (actual == CUSTOM) {
                "Це скріншот мого екрана. ${customText.orEmpty().trim()}"
            } else {
                actual.instruction
            }
            return "$instruction $FORMAT_RULES"
        }
    }
}
