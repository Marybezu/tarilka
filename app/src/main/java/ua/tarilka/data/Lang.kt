package ua.tarilka.data

/** Guards the Ukrainian interface against Russian words the model sometimes produces. */
object Lang {
    private val RUSSIAN_LETTERS = Regex("[ыэъёЫЭЪЁ]")
    /** Common Russian words written only with letters Ukrainian also has. */
    private val RUSSIAN_WORDS = Regex(
        "(?iu)(?<![\\p{L}])(и|что|это|или|нет|как|если|также|очень|можно|нужно|рекомендуется|белок|овощи|рыба|мясо)(?![\\p{L}])",
    )

    fun looksRussian(s: String): Boolean = RUSSIAN_LETTERS.containsMatchIn(s) || RUSSIAN_WORDS.containsMatchIn(s)

    /** A Ukrainian name if it is clean, otherwise the English one. */
    fun safeUk(uk: String, en: String): String = if (uk.isBlank() || looksRussian(uk)) en else uk
}
