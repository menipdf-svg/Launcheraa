package com.meni.pixellauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val isSystem: Boolean,
    val icon: ImageBitmap,
    // השם המקורי של האפליקציה, כשהמשתמש נתן לה שם אחר
    val originalLabel: String = label
) {
    val key: String get() = "$packageName/$activityName"
    val isRenamed: Boolean get() = label != originalLabel
}

class AppShortcut(val label: String, val icon: ImageBitmap?, val info: ShortcutInfo)

object AppsRepository {

    fun loadApps(
        context: Context,
        theme: IconTheme? = null,
        shape: IconShape = IconShape.SYSTEM,
        iconPack: String? = null
    ): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val custom = AppLabels.all(context)
        val versions = HashMap<String, Long>()
        val style = "${shape.name}|${theme?.background}|${theme?.foreground}|${iconPack.orEmpty()}"
        val signatures = mutableListOf<String>()

        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val activity = ri.activityInfo.name
                val original = ri.loadLabel(pm).toString()
                val key = "$pkg/$activity"
                // גרסת האפליקציה: אייקון מצויר מחדש רק כשהאפליקציה מתעדכנת
                val version = versions.getOrPut(pkg) {
                    runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
                }
                // אייקון שהמשתמש בחר גובר על הכול, והחותמת שלו נכנסת לחתימת המטמון
                val customStamp = CustomIcons.stamp(context, key)
                val signature = "$key|$version|$style|$customStamp"
                signatures += signature
                AppInfo(
                    label = custom[key] ?: original,
                    originalLabel = original,
                    packageName = pkg,
                    activityName = activity,
                    isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    icon = IconCache.get(context, signature) {
                        val chosen = if (customStamp > 0L) CustomIcons.load(context, key) else null
                        val packIcon = iconPack?.let { IconPacks.iconFor(context, it, pkg, activity) }
                        when {
                            chosen != null -> IconRenderer.renderPlain(
                                android.graphics.drawable.BitmapDrawable(context.resources, chosen)
                            )
                            packIcon != null -> IconRenderer.renderPlain(packIcon)
                            else -> IconRenderer.render(ri.loadIcon(pm), theme, shape = shape)
                        }
                    }
                )
            }
            .sortedWith(compareBy<AppInfo, String>(AlphabeticalOrder.collator) { it.label })
        IconCache.prune(context, signatures)
        return apps
    }

    // ה-Dock ההתחלתי כמו בפיקסל: טלפון, הודעות, Play, דפדפן, מצלמה
    fun defaultDockKeys(context: Context, apps: List<AppInfo>): List<String> {
        val pm = context.packageManager
        val packages = listOf(
            resolve(pm, Intent(Intent.ACTION_DIAL)),
            resolve(pm, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
            "com.android.vending",
            resolve(pm, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))),
            resolve(pm, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        )
        return packages
            .mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
            .distinctBy { it.packageName }
            .map { it.key }
    }

    private fun resolve(pm: PackageManager, intent: Intent): String? =
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" }

    fun launch(context: Context, app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        start(context, intent)
    }

    fun openAppInfo(context: Context, packageName: String) {
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** פותח את מסך החשבונות של המכשיר */
    fun openAccountSettings(context: Context) {
        val intents = listOf(
            Intent("android.settings.SYNC_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            }.getOrDefault(false)
            if (ok) return
        }
    }

    /** האם האפליקציה הותקנה מ-Google Play. רק אז יש טעם לבדוק שם עדכון */
    fun installedFromPlay(context: Context, packageName: String): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)

    /** פותח את דף האפליקציה ב-Google Play, או בדפדפן אם Play אינו זמין */
    fun openPlayStore(context: Context, packageName: String) {
        val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(play); true }.getOrDefault(false)
        if (!opened) {
            start(
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** פותח אפליקציה מותקנת לפי שם החבילה */
    fun openApp(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) start(context, intent) else openAppInfo(context, packageName)
    }

    fun uninstall(context: Context, packageName: String) {
        start(
            context,
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openCalendar(context: Context) {
        start(
            context,
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** פותח את מגירת ההתראות (כמו החלקה מלמעלה) */
    @android.annotation.SuppressLint("WrongConstant")
    fun expandNotifications(context: Context) {
        try {
            val service = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(service)
        } catch (e: Exception) {
            Log.w("PixelLauncher", "expandNotificationsPanel failed", e)
        }
    }

    fun openWallpaperPicker(context: Context) {
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "בחירת טפט")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, chooser)
    }

    /** אנדרואיד נותן קיצורי דרך רק לאפליקציית הבית שמוגדרת כברירת מחדל */
    fun canShowShortcuts(context: Context): Boolean = try {
        context.getSystemService(LauncherApps::class.java)?.hasShortcutHostPermission() == true
    } catch (_: Exception) {
        false
    }

    fun openHomeSettings(context: Context) {
        start(context, Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // קיצורי דרך של אפליקציה
    fun shortcuts(context: Context, packageName: String): List<AppShortcut> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        if (!canShowShortcuts(context)) return emptyList()

        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        val all = try {
            launcherApps.getShortcuts(query, Process.myUserHandle()).orEmpty()
        } catch (e: Exception) {
            Log.w("PixelLauncher", "getShortcuts failed for $packageName", e)
            emptyList()
        }

        val density = context.resources.displayMetrics.densityDpi
        return all
            .filter { it.isEnabled && (it.isDynamic || it.isDeclaredInManifest) }
            .distinctBy { it.id }
            .sortedWith(compareBy({ if (it.isDynamic) 1 else 0 }, { it.rank }))
            .take(4)
            .map { s ->
                // אייקון שנכשל בטעינה לא מבטל את כל הרשימה
                val icon = try {
                    launcherApps.getShortcutIconDrawable(s, density)?.toBitmap(96, 96)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
                AppShortcut(
                    label = (s.shortLabel ?: s.longLabel ?: "").toString(),
                    icon = icon,
                    info = s
                )
            }
    }

    fun startShortcut(context: Context, shortcut: AppShortcut) {
        try {
            context.getSystemService(LauncherApps::class.java)
                ?.startShortcut(shortcut.info, null, null)
        } catch (_: Exception) {
        }
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}

/** סדר א-ב לפי שפת המכשיר: עברית ואנגלית ממוינות נכון, בלי הבדל בין אותיות גדולות לקטנות */
object AlphabeticalOrder {
    val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.getDefault()).apply {
        strength = java.text.Collator.PRIMARY
    }
}

/** שמות שהמשתמש נתן לאפליקציות, לפי המפתח של האפליקציה */
/**
 * אייקונים שהמשתמש בחר מהגלריה. התמונה נשמרת בתיקייה פנימית של האפליקציה,
 * וההעדפות שומרות רק את שם הקובץ ואת מועד השמירה, כדי שהמטמון יתרענן בהחלפה.
 */
object CustomIcons {
    private const val PREFS = "custom_icons"

    private fun dir(context: Context) = java.io.File(context.filesDir, "icons").apply { mkdirs() }

    private fun fileFor(context: Context, appKey: String) =
        java.io.File(dir(context), appKey.replace('/', '_').replace(':', '_') + ".png")

    /** חתימת המטמון: משתנה בכל החלפה של אייקון, ולכן מאלצת ציור מחדש */
    fun stamp(context: Context, appKey: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(appKey, 0L)

    fun has(context: Context, appKey: String): Boolean = stamp(context, appKey) > 0L

    fun load(context: Context, appKey: String): android.graphics.Bitmap? {
        if (!has(context, appKey)) return null
        val file = fileFor(context, appKey)
        if (!file.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** שומר תמונה שנבחרה. מקטין ל-192 פיקסלים, די והותר לאייקון */
    fun save(context: Context, appKey: String, uri: android.net.Uri): Boolean = runCatching {
        val source = context.contentResolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it)
        } ?: return false
        val size = 192
        val scaled = android.graphics.Bitmap.createScaledBitmap(source, size, size, true)
        fileFor(context, appKey).outputStream().use {
            scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(appKey, System.currentTimeMillis()).apply()
        true
    }.getOrDefault(false)

    fun clear(context: Context, appKey: String) {
        runCatching { fileFor(context, appKey).delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(appKey).apply()
    }
}

/** פעולה שנייה לאייקון: החלקה מעלה עליו פותחת אפליקציה אחרת */
object AppSwipes {
    private const val PREFS = "icon_swipes"

    fun all(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()

    /** null = ביטול המחווה */
    fun set(context: Context, appKey: String, targetKey: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (targetKey == null) edit.remove(appKey) else edit.putString(appKey, targetKey)
        edit.apply()
    }
}

object AppLabels {
    private const val PREFS = "custom_labels"

    fun all(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()

    /** null או שם ריק = חזרה לשם המקורי */
    fun set(context: Context, appKey: String, name: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (name.isNullOrBlank()) edit.remove(appKey) else edit.putString(appKey, name.trim())
        edit.apply()
    }
}
