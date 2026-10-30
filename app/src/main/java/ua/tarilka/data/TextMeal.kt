package ua.tarilka.data

/**
 * "квашена капуста 100 г, варена картопля 40 г котлета 50г" → items with an amount.
 * Commas are optional: an amount with its unit ends an item. Works without the AI model.
 */
object TextMeal {
    data class Item(val name: String, val amount: Double?, val unit: Unit)
    enum class Unit { GRAMS, PIECES }

    // Letters are spelled \p{L}: \w is ASCII-only on the JVM. "1/2" is a half.
    private val AMOUNT = Regex(
        """^(.*?)(\d+\s*/\s*\d+|\d+(?:[.,]\d+)?)\s*""" +
            """(кілограм\p{L}*|кг|kg|грам\p{L}*|гр|г|g|мл|ml|л|l|шт\p{L}*|pcs|pc|""" +
            """склян\p{L}*|чаш\p{L}*|ложк\p{L}*|ложок|скиб\p{L}*|шмат\p{L}*|порці\p{L}*)?\.?(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** Commas between items, but not the decimal comma in "0,5 л". */
    private val SEPARATORS = Regex("(?<!\\d),|,(?!\\d)|[;\\n]")

    /**
     * [isFood] tells whether some words name a known food. It splits "2 котлети гречка 200 г" (no comma) into
     * two foods, each with its own amount; without it, or when no split names two foods, the words stay together.
     */
    fun parse(text: String, isFood: ((String) -> Boolean)? = null): List<Item> {
        val out = mutableListOf<Item>()
        // Commas, semicolons and new lines separate items; inside a piece, every amount closes an item.
        for (piece in text.split(SEPARATORS)) {
            var rest = piece.trim()
            // "2 котлети": the amount comes first and belongs to the next name.
            var pending: Pair<Double, String>? = null
            while (rest.isNotEmpty()) {
                val m = AMOUNT.find(rest)
                if (m == null) {
                    clean(rest)?.let { name -> out += pending?.let { item(name, it.first, it.second) } ?: Item(name, null, Unit.GRAMS) }
                    break
                }
                val name = clean(m.groupValues[1])
                val number = number(m.groupValues[2]) ?: break
                val unit = m.groupValues[3].lowercase()
                when {
                    name == null -> pending = number to unit
                    pending != null -> {
                        // "2 котлети гречка 200 г": the first food takes the waiting amount, the last one this amount.
                        val split = isFood?.let { splitTwo(name, it) }
                        if (split != null) {
                            out += item(split.first, pending.first, pending.second)
                            out += item(split.second, number, unit)
                            pending = null
                        } else {
                            out += item(name, pending.first, pending.second)
                            pending = number to unit
                        }
                    }
                    else -> out += item(name, number, unit)
                }
                rest = rest.substring(m.range.last + 1).trim()
            }
        }
        return out
    }

    /** "котлети гречка" → ("котлети", "гречка") when both parts are foods; the longest second part wins. */
    private fun splitTwo(name: String, isFood: (String) -> Boolean): Pair<String, String>? {
        val words = name.split(Regex("\\s+"))
        if (words.size < 2) return null
        for (k in 1 until words.size) {
            val a = words.take(k).joinToString(" ")
            val b = words.drop(k).joinToString(" ")
            if (isFood(a) && isFood(b)) return a to b
        }
        return null
    }

    private fun number(s: String): Double? {
        val parts = s.split('/')
        if (parts.size == 2) {
            val a = parts[0].trim().toDoubleOrNull() ?: return null
            val b = parts[1].trim().toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            return a / b
        }
        return s.replace(',', '.').toDoubleOrNull()
    }

    private fun item(name: String, number: Double, unit: String): Item = when {
        unit.startsWith("кг") || unit.startsWith("kg") || unit.startsWith("кілограм") -> Item(name, number * 1000, Unit.GRAMS)
        unit == "л" || unit == "l" -> Item(name, number * 1000, Unit.GRAMS)
        // Household measures: a glass or cup about 250 g, a tablespoon about 15 g.
        unit.startsWith("склян") || unit.startsWith("чаш") -> Item(name, number * 250, Unit.GRAMS)
        unit.startsWith("ложк") || unit == "ложок" -> Item(name, number * 15, Unit.GRAMS)
        // A slice, a piece, a portion: the food's usual portion.
        unit.startsWith("шт") || unit.startsWith("pc") || unit.startsWith("скиб") || unit.startsWith("шмат") || unit.startsWith("порці") ->
            Item(name, number, Unit.PIECES)
        // A bare small number is a count ("котлети 2"), a bigger one is grams.
        unit.isEmpty() && number <= 10 -> Item(name, number, Unit.PIECES)
        else -> Item(name, number, Unit.GRAMS)
    }

    private fun clean(s: String): String? =
        s.trim().trimEnd('-', '–', ':', '—').trim().removePrefix("і ").removePrefix("та ").trim().takeIf { it.length >= 2 }

    /** Looks like a list rather than a search: has an amount, or several items. */
    fun looksLikeList(text: String): Boolean = text.any(Char::isDigit) || text.count { it == ',' } >= 1
}
