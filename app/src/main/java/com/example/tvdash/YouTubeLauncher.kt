package com.example.tvdash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build

/** Outcome of asking the TV's YouTube app to open a search. [code] is the reply sent back to the phone. */
enum class YtResult(val code: String) {
    LAUNCHED("ok"),
    EMPTY("empty"),
    NOT_INSTALLED("no-youtube"),
    NOT_HANDLED("not-handled"),
}

/**
 * Opens YouTube's own search-results screen with an explicit intent. Nothing is typed into YouTube's custom
 * on-screen keyboard, so its layout, language or version cannot affect the result.
 */
class YouTubeLauncher(context: Context) {
    private val pm: PackageManager = context.packageManager

    /** [from] is the context the activity is started from (must be allowed to start activities in the background). */
    fun search(rawQuery: String, from: Context): YtResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) return YtResult.EMPTY
        val installed = PACKAGES.filter { pm.getLeanbackLaunchIntentForPackage(it) != null || pm.getLaunchIntentForPackage(it) != null }
        if (installed.isEmpty()) return YtResult.NOT_INSTALLED
        for (uri in links(query)) {
            for (pkg in installed) {
                val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val target = resolve(intent)?.activityInfo ?: continue
                intent.component = ComponentName(target.packageName, target.name) // explicit target
                if (runCatching { from.startActivity(intent) }.isSuccess) return YtResult.LAUNCHED
            }
        }
        return YtResult.NOT_HANDLED
    }

    /** The preferred link format first, then the web link that the YouTube app also understands. */
    private fun links(query: String): List<Uri> = listOf(
        Uri.parse("youtube://www.youtube.com/results").buildUpon().appendQueryParameter("search_query", query).build(),
        Uri.parse("https://www.youtube.com/results").buildUpon().appendQueryParameter("search_query", query).build(),
    )

    @Suppress("DEPRECATION")
    private fun resolve(intent: Intent): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= 33) pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        else pm.resolveActivity(intent, 0)

    private companion object {
        val PACKAGES = listOf("com.google.android.youtube.tv", "com.google.android.youtube")
    }
}
