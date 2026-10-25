package ua.tarilka.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** One food the model saw on the plate. Nutrients are the model's own estimate (fallback only). */
data class DetectedFood(
    val nameEn: String,
    val nameUk: String,
    val grams: Double,
    val kcal100: Double?,
    val protein100: Double?,
    val fat100: Double?,
    val carbs100: Double?,
    val confidence: String,
)

/** Runs Gemma 4 fully on the phone via LiteRT-LM. Nothing leaves the device. */
class GemmaEngine(private val context: Context, private val models: ModelStore) {
    private var engine: Engine? = null
    private var loadedPath: String? = null

    /** One user of the native engine at a time: loading, inference and release never overlap. */
    private val mutex = Mutex()

    /** Must be called with [mutex] held. */
    private fun engineLocked(): Engine {
        val path = models.modelFile()?.absolutePath ?: error("Model not installed")
        engine?.takeIf { loadedPath == path }?.let { return it }
        engine?.close()
        engine = null
        val created = runCatching { create(path, gpu = true) }.getOrElse { create(path, gpu = false) }
        engine = created
        loadedPath = path
        return created
    }

    private fun create(path: String, gpu: Boolean): Engine {
        val b = if (gpu) Backend.GPU() else Backend.CPU()
        val config = EngineConfig(
            modelPath = path,
            backend = b,
            visionBackend = if (gpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = 8192,
            cacheDir = context.cacheDir.absolutePath,
        )
        return Engine(config).also { it.initialize() }
    }

    private suspend fun <T> withEngine(block: (Engine) -> T): T =
        mutex.withLock { withContext(Dispatchers.Default) { block(engineLocked()) } }

    /** Frees the model's memory; waits for a running analysis to finish first. */
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            loadedPath = null
        }
    }

    suspend fun analyzePhoto(image: File, note: String?, knownNames: List<String>): List<DetectedFood> {
        val text = withEngine { e ->
            e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_PHOTO),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                ),
            ).use { conv ->
                val prompt = buildString {
                    append(PHOTO_PROMPT)
                    if (knownNames.isNotEmpty()) {
                        append("\nWhen a food matches one of these database names, copy that name exactly into name_en: ")
                        append(knownNames.joinToString("; "))
                    }
                    if (!note.isNullOrBlank()) append("\nThe user says about this meal: ").append(note.trim())
                }
                conv.sendMessage(Contents.of(Content.ImageFile(image.absolutePath), Content.Text(prompt))).toString()
            }
        }
        return parseFoods(text)
    }

    /** Short, friendly advice based on the day's numbers. */
    suspend fun advice(summary: String, ukrainian: Boolean): String = withEngine { e ->
        e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(if (ukrainian) SYSTEM_ADVICE_UK else SYSTEM_ADVICE_EN),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6),
            ),
        ).use { conv -> conv.sendMessage(summary).toString().trim() }
    }

    companion object {
        private const val SYSTEM_PHOTO =
            "You are a careful nutrition assistant. You identify foods in meal photos and estimate portion sizes. " +
                "You always answer with valid JSON only."

        private const val PHOTO_PROMPT = """Look at this photo of a meal. List every distinct food or dish you can see.
For each one estimate the edible weight in grams. Use the plate (a dinner plate is about 26 cm wide), bowls, cutlery and hands for scale.
Prefer simple, common names, e.g. "buckwheat, cooked", "chicken breast, cooked", "borscht", "tomato", "rye bread".
Also give your best estimate of nutrients per 100 g.
Answer with ONLY a JSON array, no other text, in this exact form:
[{"name_en":"...","name_uk":"... (in Ukrainian)","grams":150,"kcal_per_100g":120,"protein_per_100g":5,"fat_per_100g":3,"carbs_per_100g":18,"confidence":"high"}]
confidence is "high", "medium" or "low". If there is no food in the photo answer [] ."""

        private const val SYSTEM_ADVICE_UK =
            "Ти доброзичливий помічник з харчування. Відповідай українською, коротко: 2–4 пункти, кожен одним реченням. " +
                "Враховуй, що і коли людина їла, скільки випила і скільки пройшла кроків. Давай практичні поради з конкретними продуктами. Не став діагнозів, не радь голодування чи екстремальних дієт, " +
                "не засуджуй. Якщо цифри виглядають небезпечно (дуже мало їжі або дуже швидка втрата ваги), м'яко порадь звернутися до лікаря."

        private const val SYSTEM_ADVICE_EN =
            "You are a friendly nutrition helper. Answer in English, briefly: 2–4 bullet points, one sentence each. " +
                "Take into account what and when the person ate, how much they drank and how many steps they walked. Give practical suggestions with concrete foods. Do not diagnose, do not suggest fasting or extreme diets, " +
                "do not judge. If the numbers look unsafe (very low intake or very fast weight loss), gently suggest seeing a doctor."

        fun parseFoods(raw: String): List<DetectedFood> {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val arr = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val en = o.optString("name_en").trim()
                val uk = o.optString("name_uk").trim()
                if (en.isEmpty() && uk.isEmpty()) return@mapNotNull null
                fun num(k: String) = o.optDouble(k).takeIf { !it.isNaN() && it >= 0 }
                DetectedFood(
                    nameEn = en.ifEmpty { uk },
                    nameUk = uk.ifEmpty { en },
                    grams = (num("grams") ?: 100.0).coerceIn(1.0, 2000.0),
                    kcal100 = num("kcal_per_100g"),
                    protein100 = num("protein_per_100g"),
                    fat100 = num("fat_per_100g"),
                    carbs100 = num("carbs_per_100g"),
                    confidence = o.optString("confidence", "medium"),
                )
            }
        }
    }
}
