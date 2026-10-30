package ua.tarilka.data

/** Guards the Ukrainian interface against Russian words the model sometimes produces. */
object Lang {
    private val RUSSIAN_LETTERS = Regex("[ыэъёЫЭЪЁ]")
    /** Common Russian words written only with letters Ukrainian also has. */
    private val RUSSIAN_WORDS = Regex(
        "(?iu)(?<![\\p{L}])(и|что|это|или|нет|как|если|также|очень|можно|нужно|рекомендуется|белок|овощи|рыба|мясо)(?![\\p{L}])",
    )

    /** Russian stems that never start a Ukrainian word. */
    private val RUSSIAN_STEMS = Regex("(?iu)(?<![\\p{L}])(подсолн|сегодня|сейчас|завтрак)")

    fun looksRussian(s: String): Boolean =
        RUSSIAN_LETTERS.containsMatchIn(s) || RUSSIAN_WORDS.containsMatchIn(s) || RUSSIAN_STEMS.containsMatchIn(s)

    /** Letters from another alphabet (the model once slipped into Devanagari mid-word). */
    fun hasForeignScript(s: String): Boolean = s.any { c ->
        c.isLetter() && Character.UnicodeScript.of(c.code) !in OK_SCRIPTS
    }
    private val OK_SCRIPTS = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC)

    /** Text fit to show: no stray alphabets, and for Ukrainian no Russian. */
    fun isClean(s: String, ukrainian: Boolean): Boolean = !hasForeignScript(s) && !(ukrainian && looksRussian(s))

    /** The words of the under-18 rule in the prompts (see [Targets.aiSafety]); checked by a test. */
    const val NO_KCAL_MARK = "never mention calories"

    private val CALORIES = Regex("(?iu)ккал|kcal|калор|calor")

    /** The text talks about calories (an AI answer for someone under 18 must not). */
    fun mentionsCalories(s: String): Boolean = CALORIES.containsMatchIn(s)

    /** A Ukrainian name if it is clean, otherwise the English one. */
    fun safeUk(uk: String, en: String): String = if (uk.isBlank() || looksRussian(uk)) en else uk
}
