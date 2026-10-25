package ua.tarilka.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import ua.tarilka.data.DiaryEntry
import ua.tarilka.data.FluidEntry
import ua.tarilka.data.WeightEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Optional bridge to Health Connect (built into Android 14+).
 * Writes meals, drinks and weight; reads steps. Record ids use the entry's creation time,
 * so they stay unique across reinstalls and restores. Every call fails quietly:
 * the app works the same without it.
 */
class HealthSync(private val context: Context) {

    val available: Boolean
        get() = runCatching {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }.getOrDefault(false)

    private val client: HealthConnectClient? by lazy {
        if (available) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null
    }

    suspend fun granted(): Set<String> =
        runCatching { client?.permissionController?.getGrantedPermissions() }.getOrNull() ?: emptySet()

    suspend fun canReadSteps() = READ_STEPS in granted()
    suspend fun canWrite() = granted().containsAll(WRITE)

    /** Steps for a calendar day, or null when unavailable. */
    suspend fun steps(day: LocalDate): Long? {
        val c = client ?: return null
        if (!canReadSteps()) return null
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        return runCatching {
            c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()
    }

    /** Steps from the first to the last day (inclusive), or null when unavailable. */
    suspend fun steps(from: LocalDate, to: LocalDate): Long? {
        val c = client ?: return null
        if (!canReadSteps()) return null
        val zone = ZoneId.systemDefault()
        val end = minOf(to.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        return runCatching {
            c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), end)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()
    }

    /**
     * Hours slept per wake-up day. A night counts for the day it ended on
     * (sessions ending after 18:00 count for the next day: an evening nap is not last night).
     */
    suspend fun sleepByDay(from: LocalDate, to: LocalDate): Map<Long, Double> {
        val c = client ?: return emptyMap()
        if (READ_SLEEP !in granted()) return emptyMap()
        val zone = ZoneId.systemDefault()
        val start = from.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val end = minOf(to.atTime(18, 0).atZone(zone).toInstant(), Instant.now())
        if (!end.isAfter(start)) return emptyMap()
        val records = runCatching {
            c.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    SleepSessionRecord::class, TimeRangeFilter.between(start, end), pageSize = 2000,
                ),
            ).records
        }.getOrNull() ?: return emptyMap()
        return records.groupBy { r ->
            val local = r.endTime.atZone(zone)
            (if (local.hour >= 18) local.toLocalDate().plusDays(1) else local.toLocalDate()).toEpochDay()
        }.mapValues { (_, list) ->
            // A watch and a phone app may both record the same night: merge overlaps before adding up.
            var total = 0L
            var curStart: Instant? = null
            var curEnd: Instant? = null
            for (r in list.sortedBy { it.startTime }) {
                if (curEnd == null || r.startTime.isAfter(curEnd)) {
                    if (curStart != null) total += java.time.Duration.between(curStart, curEnd).toMinutes()
                    curStart = r.startTime
                    curEnd = r.endTime
                } else if (r.endTime.isAfter(curEnd)) {
                    curEnd = r.endTime
                }
            }
            if (curStart != null) total += java.time.Duration.between(curStart, curEnd).toMinutes()
            total / 60.0
        }
    }

    /** Menstruation periods another app wrote to Health Connect (start and end day); empty without access. */
    suspend fun periods(from: LocalDate): List<ua.tarilka.data.PeriodEntry> {
        val c = client ?: return emptyList()
        if (READ_PERIODS !in granted()) return emptyList()
        val zone = ZoneId.systemDefault()
        return runCatching {
            c.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    androidx.health.connect.client.records.MenstruationPeriodRecord::class,
                    TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), Instant.now()), pageSize = 500,
                ),
            ).records.map {
                ua.tarilka.data.PeriodEntry(
                    it.startTime.atZone(zone).toLocalDate().toEpochDay(),
                    it.endTime.minusSeconds(1).atZone(zone).toLocalDate().toEpochDay(),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Steps per calendar day in one request; empty without access. */
    suspend fun stepsByDay(from: LocalDate, to: LocalDate): Map<Long, Long> {
        val c = client ?: return emptyMap()
        if (!canReadSteps()) return emptyMap()
        val end = minOf(to.plusDays(1).atStartOfDay(), java.time.LocalDateTime.now())
        return runCatching {
            c.aggregateGroupByPeriod(
                androidx.health.connect.client.request.AggregateGroupByPeriodRequest(
                    setOf(StepsRecord.COUNT_TOTAL),
                    TimeRangeFilter.between(from.atStartOfDay(), end),
                    java.time.Period.ofDays(1),
                ),
            ).associate { it.startTime.toLocalDate().toEpochDay() to (it.result[StepsRecord.COUNT_TOTAL] ?: 0L) }
        }.getOrDefault(emptyMap())
    }

    suspend fun writeMeal(e: DiaryEntry) {
        val c = client ?: return
        if (!canWrite()) return
        val t = Instant.ofEpochMilli(e.createdAt)
        val off = ZoneId.systemDefault().rules.getOffset(t)
        val rec = NutritionRecord(
            startTime = t, startZoneOffset = off,
            endTime = t.plusSeconds(60), endZoneOffset = off,
            metadata = Metadata.manualEntry(clientRecordId = "diary-${e.createdAt}"),
            energy = Energy.kilocalories(e.kcal),
            protein = Mass.grams(e.protein),
            totalFat = Mass.grams(e.fat),
            totalCarbohydrate = Mass.grams(e.carbs),
            dietaryFiber = Mass.grams(e.fiber),
            name = e.nameEn,
            mealType = when (e.meal) {
                0 -> MealType.MEAL_TYPE_BREAKFAST
                1 -> MealType.MEAL_TYPE_LUNCH
                2 -> MealType.MEAL_TYPE_DINNER
                else -> MealType.MEAL_TYPE_SNACK
            },
        )
        runCatching { c.insertRecords(listOf(rec)) }
    }

    suspend fun deleteMeal(e: DiaryEntry) {
        val c = client ?: return
        runCatching { c.deleteRecords(NutritionRecord::class, emptyList(), listOf("diary-${e.createdAt}")) }
    }

    suspend fun writeFluid(f: FluidEntry) {
        val c = client ?: return
        if (!canWrite()) return
        val t = Instant.ofEpochMilli(f.createdAt)
        val off = ZoneId.systemDefault().rules.getOffset(t)
        runCatching {
            c.insertRecords(
                listOf(
                    HydrationRecord(
                        t, off, t.plusSeconds(60), off, Volume.milliliters(f.ml),
                        Metadata.manualEntry(clientRecordId = "fluid-${f.createdAt}"),
                    ),
                ),
            )
        }
    }

    suspend fun deleteFluid(f: FluidEntry) {
        val c = client ?: return
        runCatching { c.deleteRecords(HydrationRecord::class, emptyList(), listOf("fluid-${f.createdAt}")) }
    }

    suspend fun deleteWeight(w: WeightEntry) {
        val c = client ?: return
        runCatching { c.deleteRecords(WeightRecord::class, emptyList(), listOf("weight-${w.day}")) }
    }

    suspend fun writeWeight(w: WeightEntry) {
        val c = client ?: return
        if (!canWrite()) return
        val zone = ZoneId.systemDefault()
        val day = LocalDate.ofEpochDay(w.day)
        val t = w.minutes?.let { day.atTime(it / 60, it % 60).atZone(zone).toInstant() }
            ?: if (day == LocalDate.now()) Instant.now() else day.atTime(8, 0).atZone(zone).toInstant()
        runCatching {
            c.insertRecords(
                listOf(WeightRecord(t, zone.rules.getOffset(t), Mass.kilograms(w.kg), Metadata.manualEntry(clientRecordId = "weight-${w.day}"))),
            )
        }
    }

    /** Removes every record this app wrote (Health Connect limits the delete to our own records). */
    suspend fun deleteAllOwn() {
        val c = client ?: return
        if (!canWrite()) return
        val range = TimeRangeFilter.before(Instant.now().plusSeconds(2 * 86_400))
        listOf(NutritionRecord::class, HydrationRecord::class, WeightRecord::class).forEach { type ->
            runCatching { c.deleteRecords(type, range) }
        }
    }

    companion object {
        val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        val READ_SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val READ_PERIODS = HealthPermission.getReadPermission(androidx.health.connect.client.records.MenstruationPeriodRecord::class)
        val WRITE = setOf(
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
        )
    }
}
