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
        assertEquals(1950.0, t.waterMl, 0.01)
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
        assertTrue(p.parseFoods("I see a plate").isEmpty())
    }
}
