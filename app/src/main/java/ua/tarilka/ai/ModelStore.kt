package ua.tarilka.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the Gemma model file lives and how it gets there.
 * The app has no internet permission: the user downloads the model in their browser
 * and picks the file here; the app copies it into its private storage.
 */
class ModelStore(private val context: Context) {

    sealed interface State {
        data object Missing : State
        data class Ready(val file: File, val sizeMb: Long) : State
        data class Failed(val reason: String) : State
    }

    /** App-private storage: no other app can read it, and it needs no permission. */
    private val dir: File get() = File(context.filesDir, MODEL_DIR).apply { mkdirs() }

    /** Where versions up to 0.1.10 downloaded the model; still used if a model is there. */
    private val legacyDir: File? get() = context.getExternalFilesDir(null)?.let { File(it, MODEL_DIR) }

    private fun models(d: File?): List<File> =
        d?.listFiles { f -> f.name.endsWith(".litertlm") && f.length() > MIN_SIZE }?.toList() ?: emptyList()

    fun modelFile(): File? = (models(dir) + models(legacyDir)).maxByOrNull { it.lastModified() }

    fun state(): State {
        val f = modelFile() ?: return State.Missing
        return State.Ready(f, f.length() / MB)
    }

    data class ImportResult(val file: File, val originalDeleted: Boolean?)

    /**
     * Copies a model the user picked with the system file picker.
     * When [deleteOriginal] is set, tries to remove the picked file afterwards
     * (null in the result = not asked, false = the storage didn't allow it).
     */
    suspend fun import(uri: Uri, deleteOriginal: Boolean, onProgress: (Float) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val total = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val free = dir.usableSpace
            if (total > 0) require(free > total + 200 * MB) { "not enough free space" }
            val tmp = File(dir, "import.tmp")
            cr.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) onProgress(copied.toFloat() / total)
                    }
                }
            }
            if (tmp.length() <= MIN_SIZE) {
                tmp.delete()
                error("file too small, is it a .litertlm model?")
            }
            dir.listFiles { f -> f.name.endsWith(".litertlm") }?.forEach { it.delete() }
            legacyDir?.listFiles()?.forEach { it.delete() }
            val target = File(dir, "model.litertlm")
            tmp.renameTo(target)
            val deleted = if (deleteOriginal) {
                runCatching { DocumentsContract.deleteDocument(cr, uri) }.getOrDefault(false)
            } else {
                null
            }
            ImportResult(target, deleted)
        }

    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
        legacyDir?.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val E2B_PAGE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        const val E2B_FILE = "gemma-4-E2B-it.litertlm"
        private const val MODEL_DIR = "models"
        private const val MB = 1024L * 1024L
        private const val MIN_SIZE = 100L * MB
    }
}
