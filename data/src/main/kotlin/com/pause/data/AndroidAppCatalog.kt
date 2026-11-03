package com.pause.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.pause.domain.AppCatalog
import com.pause.domain.AppCategory
import com.pause.domain.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidAppCatalog(private val context: Context) : AppCatalog {
    override suspend fun listApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcherPackages = query(manager, homeIntent).map { it.activityInfo.packageName }.toSet()
        val excluded = launcherPackages + setOf(
            context.packageName, "com.android.settings", "com.android.systemui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
        )
        query(manager, launcherIntent)
            .mapNotNull { info ->
                val application = info.activityInfo.applicationInfo
                if (application.packageName in excluded || !application.enabled) return@mapNotNull null
                InstalledApp(
                    packageName = application.packageName,
                    name = info.loadLabel(manager)?.toString()?.takeIf(String::isNotBlank) ?: application.packageName,
                    category = category(application.category),
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    @Suppress("DEPRECATION")
    private fun query(manager: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= 33) manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else manager.queryIntentActivities(intent, 0)

    private fun category(category: Int): AppCategory = when (category) {
        ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
        ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_AUDIO -> AppCategory.VIDEO
        ApplicationInfo.CATEGORY_GAME -> AppCategory.GAMES
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
        else -> AppCategory.OTHER
    }
}
