package ua.tarilka.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

/** What the model answered, kept so a failed recognition can be explained. */
data class PhotoResult(
    val foods: List<DetectedFood>,
    val raw: String,
    val backend: String,
    /** Seconds spent loading the model (0 if it was already loaded) and analyzing the photo. */
    val loadSec: Int,
    val analyzeSec: Int,
)

/** Runs Gemma 4 fully on the phone via LiteRT-LM. Nothing leaves the device. */
class GemmaEngine(
    private val context: Context,
    private val models: ModelStore,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /** Frees the model's memory after 5 minutes without use. */
    private var idleJob: kotlinx.coroutines.Job? = null
    private fun scheduleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            kotlinx.coroutines.delay(IDLE_MS)
            release()
        }
    }

    private var engine: Engine? = null
    private var loadedPath: String? = null

    /** "GPU" or "CPU": which processor the model ended up on. */
    var backend: String = "?"
        private set

    /** One user of the native engine at a time: loading, inference and release never overlap. */
    private val mutex = Mutex()

    /** Must be called with [mutex] held. */
    private fun engineLocked(): Engine {
        val path = models.modelFile()?.absolutePath ?: error("Model not installed")
        engine?.takeIf { loadedPath == path }?.let { return it }
        engine?.close()
        engine = null
        val created = runCatching { create(path, gpu = true).also { backend = "GPU" } }
            .getOrElse { create(path, gpu = false).also { backend = "CPU" } }
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

    /** Time the last call spent loading the model, ms. */
    private var lastLoadMs = 0L

    private suspend fun <T> withEngine(block: (Engine) -> T): T {
        idleJob?.cancel()
        try {
            return mutex.withLock {
                withContext(Dispatchers.Default) {
                    val t0 = System.currentTimeMillis()
                    val e = engineLocked()
                    lastLoadMs = System.currentTimeMillis() - t0
                    block(e)
                }
            }
        } finally {
            scheduleRelease()
        }
    }

    /** Frees the model's memory; waits for a running analysis to finish first. */
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            loadedPath = null
        }
    }

    suspend fun analyzePhoto(image: File, note: String?, plate: Int = 1): PhotoResult {
        val prompt = buildString {
            append(PHOTO_PROMPT.replace("{PLATE}", PLATES.getOrElse(plate) { PLATES[1] }))
            if (!note.isNullOrBlank()) append("\nThe person says about this meal: ").append(note.trim())
        }
        val start = System.currentTimeMillis()
        val raw = withEngine { e ->
            // Constrained decoding makes the small model answer in valid JSON; fall back if unsupported.
            runCatching { ask(e, image, prompt, constrained = true) }.getOrElse { ask(e, image, prompt, constrained = false) }
        }
        val total = System.currentTimeMillis() - start
        return PhotoResult(
            parseFoods(raw), raw, backend,
            loadSec = (lastLoadMs / 1000).toInt(), analyzeSec = ((total - lastLoadMs) / 1000).toInt(),
        )
    }

    /** A typed list like "two cutlets and some mashed potato" → foods in English with grams. No image: a few seconds. */
    suspend fun parseText(text: String): List<DetectedFood> {
        val prompt = TEXT_PROMPT + "\n" + text.take(500)
        val raw = withEngine { e ->
            runCatching { ask(e, null, prompt, constrained = true) }.getOrElse { ask(e, null, prompt, constrained = false) }
        }
        return parseFoods(raw)
    }

    private fun ask(e: Engine, image: File?, prompt: String, constrained: Boolean): String =
        e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PHOTO),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                // No hidden "thinking": it would use up the output before the list is written.
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = 512,
                enableResponseFormat = constrained,
            ),
        ).use { conv ->
            val contents = if (image != null) Contents.of(Content.ImageFile(image.absolutePath), Content.Text(prompt)) else Contents.of(Content.Text(prompt))
            if (constrained) {
                conv.sendMessage(contents, responseFormat = ResponseFormat.json(SCHEMA)).toString()
            } else {
                conv.sendMessage(contents).toString()
            }
        }

    /** Loads the model in the background so the first photo doesn't wait for it. */
    suspend fun warmUp() = runCatching { withEngine { } }

    /** Short, friendly advice based on the day's numbers. */
    suspend fun advice(summary: String, ukrainian: Boolean, maxTokens: Int = 400): String = withEngine { e ->
        e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(if (ukrainian) SYSTEM_ADVICE_UK else SYSTEM_ADVICE_EN),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6),
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = maxTokens,
            ),
        ).use { conv -> conv.sendMessage(summary).toString().trim() }
    }

    companion object {
        private const val IDLE_MS = 5 * 60 * 1000L

        private const val SYSTEM_PHOTO =
            "You are a careful nutrition assistant. You identify foods in meal photos and estimate portion sizes. " +
                "You always answer with valid JSON only."

        private const val TEXT_PROMPT = """The person wrote what they ate, maybe in Ukrainian. List each food in English with its weight in grams.
Use the amount they gave; if none, estimate a usual portion (one cutlet about 80 g, one slice of bread about 35 g, one egg about 50 g, a bowl of soup about 300 g).
Use short common names. Answer with ONLY JSON: {"items":[{"name":"boiled potatoes","grams":150,"confidence":"high"}]}
What they ate:"""

        /** What the person says the food is on: gives the model a real size to compare with. */
        private val PLATES = listOf(
            "the food is on a small plate about 20 cm wide",
            "the food is on a dinner plate about 26 cm wide",
            "the food is on a large plate about 30 cm wide",
            "the food is in a bowl about 15 cm wide (about 400 ml)",
        )

        private const val SCHEMA = """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object",
"properties":{"name":{"type":"string"},"grams":{"type":"number"},"confidence":{"type":"string","enum":["high","medium","low"]}},
"required":["name","grams"]}}},"required":["items"]}"""

        // No long list of example foods: the small model tends to "see" whatever the prompt names.
        private const val PHOTO_PROMPT = """Look at this photo of a meal and list each distinct food that is clearly visible, in English.
Do not list anything you cannot see. Do not miss meat, fish, cutlets, bread, cheese or sauces.
Estimate the edible weight of each in grams. For scale: {PLATE}, a slice of bread or toast
weighs about 35 g, one egg about 50 g, half an avocado about 70 g, a tablespoon of sauce about 15 g.
Use short common names, for example "boiled egg" or "rye bread".
Answer with ONLY JSON: {"items":[{"name":"boiled egg","grams":100,"confidence":"high"}]}
confidence is "high", "medium" or "low". If there is no food, answer {"items":[]}."""

        private const val SYSTEM_ADVICE_UK =
            "Ти доброзичливий помічник з харчування. Відповідай лише українською мовою, без жодного російського слова, коротко: 2–4 пункти, кожен одним реченням. " +
                "Враховуй, що і коли людина їла, скільки випила і скільки пройшла кроків. Давай практичні поради з конкретними продуктами. Не став діагнозів, не радь голодування чи екстремальних дієт, " +
                "не засуджуй. Якщо цифри виглядають небезпечно (дуже мало їжі або дуже швидка втрата ваги), м'яко порадь звернутися до лікаря."

        private const val SYSTEM_ADVICE_EN =
            "You are a friendly nutrition helper. Answer in English, briefly: 2–4 bullet points, one sentence each. " +
                "Take into account what and when the person ate, how much they drank and how many steps they walked. Give practical suggestions with concrete foods. Do not diagnose, do not suggest fasting or extreme diets, " +
                "do not judge. If the numbers look unsafe (very low intake or very fast weight loss), gently suggest seeing a doctor."

        /** Accepts {"items":[...]}, a bare [...] or even a cut-off answer (objects are read one by one). */
        fun parseFoods(raw: String): List<DetectedFood> {
            val text = raw.replace("```json", "").replace("```", "")
            val objects: List<JSONObject> = run {
                runCatching { JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).getJSONArray("items") }
                    .recoverCatching { JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1)) }
                    .map { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                    .getOrElse {
                        Regex("\\{[^{}]*\"name(?:_en|_uk)?\"[^{}]*\\}").findAll(text)
                            .mapNotNull { m -> runCatching { JSONObject(m.value) }.getOrNull() }.toList()
                    }
            }
            return objects.mapNotNull { o ->
                val en = o.optString("name").ifBlank { o.optString("name_en") }.trim()
                val uk = o.optString("name_uk").trim()
                if (en.isEmpty() && uk.isEmpty()) return@mapNotNull null
                fun num(k: String) = o.optDouble(k).takeIf { !it.isNaN() && it >= 0 }
                DetectedFood(
                    nameEn = en.ifEmpty { uk },
                    nameUk = ua.tarilka.data.Lang.safeUk(uk, en.ifEmpty { uk }),
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
