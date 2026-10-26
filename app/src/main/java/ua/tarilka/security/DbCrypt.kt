package ua.tarilka.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ua.tarilka.data.AppDatabase
import java.io.File

/**
 * Turns the diary database into an encrypted one and back, with SQLCipher's own sqlcipher_export.
 * The copy is written to a temporary file and checked (every table has the same number of rows)
 * before it replaces the original, so a failure leaves the original untouched.
 * The database must be closed while this runs.
 */
object DbCrypt {
    private const val TMP = "tarilka.convert.tmp"

    fun encrypt(context: Context, rawKey: ByteArray) = convert(context, from = ByteArray(0), to = rawKey)

    fun decrypt(context: Context, rawKey: ByteArray) = convert(context, from = rawKey, to = ByteArray(0))

    private fun convert(context: Context, from: ByteArray, to: ByteArray) {
        System.loadLibrary("sqlcipher")
        val main = context.getDatabasePath(AppDatabase.NAME)
        val tmp = context.getDatabasePath(TMP)
        deleteWithSidecars(tmp)
        val counts: Map<String, Long>
        helper(context, AppDatabase.NAME, from).use { h ->
            val db = h.writableDatabase
            counts = rowCounts(db)
            // An empty key string attaches a plain (unencrypted) file; "x'…'" is the raw key.
            db.execSQL("ATTACH DATABASE ? AS target KEY ?", arrayOf<Any>(tmp.path, String(to, Charsets.US_ASCII)))
            db.query("SELECT sqlcipher_export('target')").use { it.moveToFirst() }
            db.execSQL("PRAGMA target.user_version = ${AppDatabase.VERSION}")
            db.execSQL("DETACH DATABASE target")
        }
        // Check the copy before it replaces anything.
        helper(context, TMP, to).use { h ->
            val copied = rowCounts(h.writableDatabase)
            check(copied == counts) { "The converted copy does not match" }
        }
        // Swap so that some complete database file exists at every moment.
        val old = File(main.path + ".old")
        deleteWithSidecars(old)
        check(main.renameTo(old)) { "Could not replace the database" }
        listOf("-wal", "-shm", "-journal").forEach { File(main.path + it).delete() }
        if (!tmp.renameTo(main)) {
            old.renameTo(main)
            error("Could not replace the database")
        }
        deleteWithSidecars(old)
    }

    /** After an interrupted swap: put the complete old file back. */
    fun recover(context: Context) {
        val main = context.getDatabasePath(AppDatabase.NAME)
        val old = File(main.path + ".old")
        if (!main.exists() && old.exists()) old.renameTo(main)
        deleteWithSidecars(context.getDatabasePath(TMP))
    }

    private fun helper(context: Context, name: String, key: ByteArray): SupportSQLiteOpenHelper =
        SupportOpenHelperFactory(key).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(AppDatabase.VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )

    private fun rowCounts(db: SupportSQLiteDatabase): Map<String, Long> {
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { t -> db.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getLong(0) } }
    }

    private fun deleteWithSidecars(f: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(f.path + it).delete() }
    }
}
