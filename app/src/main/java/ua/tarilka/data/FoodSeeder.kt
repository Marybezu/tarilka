package ua.tarilka.data

import android.content.Context

/** Loads the built-in food database from assets/foods.csv into Room. */
object FoodSeeder {
    /** Bump when foods.csv changes so existing installs get the update. */
    const val VERSION = 6

    suspend fun seedIfNeeded(context: Context, db: AppDatabase, prefs: Prefs) {
        if (prefs.seedVersion >= VERSION) return
        val parsed = parse(context.assets.open("foods.csv").bufferedReader(Charsets.UTF_8).readLines())
        val existing = db.foods().codes().associateBy { it.code }
        db.foods().upsertAll(parsed.map { f ->
            val old = existing[f.code]
            f.copy(id = old?.id ?: 0, favorite = old?.favorite ?: false)
        })
        prefs.seedVersion = VERSION
    }

    fun parse(lines: List<String>): List<Food> {
        val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        val header = rows.first().split(';')
        fun idx(name: String) = header.indexOf(name).also { require(it >= 0) { "missing column $name" } }
        val c = mapOf(
            "code" to idx("code"), "category" to idx("category"), "uk" to idx("name_uk"),
            "en" to idx("name_en"), "kcal" to idx("kcal"), "p" to idx("protein"), "f" to idx("fat"),
            "c" to idx("carbs"), "fib" to idx("fiber"), "pg" to idx("portion_g"),
            "puk" to idx("portion_uk"), "pen" to idx("portion_en"), "src" to idx("source"),
        )
        return rows.drop(1).map { line ->
            val v = line.split(';')
            fun s(k: String) = v.getOrElse(c.getValue(k)) { "" }.trim()
            fun d(k: String) = s(k).toDoubleOrNull() ?: 0.0
            Food(
                code = s("code"), category = s("category"), nameUk = s("uk"), nameEn = s("en"),
                kcal = d("kcal"), protein = d("p"), fat = d("f"), carbs = d("c"), fiber = d("fib"),
                portionG = s("pg").toDoubleOrNull(),
                portionUk = s("puk").ifEmpty { null }, portionEn = s("pen").ifEmpty { null },
                source = s("src"),
            )
        }
    }
}
