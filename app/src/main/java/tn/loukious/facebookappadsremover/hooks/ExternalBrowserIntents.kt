package tn.loukious.facebookappadsremover.hooks

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

internal object ExternalBrowserIntents {
    // Executed inside Facebook, whose manifest already queries web VIEW intents.
    @SuppressLint("QueryPermissionsNeeded")
    fun replacement(context: Context, original: Intent): Intent? {
        val component = original.component ?: return null
        val url = ExternalBrowserPolicy.destination(
            component.packageName, component.className, original.dataString,
        ) ?: return null

        // Resolve a generic web URL so verified app links cannot take over.
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val manager = context.packageManager
        val browsers = manager.queryIntentActivities(probe,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER)
            .filter {
                // A browser's web filter accepts every host, unlike an app
                // registered for a particular website.
                it.filter?.countDataAuthorities() == 0 &&
                    it.filter?.countDataPaths() == 0 &&
                    it.activityInfo.packageName != "com.facebook.katana"
            }
            .distinctBy { it.activityInfo.packageName }
        if (browsers.isEmpty()) return null

        val defaultPackage = manager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        val defaultBrowser = browsers.firstOrNull { it.activityInfo.packageName == defaultPackage }
        fun view(browser: android.content.pm.ResolveInfo) =
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setComponent(ComponentName(browser.activityInfo.packageName, browser.activityInfo.name))

        val external = when {
            defaultBrowser != null -> view(defaultBrowser)
            browsers.size == 1 -> view(browsers.single())
            else -> Intent.createChooser(view(browsers.first()), "Open link in browser")
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, browsers.drop(1).map(::view).toTypedArray())
        }
        // Start a normal browser task without Facebook's browser extras,
        // custom-tab session, grants or task-clearing flags.
        return external.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
