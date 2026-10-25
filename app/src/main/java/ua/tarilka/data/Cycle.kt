package ua.tarilka.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One menstruation: first day and (optionally) last day, as epoch days. */
@Entity(tableName = "periods")
data class PeriodEntry(
    @PrimaryKey val start: Long,
    // "end" is an SQL keyword: stored under another column name.
    @androidx.room.ColumnInfo(name = "end_day") val end: Long? = null,
)

/** Optional notes for a day of menstruation. */
@Entity(tableName = "period_days")
data class PeriodDay(
    @PrimaryKey val day: Long,
    /** 0 light, 1 medium, 2 heavy */
    val flow: Int? = null,
    /** 0 none, 1 moderate, 2 strong */
    val pain: Int? = null,
)

/**
 * Cycle arithmetic, kept deliberately simple and honest:
 * - a gap over 60 days between two starts is treated as a forgotten entry, not a cycle;
 * - "irregular" only after 3 cycles, when they differ by more than 9 days (ACOG: up to 7–9 is normal);
 * - predictions need 2 recorded starts; ovulation is the calendar estimate (14 days before the next
 *   period) and is not shown for irregular cycles. Not contraception, not a fertility plan.
 */
data class CycleInfo(
    val lastStart: Long?,
    /** Valid cycle lengths, oldest first. */
    val lengths: List<Int>,
    val avgLength: Int?,
    val irregular: Boolean,
    val nextStart: Long?,
    val ovulation: Long?,
    /** Ongoing period (started, not ended, within 10 days). */
    val inPeriod: Boolean,
    /** Doctor flags. */
    val tooShortOrLong: Boolean,
    val longBleeding: Boolean,
    val heavyOrPainful: Boolean,
) {
    fun cycleDay(today: Long): Int? = lastStart?.let { (today - it + 1).toInt() }?.takeIf { it in 1..120 }
    /** The expected start passed by 2+ days without a new entry: ask, don't conclude. */
    fun late(today: Long): Boolean = nextStart != null && today >= nextStart + 2
    val seeDoctor: Boolean get() = tooShortOrLong || longBleeding || heavyOrPainful

    companion object {
        const val MAX_GAP = 60

        fun of(periods: List<PeriodEntry>, days: List<PeriodDay>, today: Long): CycleInfo {
            val sorted = periods.sortedBy { it.start }
            val lengths = sorted.zipWithNext { a, b -> (b.start - a.start).toInt() }.filter { it in 10..MAX_GAP }
            val recent = lengths.takeLast(6)
            val avg = recent.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val irregular = recent.size >= 3 && recent.max() - recent.min() > 9
            val last = sorted.lastOrNull()
            val next = if (avg != null && last != null) last.start + avg else null
            val byDay = days.associateBy { it.day }
            fun periodDays(p: PeriodEntry) = (p.start..(p.end ?: minOf(p.start + 9, today))).mapNotNull { byDay[it] }
            val lastTwo = sorted.takeLast(2)
            return CycleInfo(
                lastStart = last?.start,
                lengths = lengths,
                avgLength = avg,
                irregular = irregular,
                nextStart = next,
                ovulation = if (next != null && !irregular) next - 14 else null,
                inPeriod = last != null && last.end == null && today - last.start in 0..9,
                tooShortOrLong = recent.size >= 2 && avg != null && (avg < 21 || avg > 35),
                // Only from a marked end: an unmarked one is more likely forgotten than long.
                longBleeding = sorted.takeLast(3).any { p -> p.end != null && p.end - p.start + 1 > 7 },
                heavyOrPainful = lastTwo.size == 2 && lastTwo.all { p -> periodDays(p).any { it.flow == 2 || it.pain == 2 } },
            )
        }
    }
}
