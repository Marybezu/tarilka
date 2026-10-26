package ua.tarilka.data

import android.content.Context

/**
 * Food groups that health guidance talks about. Every built-in food has at most one, listed openly in
 * assets/food_groups.csv (from scripts/food_sources/groups.csv). Own foods have none.
 */
enum class FoodGroup {
    PROCESSED_MEAT, RED_MEAT, POULTRY, FISH, OILY_FISH, LEGUMES, NUTS,
    WHOLE_GRAIN, REFINED_GRAIN, SFA_RICH, FASTFOOD, SUGARY_DRINK, SWEETS, VEG_FRUIT,
}

object FoodGroups {
    @Volatile private var map: Map<String, FoodGroup>? = null

    fun load(context: Context): Map<String, FoodGroup> = map ?: synchronized(this) {
        map ?: runCatching {
            context.assets.open("food_groups.csv").bufferedReader(Charsets.UTF_8).readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("code;") }
                .mapNotNull { line -> line.split(';').takeIf { it.size >= 2 }?.let { (code, g) -> runCatching { code to FoodGroup.valueOf(g.trim()) }.getOrNull() } }
                .toMap()
        }.getOrDefault(emptyMap()).also { map = it }
    }
}

/**
 * "What could change" and "what is already good" for a week or longer, from the person's own foods.
 * Each rule follows a public guideline (see [Advice.Rule.source]); suggestions say "swap some" or
 * "a smaller portion", never "cut out". People who should not eat less (pregnancy, under 18, BMI under 18.5)
 * get only the "add" suggestions.
 */
object Advice {
    enum class Rule(val reduce: Boolean, val source: Int) {
        PROCESSED_MEAT(true, ua.tarilka.R.string.src_processed),
        RED_MEAT(true, ua.tarilka.R.string.src_red),
        SUGAR(true, ua.tarilka.R.string.src_sugar),
        SFA_FOODS(true, ua.tarilka.R.string.src_sfa),
        FASTFOOD(true, ua.tarilka.R.string.src_who),
        REFINED_GRAINS(true, ua.tarilka.R.string.src_fibre),
        VEG_FRUIT(false, ua.tarilka.R.string.src_veg),
        LEGUMES(false, ua.tarilka.R.string.src_legumes),
        FISH(false, ua.tarilka.R.string.src_fish),
        NUTS(false, ua.tarilka.R.string.src_nuts),
    }

    /** One suggestion: the rule, how often or how much, and the person's own foods behind it. */
    data class Item(val rule: Rule, val amount: Double, val foods: List<String>, val good: Boolean = false)

    data class Result(val change: List<Item>, val good: Item?)

    /**
     * [entries] of [days] logged days (at least 7 calendar days are expected); [foods] by id.
     * Returns at most two things to change (one "less", one "more" when both exist) and one thing that is good.
     */
    fun of(entries: List<DiaryEntry>, foods: Map<Long, Food>, groups: Map<String, FoodGroup>, days: Int, mayReduce: Boolean): Result {
        if (days < Habits.MIN_DAYS || entries.isEmpty()) return Result(emptyList(), null)
        fun group(e: DiaryEntry): FoodGroup? = e.foodId?.let(foods::get)?.code?.let(groups::get)
        val perWeek = 7.0 / days.coerceAtLeast(1)
        val byGroup = entries.groupBy { group(it) }
        fun daysWith(g: FoodGroup) = byGroup[g].orEmpty().map { it.day }.distinct().size
        fun top(list: List<DiaryEntry>, n: Int = 3) = list.groupBy { it.name() }.entries
            .sortedByDescending { (_, l) -> l.sumOf { it.kcal } }.take(n).map { it.key }
        fun topOf(vararg g: FoodGroup) = top(g.flatMap { byGroup[it].orEmpty() })
        val kcal = entries.sumOf { it.kcal }.coerceAtLeast(1.0)

        val less = mutableListOf<Pair<Double, Item>>() // severity to item
        val more = mutableListOf<Pair<Double, Item>>()
        val good = mutableListOf<Item>()

        // Processed meat: any amount raises the risk; worth attention from 3 days a week.
        val pm = daysWith(FoodGroup.PROCESSED_MEAT) * perWeek
        if (pm >= 3) less += pm to Item(Rule.PROCESSED_MEAT, pm, topOf(FoodGroup.PROCESSED_MEAT))
        // Red meat over 500 g a week.
        val red = byGroup[FoodGroup.RED_MEAT].orEmpty().sumOf { it.grams } * perWeek
        if (red > 500) less += red / 500 to Item(Rule.RED_MEAT, red, topOf(FoodGroup.RED_MEAT))
        // Sugar: calories from sweets and sugary drinks over 10 %.
        val sugarPct = (byGroup[FoodGroup.SWEETS].orEmpty() + byGroup[FoodGroup.SUGARY_DRINK].orEmpty()).sumOf { it.kcal } / kcal * 100
        if (sugarPct > 10) less += sugarPct / 10 to Item(Rule.SUGAR, sugarPct, topOf(FoodGroup.SWEETS, FoodGroup.SUGARY_DRINK))
        // Foods rich in saturated fat: over a fifth of calories (the database has total fat only, so this is by food).
        val sfaPct = byGroup[FoodGroup.SFA_RICH].orEmpty().sumOf { it.kcal } / kcal * 100
        if (sfaPct > 20) less += sfaPct / 20 to Item(Rule.SFA_FOODS, sfaPct, topOf(FoodGroup.SFA_RICH))
        // Fast food on 2+ days a week.
        val ff = daysWith(FoodGroup.FASTFOOD) * perWeek
        if (ff >= 2) less += ff / 2 to Item(Rule.FASTFOOD, ff, topOf(FoodGroup.FASTFOOD))
        // Grains: mostly refined.
        val whole = byGroup[FoodGroup.WHOLE_GRAIN].orEmpty().sumOf { it.kcal }
        val refined = byGroup[FoodGroup.REFINED_GRAIN].orEmpty().sumOf { it.kcal }
        if (refined > 0 && whole / (whole + refined) < 0.25 && daysWith(FoodGroup.REFINED_GRAIN) * perWeek >= 4) {
            less += 1.0 to Item(Rule.REFINED_GRAINS, whole / (whole + refined) * 100, topOf(FoodGroup.REFINED_GRAIN))
        } else if (whole > 0 && whole / (whole + refined) >= 0.5) {
            good += Item(Rule.REFINED_GRAINS, whole / (whole + refined) * 100, topOf(FoodGroup.WHOLE_GRAIN), good = true)
        }

        // "More of": vegetables and fruit, legumes, fish, nuts.
        val veg = byGroup[FoodGroup.VEG_FRUIT].orEmpty().sumOf { it.grams } / days
        if (veg < 400) more += (400 - veg) / 100 to Item(Rule.VEG_FRUIT, veg, topOf(FoodGroup.VEG_FRUIT))
        else good += Item(Rule.VEG_FRUIT, veg, topOf(FoodGroup.VEG_FRUIT), good = true)
        val legumes = daysWith(FoodGroup.LEGUMES) * perWeek
        if (legumes < 1) more += 1.5 to Item(Rule.LEGUMES, legumes, emptyList())
        else if (legumes >= 2) good += Item(Rule.LEGUMES, legumes, topOf(FoodGroup.LEGUMES), good = true)
        val fish = (daysWith(FoodGroup.FISH) + daysWith(FoodGroup.OILY_FISH)) * perWeek
        if (fish < 1) more += 1.2 to Item(Rule.FISH, fish, emptyList())
        else if (fish >= 1 && daysWith(FoodGroup.OILY_FISH) > 0) good += Item(Rule.FISH, fish, topOf(FoodGroup.OILY_FISH, FoodGroup.FISH), good = true)
        val nuts = daysWith(FoodGroup.NUTS) * perWeek
        if (nuts < 1) more += 0.5 to Item(Rule.NUTS, nuts, emptyList())

        val change = buildList {
            if (mayReduce) less.maxByOrNull { it.first }?.let { add(it.second) }
            more.maxByOrNull { it.first }?.let { add(it.second) }
        }
        return Result(change, good.firstOrNull())
    }

    /** The biggest sources of [value] in a day: "sausage 24 g, mayonnaise 18 g". */
    fun sources(entries: List<DiaryEntry>, value: (DiaryEntry) -> Double, n: Int = 3): List<Pair<String, Double>> =
        entries.groupBy { it.name() }.map { (name, l) -> name to l.sumOf(value) }
            .filter { it.second > 0.5 }.sortedByDescending { it.second }.take(n)
}
