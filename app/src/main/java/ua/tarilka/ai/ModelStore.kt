package ua.tarilka.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the Gemma model file lives and how it gets there.
 * The model is ~2.6 GB, so it is never bundled in the APK: the user either
 * downloads it once (system download manager) or picks a file they already have.
 */
class ModelStore(private val context: Context) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float, val downloadedMb: Long, val totalMb: Long) : State
        data class Ready(val file: File, val sizeMb: Long) : State
        data class Failed(val reason: String) : State
    }

    private val dir: File get() = File(context.getExternalFilesDir(null), MODEL_DIR).apply { mkdirs() }
    private val sp = context.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(DownloadManager::class.java)

    fun modelFile(): File? =
        dir.listFiles { f -> f.name.endsWith(".litertlm") && f.length() > MIN_SIZE }?.maxByOrNull { it.lastModified() }

    fun state(): State {
        val id = sp.getLong(KEY_DOWNLOAD, -1)
        if (id >= 0) {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> finishDownload()
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            sp.edit().remove(KEY_DOWNLOAD).apply()
                            partFile().delete()
                            return State.Failed("download error $reason")
                        }
                        else -> return State.Downloading(
                            if (total > 0) done.toFloat() / total else 0f, done / MB, total.coerceAtLeast(0) / MB,
                        )
                    }
                } else {
                    sp.edit().remove(KEY_DOWNLOAD).apply()
                }
            }
        }
        val f = modelFile() ?: return State.Missing
        return State.Ready(f, f.length() / MB)
    }

    fun startDownload(url: String = E2B_URL) {
        if (sp.getLong(KEY_DOWNLOAD, -1) >= 0) return
        partFile().delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Gemma 4 E2B")
            .setDescription("Tarilka")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(partFile()))
        sp.edit().putLong(KEY_DOWNLOAD, dm.enqueue(req)).apply()
    }

    fun cancelDownload() {
        val id = sp.getLong(KEY_DOWNLOAD, -1)
        if (id >= 0) dm.remove(id)
        sp.edit().remove(KEY_DOWNLOAD).apply()
        partFile().delete()
    }

    private fun finishDownload() {
        sp.edit().remove(KEY_DOWNLOAD).apply()
        val part = partFile()
        if (part.exists() && part.length() > MIN_SIZE) {
            part.renameTo(File(dir, E2B_FILE))
        }
    }

    /** Copies a model the user picked with the system file picker. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val tmp = File(dir, "import.tmp")
        context.contentResolver.openInputStream(uri)!!.use { input ->
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
        require(tmp.length() > MIN_SIZE) { "file too small" }
        val target = File(dir, "imported.litertlm")
        modelFile()?.delete()
        tmp.renameTo(target)
        target
    }

    fun delete() {
        cancelDownload()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun partFile() = File(dir, "$E2B_FILE.part")

    companion object {
        const val E2B_FILE = "gemma-4-E2B-it.litertlm"
        const val E2B_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val E2B_PAGE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        private const val MODEL_DIR = "models"
        private const val KEY_DOWNLOAD = "downloadId"
        private const val MB = 1024L * 1024L
        private const val MIN_SIZE = 100L * MB
    }
}
