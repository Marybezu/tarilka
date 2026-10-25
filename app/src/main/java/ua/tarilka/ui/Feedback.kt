package ua.tarilka.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The app has no internet: feedback opens a new GitHub issue in the browser, already filled in.
 * The person sees and edits everything before sending; nothing from the diary is added.
 */
object Feedback {
    private const val NEW_ISSUE = "https://github.com/Marybezu/tarilka/issues/new"

    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    fun open(context: Context, title: String, body: String) {
        val uri = Uri.parse(NEW_ISSUE).buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", body.take(6000))
            .build()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
