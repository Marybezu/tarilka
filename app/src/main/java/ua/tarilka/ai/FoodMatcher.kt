package ua.tarilka.ai

import ua.tarilka.data.Food
import java.util.Locale

/**
 * Finds the database food that best matches a name the AI produced.
 * Numbers then come from the verified database, not from the model.
 */
object FoodMatcher {
    private val STOP = setOf(
        "with", "and", "a", "the", "of", "in", "on", "some", "fresh", "plain", "slice", "piece",
        "з", "із", "зі", "і", "й", "та", "в", "у", "на", "свіжий", "свіжа", "шматок",
    )

    private val SYNONYMS = mapOf("boiled" to "cooked", "stewed" to "cooked", "simmered" to "cooked")

    /**
     * English words are compared whole, with plurals folded ("tomatoes" = "tomato");
     * Ukrainian words are cut to 5 letters, a cheap stemmer for its endings.
     */
    private fun norm(word: String): String {
        val w = SYNONYMS[word] ?: word
        if (!w.all { it in 'a'..'z' || it == '\'' }) return w.take(5)
        val single = when {
            w.endsWith("oes") -> w.dropLast(2)
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("s") && !w.endsWith("ss") && w.length > 3 -> w.dropLast(1)
            else -> w
        }
        return SYNONYMS[single] ?: single
    }

    fun stems(s: String): Set<String> =
        s.lowercase(Locale.ROOT)
            .replace('ʼ', '\'').replace('’', '\'')
            .split(Regex("[^\\p{L}\\d']+"))
            .filter { it.length > 1 && it !in STOP }
            .map(::norm)
            .toSet()

    /** What a bare word on a plate usually means (codes in foods.csv). */
    private val DEFAULTS: Map<Set<String>, String> = mapOf(
        setOf("corn") to "v23", setOf("rice") to "g03", setOf("potato") to "v01", setOf("cheese") to "d15",
        setOf("bread") to "b01", setOf("chicken") to "m01", setOf("grilled", "chicken") to "m01",
        setOf("fish") to "s06", setOf("pasta") to "g15", setOf("egg") to "e01", setOf("beef") to "m13",
        setOf("pork") to "m09", setOf("salad") to "u24", setOf("bean") to "l01", setOf("noodle") to "g36",
        setOf("sausage") to "m17", setOf("meat") to "m13", setOf("salmon") to "s01", setOf("oatmeal") to "g06",
    )

    private fun score(query: Set<String>, name: String): Double {
        if (query.isEmpty()) return 0.0
        val target = stems(name)
        if (target.isEmpty()) return 0.0
        val common = query.intersect(target).size.toDouble()
        if (common == 0.0) return 0.0
        val jaccard = common / query.union(target).size
        val queryCoverage = common / query.size
        val targetCoverage = common / target.size
        return 0.4 * queryCoverage + 0.4 * targetCoverage + 0.2 * jaccard
    }

    private val RAW = setOf("raw", "dry", "сира", "сирий", "сухий", "суха").map(::norm).toSet()

    private val FAT_WORDS = setOf(
        "oil", "butter", "lard", "margarine", "mayonnaise", "sauce", "dressing", "ketchup", "mustard", "vinegar",
        "олія", "масло", "смалець", "маргарин", "майонез", "соус", "заправка", "кетчуп", "гірчиця", "оцет",
    ).map(::norm).toSet()

    data class Match(val food: Food, val score: Double)

    fun best(nameEn: String, nameUk: String, foods: List<Food>, threshold: Double = 0.5): Match? {
        // The model is asked to copy database names exactly, so try that first.
        foods.firstOrNull { it.nameEn.equals(nameEn.trim(), ignoreCase = true) }?.let { return Match(it, 1.0) }
        foods.firstOrNull { it.nameUk.equals(nameUk.trim(), ignoreCase = true) }?.let { return Match(it, 1.0) }
        val qEn = stems(nameEn)
        val qUk = stems(nameUk)
        val queryAll = qEn + qUk
        DEFAULTS[qEn]?.let { code -> foods.firstOrNull { it.code == code }?.let { return Match(it, 1.0) } }
        var best: Match? = null
        for (f in foods) {
            // Ukrainian stems collide more often (варена / вареники), so they weigh a bit less.
            var s = maxOf(score(qEn, f.nameEn), 0.9 * score(qUk, f.nameUk))
            if (s == 0.0) continue
            // A photo shows ready food: prefer cooked variants unless "raw"/"dry" was said.
            val foodStems = stems(f.nameEn) + stems(f.nameUk)
            if (RAW.any { it in foodStems } && RAW.none { it in queryAll }) s *= 0.85
            // "corn" is sweet corn, not corn oil; "mushrooms" are mushrooms, not mushroom soup.
            if (f.category in setOf("fats", "sauces") && FAT_WORDS.none { it in queryAll }) s *= 0.5
            if (f.category in setOf("dishes", "fastfood") && f.code != null) s *= 0.85
            // Ingredients without a typical portion (flour, dry grains) are not what a plate shows.
            if (f.portionG == null && f.code != null) s *= 0.8
            val b = best
            if (b == null || s > b.score || (s == b.score && f.nameEn.length < b.food.nameEn.length)) {
                best = Match(f, s)
            }
        }
        return best?.takeIf { it.score >= threshold }
    }
}
