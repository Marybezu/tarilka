package ua.tarilka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.time.LocalDate

/**
 * A password-encrypted copy of everything, made by itself about once a week into a folder the person chose
 * (for example a memory card, or a folder they sync themselves). No internet. The four newest copies are kept.
 * It is made when the diary is open (at start or after unlocking), so it also works with app protection.
 */
object AutoBackup {
    private const val PREFIX = "tarilka-auto-"
    private const val PART = ".part"
    private const val KEEP = 4
    const val EVERY_DAYS = 7

    fun isSetUp(prefs: Prefs) = prefs.autoBackupFolder != null && prefs.autoBackupSecret != null

    fun isDue(prefs: Prefs, today: Long = LocalDate.now().toEpochDay()) = isSetUp(prefs) && today - prefs.autoBackupLast >= EVERY_DAYS

    /** Makes a copy now; returns false (and remembers why) when the folder or password is gone. */
    suspend fun run(context: Context, db: AppDatabase, prefs: Prefs): Boolean {
        val tree = prefs.autoBackupFolder?.let(Uri::parse) ?: return false
        val password = prefs.autoBackupSecret?.let(ua.tarilka.security.SecretStore::open)
        if (password == null) {
            prefs.autoBackupProblem = true
            return false
        }
        return runCatching {
            val text = Backup.encrypt(Backup.export(db, prefs), password)
            val r = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val today = LocalDate.now()
            val name = "$PREFIX$today.json"
            // Written under a temporary name first: a failed write never leaves a broken copy among the kept ones,
            // and a second copy on the same day replaces the first instead of pushing out an older week.
            val part = DocumentsContract.createDocument(r, parent, "application/octet-stream", "$PREFIX$today$PART") ?: error("no file")
            runCatching {
                r.openOutputStream(part, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no output")
            }.onFailure { runCatching { DocumentsContract.deleteDocument(r, part) }; throw it }
            children(context, tree).filter { it.second == name }.forEach { (id, _) ->
                DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
            if (runCatching { DocumentsContract.renameDocument(r, part, name) }.getOrNull() == null) {
                // The folder's app cannot rename: write the copy under its own name, then drop the temporary file.
                val file = DocumentsContract.createDocument(r, parent, "application/json", name) ?: error("no file")
                r.openOutputStream(file, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no output")
                runCatching { DocumentsContract.deleteDocument(r, part) }
            }
            prune(context, tree, KEEP)
            prefs.autoBackupLast = today.toEpochDay()
            prefs.autoBackupProblem = false
            true
        }.getOrElse {
            prefs.autoBackupProblem = true
            false
        }
    }

    /** Deletes every copy made by this feature (when all personal data is deleted). */
    fun deleteCopies(context: Context, prefs: Prefs) {
        prefs.autoBackupFolder?.let { runCatching { prune(context, Uri.parse(it), 0) } }
    }

    /** Only the [keep] newest copies made by this feature stay (and no leftover temporary files); nothing else is touched. */
    private fun prune(context: Context, tree: Uri, keep: Int) {
        val r = context.contentResolver
        val ours = children(context, tree)
        val copies = ours.filter { it.second.endsWith(".json") }.sortedByDescending { it.second }.drop(keep)
        (copies + ours.filter { it.second.endsWith(PART) }).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(r, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    /** Files in the folder made by this feature: (document id, name). */
    private fun children(context: Context, tree: Uri): List<Pair<String, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(PREFIX)) ours += c.getString(0) to name
            }
        }
        return ours
    }

    /** The folder's name for the settings line. */
    fun folderName(context: Context, prefs: Prefs): String? = runCatching {
        val tree = Uri.parse(prefs.autoBackupFolder ?: return null)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
