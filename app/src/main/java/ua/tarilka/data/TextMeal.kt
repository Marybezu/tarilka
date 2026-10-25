package ua.tarilka.data

/**
 * "квашена капуста 100 г, варена картопля 40 г котлета 50г" → items with an amount.
 * Commas are optional: an amount with its unit ends an item. Works without the AI model.
 */
object TextMeal {
    data class Item(val name: String, val amount: Double?, val unit: Unit)
    enum class Unit { GRAMS, PIECES }

    private val AMOUNT = Regex(
        """^(.*?)(\d+(?:[.,]\d+)?)\s*(кілограм\w*|кг|kg|грам\w*|гр|г|g|мл|ml|л|l|шт\w*|pcs|pc)?\.?(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    /** Commas between items, but not the decimal comma in "0,5 л". */
    private val SEPARATORS = Regex("(?<!\\d),|,(?!\\d)|[;\\n]")

    fun parse(text: String): List<Item> {
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
                val number = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: break
                val unit = m.groupValues[3].lowercase()
                when {
                    name == null -> pending = number to unit
                    pending != null -> {
                        out += item(name, pending.first, pending.second)
                        pending = number to unit
                    }
                    else -> out += item(name, number, unit)
                }
                rest = rest.substring(m.range.last + 1).trim()
            }
        }
        return out
    }

    private fun item(name: String, number: Double, unit: String): Item = when {
        unit.startsWith("кг") || unit.startsWith("kg") || unit.startsWith("кілограм") -> Item(name, number * 1000, Unit.GRAMS)
        unit == "л" || unit == "l" -> Item(name, number * 1000, Unit.GRAMS)
        unit.startsWith("шт") || unit.startsWith("pc") -> Item(name, number, Unit.PIECES)
        // A bare small number is a count ("котлети 2"), a bigger one is grams.
        unit.isEmpty() && number <= 10 -> Item(name, number, Unit.PIECES)
        else -> Item(name, number, Unit.GRAMS)
    }

    private fun clean(s: String): String? =
        s.trim().trimEnd('-', '–', ':', '—').trim().removePrefix("і ").removePrefix("та ").trim().takeIf { it.length >= 2 }

    /** Looks like a list rather than a search: has an amount, or several items. */
    fun looksLikeList(text: String): Boolean = text.any(Char::isDigit) || text.count { it == ',' } >= 1
}
