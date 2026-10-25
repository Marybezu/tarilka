package ua.tarilka.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
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
        val t = if (day == LocalDate.now()) Instant.now() else day.atTime(8, 0).atZone(zone).toInstant()
        runCatching {
            c.insertRecords(
                listOf(WeightRecord(t, zone.rules.getOffset(t), Mass.kilograms(w.kg), Metadata.manualEntry(clientRecordId = "weight-${w.day}"))),
            )
        }
    }

    companion object {
        val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        val WRITE = setOf(
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
        )
    }
}
