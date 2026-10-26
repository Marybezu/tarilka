package ua.tarilka.data

import org.json.JSONArray
import org.json.JSONObject

/** The user's own dish made of ingredients. Built-in foods are stored by code, own foods by id. */
data class RecipeItem(val food: Food, val grams: Double)

object Recipe {
    /** Nutrients per 100 g of the finished dish. [cookedWeight] accounts for water lost or added while cooking. */
    fun per100(items: List<RecipeItem>, cookedWeight: Double?): Totals {
        val raw = items.sumOf { it.grams }
        val weight = cookedWeight?.takeIf { it > 0 } ?: raw
        if (weight <= 0) return Totals()
        fun sum(f: (Food) -> Double) = items.sumOf { f(it.food) * it.grams / 100 } / weight * 100
        return Totals(sum { it.kcal }, sum { it.protein }, sum { it.fat }, sum { it.carbs }, sum { it.fiber })
    }

    fun encode(items: List<RecipeItem>, cookedWeight: Double?): String = JSONObject()
        .put("items", JSONArray().apply {
            items.forEach { i ->
                val o = JSONObject().put("g", i.grams)
                if (i.food.code != null) o.put("code", i.food.code) else o.put("id", i.food.id)
                put(o)
            }
        })
        .put("cooked", cookedWeight ?: JSONObject.NULL)
        .toString()

    /** Returns the ingredients that still exist, and the cooked weight. */
    fun decode(json: String, foods: List<Food>): Pair<List<RecipeItem>, Double?> {
        val o = JSONObject(json)
        val byCode = foods.filter { it.code != null }.associateBy { it.code }
        val byId = foods.associateBy { it.id }
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            // A damaged entry (from a damaged backup) is skipped instead of breaking the recipe.
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = if (e.has("code")) byCode[e.optString("code")] else byId[e.optLong("id")]
            val g = e.optDouble("g").takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
            f?.let { RecipeItem(it, g) }
        }
        return items to o.optDouble("cooked").takeIf { it.isFinite() && it > 0 }
    }
}
