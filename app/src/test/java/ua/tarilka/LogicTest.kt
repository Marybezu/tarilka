package ua.tarilka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.tarilka.ai.FoodMatcher
import ua.tarilka.data.FoodSeeder
import ua.tarilka.data.Goal
import ua.tarilka.data.Nutrition
import ua.tarilka.data.Profile
import ua.tarilka.data.Sex
import ua.tarilka.data.WeightEntry
import ua.tarilka.data.aiSafety
import java.io.File

class LogicTest {
    private val foods by lazy {
        val f = listOf("src/main/assets/foods.csv", "app/src/main/assets/foods.csv").map(::File).first { it.exists() }
        FoodSeeder.parse(f.readLines())
    }

    @Test
    fun databaseParses() {
        assertTrue("expected 200+ foods, got ${foods.size}", foods.size > 200)
        assertEquals(foods.size, foods.map { it.code }.toSet().size)
        val buckwheat = foods.first { it.code == "g01" }
        assertEquals("Гречка варена", buckwheat.nameUk)
        assertEquals(92.0, buckwheat.kcal, 0.01)
        assertEquals(200.0, buckwheat.portionG!!, 0.01)
    }

    @Test
    fun matcherFindsCommonFoods() {
        fun en(q: String, uk: String = "") = FoodMatcher.best(q, uk, foods)?.food?.code
        assertEquals("g01", en("Buckwheat, cooked"))
        assertEquals("m01", en("chicken breast", "куряча грудка"))
        assertEquals("u01", en("borscht", "борщ"))
        assertEquals("u08", en("dumplings with potato", "вареники з картоплею"))
        assertEquals("v15", en("tomato"))
        assertNull(FoodMatcher.best("xylophone", "ксилофон", foods))
        // Photo answers seen on a real phone
        assertEquals("v23", en("corn"))
        assertEquals("v26", en("mushrooms"))
        assertEquals("d15", en("cheese"))
        assertEquals("u13", en("hash browns"))
        assertEquals("e04", en("scrambled eggs"))
        assertEquals("v71", en("spinach, cooked"))
        assertEquals("o04", en("corn oil"))
        assertEquals("u69", en("breaded chicken"))
        assertEquals("e04", en("omelet"))
        assertEquals("m13", en("meat pieces"))
        assertEquals("e03", en("fried eggs"))
        assertEquals("v21", en("greens"))
        assertEquals("dr14", en("green tea"))
    }

    @Test
    fun targetsAreSane() {
        val p = Profile(Sex.FEMALE, 30, 165, 65.0, 1, Goal.LOSE)
        val t = Nutrition.targets(p, null)
        // Mifflin–St Jeor: 10*65 + 6.25*165 - 5*30 - 161 = 1370.25
        assertEquals(1370.25, t.bmr, 0.01)
        assertTrue(t.kcal >= t.bmr)
        assertEquals(104.0, t.protein, 0.01)
        val tiny = Nutrition.targets(p.copy(customKcal = null, weightKg = 45.0, activity = 0), null)
        assertTrue("never below the safety floor", tiny.kcal >= 1200)
        assertEquals(1600.0, t.waterMl, 0.01)
    }

    @Test
    fun weeklyTrend() {
        val pts = (0..14).map { WeightEntry(it.toLong(), 70.0 - it * 0.1) }
        val tr = Nutrition.weeklyTrend(pts)
        assertNotNull(tr)
        assertEquals(-0.7, tr!!, 0.001)
    }

    @Test
    fun backupEncryption() {
        val plain = """{"format":"tarilka-backup","diary":[]}"""
        val enc = ua.tarilka.data.Backup.encrypt(plain, "пароль-123")
        assertTrue(ua.tarilka.data.Backup.isEncrypted(enc))
        assertTrue(!enc.contains("diary"))
        assertEquals(plain, ua.tarilka.data.Backup.decrypt(enc, "пароль-123"))
        val wrong = runCatching { ua.tarilka.data.Backup.decrypt(enc, "інший") }
        assertTrue("wrong password must fail", wrong.isFailure)
    }

    @Test
    fun recipePer100() {
        val buckwheatDry = foods.first { it.code == "g02" } // 346 kcal
        val butter = foods.first { it.code == "d18" } // 748 kcal
        val items = listOf(ua.tarilka.data.RecipeItem(buckwheatDry, 100.0), ua.tarilka.data.RecipeItem(butter, 10.0))
        // Raw weight 110 g, but cooked with water to 300 g.
        val t = ua.tarilka.data.Recipe.per100(items, 300.0)
        assertEquals((346.0 + 74.8) / 300 * 100, t.kcal, 0.01)
        val json = ua.tarilka.data.Recipe.encode(items, 300.0)
        val (back, cooked) = ua.tarilka.data.Recipe.decode(json, foods)
        assertEquals(2, back.size)
        assertEquals(300.0, cooked!!, 0.01)
    }

    @Test
    fun parsesModelAnswers() {
        val p = ua.tarilka.ai.GemmaEngine.Companion
        val obj = """{"items":[{"name_en":"fried egg","name_uk":"яєчня","grams":120}]}"""
        assertEquals(1, p.parseFoods(obj).size)
        val fenced = "```json\n[{\"name_en\":\"toast\",\"name_uk\":\"тост\",\"grams\":60}]\n```"
        assertEquals("toast", p.parseFoods(fenced).single().nameEn)
        // An answer cut off in the middle still gives the complete items.
        val cut = """{"items":[{"name_en":"spinach","name_uk":"шпинат","grams":80},{"name_en":"mushro"""
        assertEquals("spinach", p.parseFoods(cut).single().nameEn)
        // The current prompt answers with English names only.
        val short = """{"items":[{"name":"boiled egg","grams":100,"confidence":"high"},{"name":"corn","grams":50}]}"""
        assertEquals(listOf("boiled egg", "corn"), p.parseFoods(short).map { it.nameEn })
        assertTrue(p.parseFoods("I see a plate").isEmpty())
    }

    @Test
    fun russianIsCaught() {
        val l = ua.tarilka.data.Lang
        assertTrue(l.looksRussian("рыба, запечена на грилі"))
        assertTrue(l.looksRussian("Мясо и овощи"))
        assertTrue(l.looksRussian("оливкову або подсолнечную олію"))
        assertTrue(l.hasForeignScript("подсолचन"))
        assertTrue(l.isClean("Додайте більше овочів і цільнозернового хліба.", ukrainian = true))
        assertTrue(!l.looksRussian("Халумі на грилі, оливки й огірок"))
        assertTrue(!l.looksRussian("Їжте більше білка і клітковини"))
        assertEquals("grilled fish", l.safeUk("рыба на гриле", "grilled fish"))
    }

    @Test
    fun habitsAreCounted() {
        fun food(id: Long, code: String, cat: String, kcal: Double) =
            ua.tarilka.data.Food(id = id, code = code, category = cat, nameUk = code, nameEn = code, kcal = kcal, protein = 0.0, fat = 0.0, carbs = 0.0, fiber = 0.0, source = "USDA")
        val foods = listOf(food(1, "f01", "fruit", 50.0), food(2, "s01", "sweets", 500.0), food(3, "v01", "veg", 80.0), food(4, "dr07", "drinks", 85.0))
            .associateBy { it.id }
        val zone = java.time.ZoneOffset.UTC
        fun at(day: Long, hour: Int) = java.time.LocalDate.ofEpochDay(day).atTime(hour, 0).toInstant(zone).toEpochMilli()
        fun e(day: Long, foodId: Long, grams: Double, hour: Int = 12) = ua.tarilka.data.DiaryEntry(
            day = day, meal = 0, foodId = foodId, nameUk = "", nameEn = "", grams = grams,
            kcal = foods.getValue(foodId).kcal * grams / 100, protein = 0.0, fat = 0.0, carbs = 0.0, fiber = 0.0, createdAt = at(day, hour),
        )
        val h = ua.tarilka.data.Habits.of(
            listOf(e(1, 1, 400.0), e(1, 3, 500.0), e(2, 2, 40.0, hour = 23), e(2, 4, 200.0)),
            foods, mapOf("f01" to ua.tarilka.data.FoodGroup.VEG_FRUIT, "s01" to ua.tarilka.data.FoodGroup.SWEETS), 7, zone,
        )
        assertEquals(2, h.days)
        assertEquals(1, h.sweetFreeDays)
        assertEquals(200.0, h.vegFruitG, 0.01) // potatoes are not counted
        assertEquals(200.0 / (200 + 400 + 200 + 170) * 100, h.latePct, 0.01)
        assertEquals(2.12, h.alcoholPerWeek, 0.01) // 200 g of dry red wine = 21.2 g of alcohol
        assertEquals(ua.tarilka.data.Habits.Level.WATCH, h.sweetsLevel)
    }

    @Test
    fun reasonsAndSleepAreCounted() {
        fun e(day: Long, meal: Int, reason: Int?, hard: Boolean = false) = ua.tarilka.data.DiaryEntry(
            day = day, meal = meal, foodId = null, nameUk = "", nameEn = "", grams = 100.0, kcal = 100.0,
            protein = 0.0, fat = 0.0, carbs = 0.0, fiber = 0.0, reason = reason, hardToStop = hard,
        )
        val r = ua.tarilka.data.Reasons
        // Two items added together for one meal count as one answer.
        val list = listOf(e(1, 0, r.HUNGER), e(1, 2, r.STRESS), e(1, 2, r.STRESS), e(2, 3, r.BOREDOM, hard = true), e(2, 1, null))
        val h = ua.tarilka.data.Habits.of(list, emptyMap(), emptyMap(), 7, sleep = mapOf(1L to 6.0, 2L to 7.0))
        assertEquals(3, h.reasonMeals)
        assertEquals(2, h.notHungerTotal)
        assertEquals(1, h.hardToStop)
        assertEquals(6.5, h.sleepAvg!!, 0.001)
        assertEquals(ua.tarilka.data.Habits.Level.OK, h.sleepLevel)
    }

    @Test
    fun alcoholLevels() {
        val wine = ua.tarilka.data.Food(id = 1, code = "dr07", category = "drinks", nameUk = "", nameEn = "", kcal = 85.0, protein = 0.0, fat = 0.0, carbs = 0.0, fiber = 0.0, source = "USDA")
        val foods = mapOf(1L to wine)
        fun glass(day: Long, ml: Double) = ua.tarilka.data.DiaryEntry(
            day = day, meal = 2, foodId = 1, nameUk = "", nameEn = "", grams = ml, kcal = 0.85 * ml,
            protein = 0.0, fat = 0.0, carbs = 0.0, fiber = 0.0,
        )
        // Two glasses a week: some alcohol, amber.
        assertEquals(ua.tarilka.data.Habits.Level.OK, ua.tarilka.data.Habits.of(listOf(glass(1, 150.0), glass(3, 150.0)), foods, emptyMap(), 7).alcoholLevel)
        // One evening with a bottle (750 ml ≈ 8 drinks): a heavy day, orange.
        val bottle = ua.tarilka.data.Habits.of(listOf(glass(1, 750.0)), foods, emptyMap(), 7)
        assertEquals(1, bottle.heavyDrinkDays)
        assertEquals(ua.tarilka.data.Habits.Level.WATCH, bottle.alcoholLevel)
        // A small glass on 5 days: frequent, orange.
        assertEquals(ua.tarilka.data.Habits.Level.WATCH, ua.tarilka.data.Habits.of((1L..5L).map { glass(it, 100.0) }, foods, emptyMap(), 7).alcoholLevel)
    }

    @Test
    fun bowelAndWorkouts() {
        val e = ua.tarilka.data.DiaryEntry(day = 1, meal = 0, foodId = null, nameUk = "", nameEn = "", grams = 100.0, kcal = 100.0, protein = 0.0, fat = 0.0, carbs = 0.0, fiber = 0.0)
        val food = (1L..7L).map { e.copy(day = it) }
        val bowel = listOf(ua.tarilka.data.BowelEntry(1, 1, 1), ua.tarilka.data.BowelEntry(3, 0), ua.tarilka.data.BowelEntry(5, 1, 0))
        val W = ua.tarilka.data.WorkoutEntry(day = 1, type = 0, minutes = 30, intensity = 1)
        val workouts = listOf(W, W.copy(day = 3, type = 1, intensity = 2), W.copy(day = 5, type = 3, minutes = 40), W.copy(day = 6, intensity = 0))
        val h = ua.tarilka.data.Habits.of(food, emptyMap(), emptyMap(), 7, bowel = bowel, workouts = workouts)
        assertEquals(2, h.bowelMoveDays)
        assertEquals(1, h.bowelHard)
        // 2 movements in 3 recorded days ≈ 4.7 a week: fine, but one hard day → amber
        assertEquals(ua.tarilka.data.Habits.Level.OK, h.bowelLevel)
        // 30 + 30×2 + 40 = 130 WHO minutes; the light walk does not count
        assertEquals(130.0, h.activeMinPerWeek, 0.01)
        assertEquals(1.0, h.strengthPerWeek, 0.01)
        assertEquals(ua.tarilka.data.Habits.Level.OK, h.activityLevel)
    }

    @Test
    fun cycleIsHonest() {
        fun p(start: Long, end: Long? = null) = ua.tarilka.data.PeriodEntry(start, end)
        // Starts 28 and 30 days apart; then a forgotten month (70 days) that must not count as a cycle.
        val c = ua.tarilka.data.CycleInfo.of(listOf(p(0, 4), p(28, 32), p(58, 62), p(128)), emptyList(), today = 135)
        assertEquals(listOf(28, 30), c.lengths)
        assertEquals(30, c.avgLength)
        assertEquals(158L, c.nextStart)
        assertEquals(144L, c.ovulation)
        assertEquals(8, c.cycleDay(135))
        assertTrue(!c.seeDoctor)
        // Lengths 22, 36, 25: irregular, so no ovulation estimate.
        val irr = ua.tarilka.data.CycleInfo.of(listOf(p(0), p(22), p(58), p(83)), emptyList(), today = 90)
        assertTrue(irr.irregular)
        assertEquals(null, irr.ovulation)
        // A marked 9-day period is worth a doctor's look; an unmarked end is not.
        assertTrue(ua.tarilka.data.CycleInfo.of(listOf(p(0, 8)), emptyList(), 20).longBleeding)
        assertTrue(!ua.tarilka.data.CycleInfo.of(listOf(p(0)), emptyList(), 9).longBleeding)
        // One start plus the usual length from setup: a date, but no ovulation guess yet.
        val first = ua.tarilka.data.CycleInfo.of(listOf(p(100, 104)), emptyList(), 110, typical = 29)
        assertEquals(129L, first.nextStart)
        assertTrue(first.fromTypical)
        assertEquals(null, first.ovulation)
    }

    @Test
    fun typedListIsRead() {
        val t = ua.tarilka.data.TextMeal
        val a = t.parse("квашена капустка 100 г варена картопля 40 г котлета 50 г")
        assertEquals(listOf("квашена капустка", "варена картопля", "котлета"), a.map { it.name })
        assertEquals(listOf(100.0, 40.0, 50.0), a.map { it.amount })
        val b = t.parse("2 котлети, хліб 40 г; кефір 0,5 л, яблуко")
        assertEquals("котлети", b[0].name)
        assertEquals(ua.tarilka.data.TextMeal.Unit.PIECES, b[0].unit)
        assertEquals(2.0, b[0].amount!!, 0.01)
        assertEquals(500.0, b[2].amount!!, 0.01)
        assertEquals(null, b[3].amount)
        assertTrue(t.looksLikeList("котлета 50 г"))
        assertTrue(!t.looksLikeList("котлета"))
        // Counting words and fractions do not become stray items.
        val c = t.parse("хліб 2 скибки, 1/2 банана, сир 100 грамів, молоко 1 склянка")
        assertEquals(listOf("хліб", "банана", "сир", "молоко"), c.map { it.name })
        assertEquals(2.0, c[0].amount!!, 0.01)
        assertEquals(ua.tarilka.data.TextMeal.Unit.PIECES, c[0].unit)
        assertEquals(0.5, c[1].amount!!, 0.01)
        assertEquals(100.0, c[2].amount!!, 0.01)
        assertEquals(250.0, c[3].amount!!, 0.01)
        // No comma between two foods: each keeps its own amount when both parts are foods.
        val known = mapOf("котлети" to 0.6, "гречка" to 0.6, "яйця" to 0.6, "варені яйця" to 0.9)
        val m: (String) -> Double = { known[it] ?: 0.0 }
        val d = t.parse("2 котлети гречка 200 г", m)
        assertEquals(listOf("котлети", "гречка"), d.map { it.name })
        assertEquals(listOf(2.0, 200.0), d.map { it.amount })
        assertEquals(ua.tarilka.data.TextMeal.Unit.PIECES, d[0].unit)
        // Words that are one food together stay together.
        val eggs = mapOf("варені яйця" to 0.9, "гречка" to 0.6, "варені" to 0.2)
        assertEquals(listOf("варені яйця", "гречка"), t.parse("2 варені яйця гречка 100 г") { eggs[it] ?: 0.0 }.map { it.name })
        // The same with the real database and matcher, as the app does it.
        fun real(s: String) = t.parse(s) { FoodMatcher.best(it, it, foods)?.score ?: 0.0 }.map { it.name }
        assertEquals(listOf("котлети", "гречка"), real("2 котлети гречка 200 г"))
        assertEquals(listOf("житній хліб"), real("2 житній хліб 50 г").take(1))
        assertEquals(listOf("млинці зі сметаною"), real("3 млинці зі сметаною 100 г").take(1))
    }

    @Test
    fun noDietWhenNotSafe() {
        val adult = Profile(Sex.FEMALE, 30, 165, 65.0, 1, Goal.LOSE)
        val n = Nutrition.targets(adult, null)
        assertEquals(Goal.LOSE, n.goal)
        assertEquals(null, n.limit)
        // Own target below the floor is raised to it.
        val low = Nutrition.targets(adult.copy(customKcal = 800), null)
        assertTrue(low.customRaised)
        assertTrue(low.kcal >= 1200)
        // Pregnancy, under 18 and BMI under 18.5: no deficit at all.
        for (p in listOf(adult.copy(pregnant = true), adult.copy(age = 16), adult.copy(weightKg = 48.0))) {
            val t = Nutrition.targets(p, null)
            assertEquals(Goal.MAINTAIN, t.goal)
            assertTrue(t.limit != null)
            assertEquals(t.tdee + if (p.pregnant) 300.0 else 0.0, t.kcal, 0.01)
        }
        // Under 18 no calorie number at all; adults see it.
        assertTrue(!Nutrition.targets(adult.copy(age = 16), null).showKcal)
        assertTrue(!Nutrition.targets(adult.copy(age = 16, pregnant = true), null).showKcal)
        assertTrue(n.showKcal)
        // Protein with obesity: from the weight at BMI 25, not the actual weight.
        val big = Nutrition.targets(adult.copy(weightKg = 140.0), null)
        assertEquals(1.6 * 25 * 1.65 * 1.65, big.protein, 0.5)
        assertEquals(1, Nutrition.bmiClass(22.0))
        assertEquals(3, Nutrition.bmiClass(31.0))
    }

    @Test
    fun reportIsConsistent() {
        fun h(days: Int, veg: Double, sweets: Double) = ua.tarilka.data.Habits(
            days = days, periodDays = 7, kcalAvg = 1500.0, proteinAvg = 90.0, fiberAvg = 20.0, sweetsPct = sweets,
            sweetFreeDays = 0, vegFruitG = veg, latePct = 5.0, alcoholPerWeek = 0.0, fastFoodDays = 0,
        )
        val t = Nutrition.targets(Profile(Sex.FEMALE, 30, 165, 65.0, 1, Goal.MAINTAIN), null)
        val cur = ua.tarilka.data.PeriodData(h(7, 450.0, 12.0), fluidMl = null, steps = null)
        val prev = ua.tarilka.data.PeriodData(h(7, 250.0, 6.0), fluidMl = null, steps = null)
        val m = ua.tarilka.data.Report.build(cur, prev, listOf(cur), t, 8000).associateBy { it.key }
        val veg = m.getValue(ua.tarilka.data.Metric.Key.VEG)
        assertEquals(1, veg.change)
        assertEquals(ua.tarilka.data.Habits.Level.GOOD, veg.level)
        // More sweets than before is worse, and over 10 % is orange.
        val sweets = m.getValue(ua.tarilka.data.Metric.Key.SWEETS)
        assertEquals(-1, sweets.change)
        assertEquals(ua.tarilka.data.Habits.Level.WATCH, sweets.level)
        val hl = ua.tarilka.data.Report.highlights(m.values.toList())
        assertEquals(ua.tarilka.data.Metric.Key.VEG, hl.improved?.key)
        assertEquals(ua.tarilka.data.Metric.Key.SWEETS, hl.attention?.key)
        // Two days say too little: no lines at all.
        assertTrue(ua.tarilka.data.Report.build(ua.tarilka.data.PeriodData(h(2, 450.0, 1.0), null, null), null, emptyList(), t, 8000).isEmpty())
    }

    @Test
    fun adviceFollowsTheRules() {
        val sausage = ua.tarilka.data.Food(id = 1, code = "m17", category = "meat", nameUk = "Сосиски", nameEn = "Frankfurters", kcal = 290.0, protein = 11.0, fat = 26.0, carbs = 2.0, fiber = 0.0, source = "USDA")
        val apple = ua.tarilka.data.Food(id = 2, code = "f01", category = "fruit", nameUk = "Яблуко", nameEn = "Apple", kcal = 52.0, protein = 0.3, fat = 0.2, carbs = 14.0, fiber = 2.4, source = "USDA")
        val foods = mapOf(1L to sausage, 2L to apple)
        val groups = mapOf("m17" to ua.tarilka.data.FoodGroup.PROCESSED_MEAT, "f01" to ua.tarilka.data.FoodGroup.VEG_FRUIT)
        val entries = (0L until 7L).flatMap { d ->
            listOfNotNull(
                if (d < 4) ua.tarilka.data.DiaryEntry.of(sausage, 100.0, d, 1) else null,
                ua.tarilka.data.DiaryEntry.of(apple, 150.0, d, 3),
            )
        }
        val r = ua.tarilka.data.Advice.of(entries, foods, groups, 7, mayReduce = true)
        assertTrue(r.change.any { it.rule == ua.tarilka.data.Advice.Rule.PROCESSED_MEAT })
        assertTrue(r.change.size <= 2)
        // Someone who should not eat less only gets "more of" suggestions.
        val safe = ua.tarilka.data.Advice.of(entries, foods, groups, 7, mayReduce = false)
        assertTrue(safe.change.none { it.rule.reduce })
        // Two days say too little.
        assertTrue(ua.tarilka.data.Advice.of(entries.filter { it.day < 2 }, foods, groups, 2, true).change.isEmpty())
    }

    @Test
    fun passwordStrengthIsSensible() {
        assertEquals(0, ua.tarilka.ui.passwordStrength("qwerty12"))
        assertEquals(1, ua.tarilka.ui.passwordStrength("Tarilka2024"))
        assertEquals(2, ua.tarilka.ui.passwordStrength("кіт сонце ложка вікно"))
        assertEquals(2, ua.tarilka.ui.passwordStrength("Xk9!pQ2#vL7z"))
    }

    @Test
    fun fibreTargetStaysInRange() {
        val small = Nutrition.targets(Profile(Sex.FEMALE, 30, 160, 55.0, 0, Goal.MAINTAIN), null)
        assertEquals(25.0, small.fiber, 0.01)
        val big = Nutrition.targets(Profile(Sex.MALE, 25, 190, 95.0, 4, Goal.GAIN), null)
        assertTrue("fibre is capped at 35 g, got ${big.fiber}", big.fiber <= 35.0)
    }

    @Test
    fun caloriesInAiTextAreCaught() {
        assertTrue(ua.tarilka.data.Lang.mentionsCalories("Це приблизно 600 ккал."))
        assertTrue(ua.tarilka.data.Lang.mentionsCalories("Менше калорій увечері"))
        assertTrue(ua.tarilka.data.Lang.mentionsCalories("About 600 kcal"))
        assertTrue(!ua.tarilka.data.Lang.mentionsCalories("Додайте до вечері тарілку овочів."))
        // The under-18 safety text carries the mark that turns the check on.
        val teen = Nutrition.targets(Profile(Sex.FEMALE, 16, 160, 50.0, 1, Goal.MAINTAIN), null)
        assertTrue(ua.tarilka.data.Lang.NO_KCAL_MARK in teen.aiSafety())
        assertTrue("no alcohol for teens", "no alcohol" in teen.aiSafety())
    }
}
