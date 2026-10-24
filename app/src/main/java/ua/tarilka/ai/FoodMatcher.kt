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

    /** Tokens are cut to 5 characters, a cheap stemmer that works for both languages. */
    fun stems(s: String): Set<String> =
        s.lowercase(Locale.ROOT)
            .replace('ʼ', '\'').replace('’', '\'')
            .split(Regex("[^\\p{L}\\d']+"))
            .filter { it.length > 1 && it !in STOP }
            .map { it.take(5) }
            .toSet()

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

    private val RAW = setOf("raw", "dry", "сира", "сирий", "сухий", "суха").map { it.take(5) }.toSet()

    data class Match(val food: Food, val score: Double)

    fun best(nameEn: String, nameUk: String, foods: List<Food>, threshold: Double = 0.5): Match? {
        // The model is asked to copy database names exactly, so try that first.
        foods.firstOrNull { it.nameEn.equals(nameEn.trim(), ignoreCase = true) }?.let { return Match(it, 1.0) }
        foods.firstOrNull { it.nameUk.equals(nameUk.trim(), ignoreCase = true) }?.let { return Match(it, 1.0) }
        val qEn = stems(nameEn)
        val qUk = stems(nameUk)
        val queryAll = qEn + qUk
        var best: Match? = null
        for (f in foods) {
            // Ukrainian stems collide more often (варена / вареники), so they weigh a bit less.
            var s = maxOf(score(qEn, f.nameEn), 0.9 * score(qUk, f.nameUk))
            if (s == 0.0) continue
            // A photo shows ready food: prefer cooked variants unless "raw"/"dry" was said.
            val foodStems = stems(f.nameEn) + stems(f.nameUk)
            if (RAW.any { it in foodStems } && RAW.none { it in queryAll }) s *= 0.85
            val b = best
            if (b == null || s > b.score || (s == b.score && f.nameEn.length < b.food.nameEn.length)) {
                best = Match(f, s)
            }
        }
        return best?.takeIf { it.score >= threshold }
    }
}
