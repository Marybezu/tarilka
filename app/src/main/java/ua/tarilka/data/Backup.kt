package ua.tarilka.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Backup of everything the user entered, as one JSON file.
 * Built-in foods are referred to by their stable code, so a backup restores
 * correctly on a fresh install or a newer app version.
 */
object Backup {
    const val FORMAT = "tarilka-backup"
    /** 2: sleep, bowel, workouts, cycle and the cycle/steps settings; 3: waist. */
    const val VERSION = 3

    private const val ENCRYPTED = "tarilka-backup-encrypted"
    private const val ITERATIONS = 600_000

    /** AES-256-GCM with a key derived from the password (PBKDF2-HMAC-SHA256). */
    fun encrypt(plain: String, password: String): String {
        val rnd = java.security.SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), javax.crypto.spec.GCMParameterSpec(128, iv))
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getEncoder()
        return JSONObject().put("format", ENCRYPTED).put("version", 1)
            .put("kdf", "PBKDF2WithHmacSHA256").put("iterations", ITERATIONS)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(data)).toString(1)
    }

    fun isEncrypted(text: String): Boolean = runCatching { JSONObject(text).optString("format") == ENCRYPTED }.getOrDefault(false)

    /** Throws if the password is wrong (GCM checks integrity). */
    fun decrypt(text: String, password: String): String {
        val o = JSONObject(text)
        val b64 = java.util.Base64.getDecoder()
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            key(password, b64.decode(o.getString("salt")), o.getInt("iterations").also { require(it in 100_000..2_000_000) { "iterations" } }),
            javax.crypto.spec.GCMParameterSpec(128, b64.decode(o.getString("iv"))),
        )
        return String(c.doFinal(b64.decode(o.getString("data"))), Charsets.UTF_8)
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): javax.crypto.SecretKey {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return javax.crypto.spec.SecretKeySpec(raw, "AES")
    }

    data class Summary(val diary: Int, val fluids: Int, val weights: Int, val foods: Int)

    suspend fun export(db: AppDatabase, prefs: Prefs): String {
        val builtinCodes = db.foods().codes().associate { it.id to it.code }
        val custom = db.foods().customOnce()
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", Instant.now().toString())
            .put("profile", prefs.profile.value.let { p ->
                JSONObject().put("sex", p.sex.name).put("age", p.age).put("heightCm", p.heightCm)
                    .put("weightKg", p.weightKg).put("activity", p.activity).put("goal", p.goal.name)
                    .put("customKcal", p.customKcal ?: JSONObject.NULL).put("isSet", p.isSet).put("pregnant", p.pregnant)
            })
            .put("customFoods", JSONArray().apply {
                custom.forEach { f ->
                    put(
                        JSONObject().put("id", f.id).put("nameUk", f.nameUk).put("nameEn", f.nameEn)
                            .put("kcal", f.kcal).put("protein", f.protein).put("fat", f.fat)
                            .put("carbs", f.carbs).put("fiber", f.fiber)
                            .put("portionG", f.portionG ?: JSONObject.NULL).put("favorite", f.favorite)
                            .put("source", f.source).put("recipe", f.recipe ?: JSONObject.NULL),
                    )
                }
            })
            .put("favorites", JSONArray(db.foods().favoriteCodes()))
            .put("diary", JSONArray().apply {
                db.diary().allOnce().forEach { e ->
                    val o = JSONObject().put("day", LocalDate.ofEpochDay(e.day).toString()).put("meal", e.meal)
                        .put("nameUk", e.nameUk).put("nameEn", e.nameEn).put("grams", e.grams)
                        .put("kcal", e.kcal).put("protein", e.protein).put("fat", e.fat)
                        .put("carbs", e.carbs).put("fiber", e.fiber).put("fromAi", e.fromAi)
                        .put("createdAt", e.createdAt)
                    e.reason?.let { o.put("reason", it) }
                    e.hunger?.let { o.put("hunger", it) }
                    if (e.hardToStop) o.put("hardToStop", true)
                    e.foodId?.let { id ->
                        val code = builtinCodes[id]
                        if (code != null) o.put("foodCode", code) else o.put("customFoodId", id)
                    }
                    put(o)
                }
            })
            .put("fluids", JSONArray().apply {
                db.fluids().allOnce().forEach {
                    put(JSONObject().put("day", LocalDate.ofEpochDay(it.day).toString()).put("ml", it.ml).put("createdAt", it.createdAt))
                }
            })
            .put("notes", JSONArray().apply {
                db.notes().allOnce().forEach {
                    put(JSONObject().put("day", LocalDate.ofEpochDay(it.day).toString()).put("text", it.text))
                }
            })
            .put("sleep", JSONArray().apply {
                db.sleep().allOnce().forEach {
                    put(JSONObject().put("day", LocalDate.ofEpochDay(it.day).toString()).put("hours", it.hours).put("quality", it.quality ?: JSONObject.NULL))
                }
            })
            .put("bowel", JSONArray().apply {
                db.bowel().allOnce().forEach {
                    put(JSONObject().put("day", LocalDate.ofEpochDay(it.day).toString()).put("count", it.count).put("form", it.form ?: JSONObject.NULL))
                }
            })
            .put("workouts", JSONArray().apply {
                db.workouts().allOnce().forEach {
                    put(
                        JSONObject().put("day", LocalDate.ofEpochDay(it.day).toString()).put("type", it.type)
                            .put("minutes", it.minutes).put("intensity", it.intensity).put("createdAt", it.createdAt),
                    )
                }
            })
            .put("periods", JSONArray().apply {
                db.cycle().periodsOnce().forEach {
                    put(JSONObject().put("start", LocalDate.ofEpochDay(it.start).toString()).put("end", it.end?.let { e -> LocalDate.ofEpochDay(e).toString() } ?: JSONObject.NULL))
                }
            })
            .put("periodDays", JSONArray().apply {
                db.cycle().daysOnce().forEach {
                    put(JSONObject().put("day", LocalDate.ofEpochDay(it.day).toString()).put("flow", it.flow ?: JSONObject.NULL).put("pain", it.pain ?: JSONObject.NULL))
                }
            })
            .put("settings", JSONObject()
                .put("cycleTypical", prefs.cycleTypical.value)
                .put("stepLog", JSONObject(prefs.loggedSteps().mapKeys { it.key.toString() })))
            .put("waist", JSONArray().apply {
                db.waist().allOnce().forEach { put(JSONObject().put("day", LocalDate.ofEpochDay(it.day).toString()).put("cm", it.cm)) }
            })
            .put("weights", JSONArray().apply {
                db.weights().allOnce().forEach {
                    put(JSONObject().put("day", LocalDate.ofEpochDay(it.day).toString()).put("kg", it.kg).put("minutes", it.minutes ?: JSONObject.NULL))
                }
            })
        return root.toString(1)
    }

    /** Checks the file and counts what is inside, without changing anything. */
    fun peek(json: String): Summary {
        val o = JSONObject(json)
        require(o.optString("format") == FORMAT) { "not a Tarilka backup" }
        return Summary(
            o.optJSONArray("diary")?.length() ?: 0,
            o.optJSONArray("fluids")?.length() ?: 0,
            o.optJSONArray("weights")?.length() ?: 0,
            o.optJSONArray("customFoods")?.length() ?: 0,
        )
    }

    /** Replaces all user data with the backup. Built-in foods stay. */
    suspend fun restore(db: AppDatabase, prefs: Prefs, json: String): Summary {
        val o = JSONObject(json)
        require(o.optString("format") == FORMAT) { "not a Tarilka backup" }
        // A newer file may hold data this version would silently drop.
        require(o.optInt("version", 1) <= VERSION) { "made by a newer version of Tarilka: update the app first" }
        val summary = peek(json)
        db.withTransaction {
            db.clearDiary()
            db.foods().deleteCustom()

            val idMap = mutableMapOf<Long, Long>()
            o.optJSONArray("customFoods")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val uk = f.getString("nameUk")
                    val en = f.getString("nameEn")
                    val newId = db.foods().insert(
                        Food(
                            category = "custom", nameUk = uk, nameEn = en,
                            kcal = f.getDouble("kcal"), protein = f.getDouble("protein"), fat = f.getDouble("fat"),
                            carbs = f.getDouble("carbs"), fiber = f.optDouble("fiber", 0.0),
                            portionG = if (f.isNull("portionG")) null else f.getDouble("portionG"),
                            portionUk = if (f.isNull("portionG")) null else "порція",
                            portionEn = if (f.isNull("portionG")) null else "serving",
                            source = f.optString("source", "CUSTOM"), favorite = f.optBoolean("favorite"),
                            recipe = if (f.isNull("recipe")) null else f.optString("recipe"),
                        ),
                    )
                    idMap[f.getLong("id")] = newId
                }
            }
            // Recipes refer to own foods by id; ids change on restore, so remap them.
            if (idMap.isNotEmpty()) {
                db.foods().customOnce().filter { it.recipe != null }.forEach { f ->
                    val r = JSONObject(f.recipe!!)
                    val items = r.optJSONArray("items") ?: return@forEach
                    for (i in 0 until items.length()) {
                        val it = items.getJSONObject(i)
                        // An id we can't map (food deleted before export) must not point at another food.
                        if (it.has("id")) it.put("id", idMap[it.getLong("id")] ?: -1L)
                    }
                    db.foods().update(f.copy(recipe = r.toString()))
                }
            }
            val favs = o.optJSONArray("favorites")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            db.foods().setFavorites(favs)

            val byCode = db.foods().codes().associate { it.code to it.id }
            o.optJSONArray("diary")?.let { arr ->
                db.diary().insertAll((0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    val foodId = when {
                        e.has("foodCode") -> byCode[e.getString("foodCode")]
                        e.has("customFoodId") -> idMap[e.getLong("customFoodId")]
                        else -> null
                    }
                    DiaryEntry(
                        day = LocalDate.parse(e.getString("day")).toEpochDay(), meal = e.getInt("meal").coerceIn(0, 3),
                        foodId = foodId, nameUk = e.getString("nameUk"), nameEn = e.getString("nameEn"),
                        grams = e.getDouble("grams"), kcal = e.getDouble("kcal"), protein = e.getDouble("protein"),
                        fat = e.getDouble("fat"), carbs = e.getDouble("carbs"), fiber = e.optDouble("fiber", 0.0),
                        fromAi = e.optBoolean("fromAi"), createdAt = e.optLong("createdAt", System.currentTimeMillis()),
                        // Values out of range (an edited file, a newer version) are dropped, never shown.
                        reason = e.optInt("reason", -1).takeIf { it in Reasons.ALL },
                        hunger = e.optInt("hunger", -1).takeIf { it in 1..5 },
                        hardToStop = e.optBoolean("hardToStop"),
                    )
                })
            }
            o.optJSONArray("fluids")?.let { arr ->
                db.fluids().insertAll((0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    val day = LocalDate.parse(e.getString("day"))
                    val noon = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    FluidEntry(day = day.toEpochDay(), ml = e.getDouble("ml"), createdAt = e.optLong("createdAt", noon).takeIf { it > 0 } ?: noon)
                })
            }
            o.optJSONArray("notes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    db.notes().upsert(DayNote(LocalDate.parse(e.getString("day")).toEpochDay(), e.getString("text")))
                }
            }
            o.optJSONArray("sleep")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    db.sleep().upsert(
                        SleepEntry(LocalDate.parse(e.getString("day")).toEpochDay(), e.getDouble("hours"), e.optInt("quality", -1).takeIf { it in 0..2 }),
                    )
                }
            }
            o.optJSONArray("bowel")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    db.bowel().upsert(
                        BowelEntry(LocalDate.parse(e.getString("day")).toEpochDay(), e.getInt("count").coerceIn(0, 3), e.optInt("form", -1).takeIf { it in 0..2 }),
                    )
                }
            }
            o.optJSONArray("workouts")?.let { arr ->
                db.workouts().insertAll((0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    WorkoutEntry(
                        day = LocalDate.parse(e.getString("day")).toEpochDay(),
                        type = e.optInt("type", Workouts.TYPES.size - 1).coerceIn(0, Workouts.TYPES.size - 1),
                        minutes = e.optInt("minutes", 30).coerceIn(1, 600),
                        intensity = e.optInt("intensity", 1).coerceIn(0, 2),
                        createdAt = e.optLong("createdAt", System.currentTimeMillis()),
                    )
                })
            }
            o.optJSONArray("periods")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    db.cycle().upsert(
                        PeriodEntry(LocalDate.parse(e.getString("start")).toEpochDay(), if (e.isNull("end")) null else LocalDate.parse(e.getString("end")).toEpochDay()),
                    )
                }
            }
            o.optJSONArray("periodDays")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    db.cycle().upsertDay(
                        PeriodDay(LocalDate.parse(e.getString("day")).toEpochDay(), e.optInt("flow", -1).takeIf { it in 0..2 }, e.optInt("pain", -1).takeIf { it in 0..2 }),
                    )
                }
            }
            o.optJSONArray("waist")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val cm = e.optDouble("cm", -1.0)
                    if (cm in 40.0..250.0) db.waist().upsert(WaistEntry(LocalDate.parse(e.getString("day")).toEpochDay(), cm))
                }
            }
            o.optJSONArray("weights")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    db.weights().upsert(WeightEntry(LocalDate.parse(e.getString("day")).toEpochDay(), e.getDouble("kg"), e.optInt("minutes", -1).takeIf { it in 0..1439 }))
                }
            }
        }
        // The cycle settings belong to the replaced cycle records.
        prefs.clearCycle()
        o.optJSONObject("settings")?.let { st ->
            st.optInt("cycleTypical", -1).let { prefs.setCycleTypical(if (it in 15..60) it else -1) }
            st.optJSONObject("stepLog")?.let { log ->
                log.keys().forEach { k -> k.toLongOrNull()?.let { d -> log.optLong(k, -1).takeIf { it in 0..200_000 }?.let { prefs.logSteps(d, it) } } }
            }
        }
        o.optJSONObject("profile")?.let { p ->
            if (p.optBoolean("isSet")) {
                prefs.saveProfile(
                    Profile(
                        sex = runCatching { Sex.valueOf(p.getString("sex")) }.getOrDefault(Sex.FEMALE),
                        // Values out of range (a damaged file) fall back to the defaults.
                        age = p.optInt("age", 30).takeIf { it in 14..100 } ?: 30,
                        heightCm = p.optInt("heightCm", 165).takeIf { it in 100..250 } ?: 165,
                        weightKg = p.optDouble("weightKg", 65.0).takeIf { it in 25.0..350.0 } ?: 65.0,
                        activity = p.optInt("activity", 1).coerceIn(0, 4),
                        goal = runCatching { Goal.valueOf(p.getString("goal")) }.getOrDefault(Goal.MAINTAIN),
                        customKcal = if (p.isNull("customKcal")) null else p.optInt("customKcal"),
                        pregnant = p.optBoolean("pregnant", false),
                    ),
                )
            }
        }
        return summary
    }

    /** The diary as a spreadsheet-friendly CSV (one row per eaten item). */
    suspend fun diaryCsv(db: AppDatabase, ukrainian: Boolean): String {
        val zone = ZoneId.systemDefault()
        val header = if (ukrainian) {
            "дата;час;прийом;продукт;грами;ккал;білки;жири;вуглеводи;клітковина;ШІ;чому;голод 1-5;важко зупинитися"
        } else {
            "date;time;meal;food;grams;kcal;protein;fat;carbs;fiber;ai;why;hunger 1-5;hard to stop"
        }
        val reasons = if (ukrainian) listOf("голод", "стрес", "нудьга", "компанія", "звичка") else Reasons.EN
        val meals = if (ukrainian) listOf("сніданок", "обід", "вечеря", "перекус") else listOf("breakfast", "lunch", "dinner", "snack")
        fun n(d: Double) = String.format(java.util.Locale.ROOT, "%.1f", d)
        val sb = StringBuilder("﻿").append(header).append('\n')
        db.diary().allOnce().forEach { e ->
            val time = Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalTime().withNano(0).withSecond(0)
            val name = (if (ukrainian) e.nameUk else e.nameEn).replace(';', ',')
            sb.append(LocalDate.ofEpochDay(e.day)).append(';').append(time).append(';')
                .append(meals.getOrElse(e.meal) { "" }).append(';').append(name).append(';')
                .append(n(e.grams)).append(';').append(n(e.kcal)).append(';').append(n(e.protein)).append(';')
                .append(n(e.fat)).append(';').append(n(e.carbs)).append(';').append(n(e.fiber)).append(';')
                .append(if (e.fromAi) 1 else 0).append(';')
                .append(e.reason?.let { reasons.getOrNull(it) } ?: "").append(';')
                .append(e.hunger ?: "").append(';')
                .append(if (e.hardToStop) 1 else "").append('\n')
        }
        return sb.toString()
    }
}
