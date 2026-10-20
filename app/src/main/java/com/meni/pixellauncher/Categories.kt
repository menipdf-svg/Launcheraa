package com.meni.pixellauncher

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** הקטגוריות שמופיעות במגירה, לפי הסדר הזה */
enum class AppCategory(val label: String) {
    COMMUNICATION("תקשורת"),
    SOCIAL("רשתות חברתיות"),
    PRODUCTIVITY("פרודוקטיביות"),
    EDUCATION("חינוך ולימודים"),
    MUSIC("מוזיקה"),
    VIDEO("וידאו ובידור"),
    PHOTO("צילום"),
    DESIGN("עיצוב"),
    GAMES("משחקים"),
    SHOPPING("קניות"),
    FINANCE("פיננסים"),
    FOOD("אוכל ומשקאות"),
    NAVIGATION("ניווט ורכב"),
    TRAVEL("נסיעות"),
    WEATHER("מזג אוויר"),
    NEWS("חדשות"),
    BOOKS("ספרים וקריאה"),
    HEALTH("בריאות"),
    SPORTS("ספורט"),
    LIFESTYLE("לייף סטייל"),
    PERSONALIZATION("התאמה אישית"),
    TOOLS("כלים"),
    OTHER("אחר")
}

/**
 * תיקייה במגירה: קטגוריה מובנית או תיקייה שהמשתמש יצר.
 * order קובע את הסדר: התיקיות של המשתמש קודם, ואחריהן הקטגוריות.
 */
data class DrawerGroup(
    val id: String,
    val label: String,
    val order: Int,
    val isCustom: Boolean = false
) {
    /** תיקיית יצרן (Samsung, Google): תמיד מוצגת כתיקייה, גם עם אפליקציה אחת */
    val isBrand: Boolean get() = id.startsWith(BRAND_PREFIX)

    companion object {
        fun of(category: AppCategory) = DrawerGroup(category.name, category.label, 1000 + category.ordinal)
        val OTHER = of(AppCategory.OTHER)

        const val BRAND_PREFIX = "brand:"
        val SAMSUNG = DrawerGroup("${BRAND_PREFIX}samsung", "Samsung", 900)
        val GOOGLE = DrawerGroup("${BRAND_PREFIX}google", "Google", 901)
        val BRANDS = listOf(SAMSUNG, GOOGLE)

        /** היצרן לפי שם החבילה, או null לאפליקציה רגילה */
        fun brandOf(packageName: String): DrawerGroup? = when {
            packageName.startsWith("com.samsung.") || packageName.startsWith("com.sec.") ||
                packageName.startsWith("com.osp.") -> SAMSUNG
            packageName.startsWith("com.google.") || packageName == "com.android.chrome" ||
                packageName == "com.android.vending" -> GOOGLE
            else -> null
        }
    }
}

/**
 * מוצא לכל אפליקציה קטגוריה:
 * 1. מ-Google Play (נשמר בזיכרון המכשיר, כך שכל אפליקציה נבדקת פעם אחת)
 * 2. אם האפליקציה לא ב-Play או שאין אינטרנט: מהקטגוריה שהאפליקציה מצהירה עליה, ולפי שם החבילה
 */
object CategoryRepository {

    private const val PREFS = "app_categories"
    private const val NOT_ON_PLAY = ""
    private const val OVERRIDES = "category_overrides"
    private const val FOLDERS = "custom_folders"
    private const val CUSTOM_PREFIX = "custom:"

    // דף ה-Play כולל JSON-LD עם "applicationCategory":"COMMUNICATION"
    private val PLAY_CATEGORY = Regex("\"applicationCategory\"\\s*:\\s*\"([A-Z_]+)\"")

    /** מה שידוע כבר עכשיו, בלי אינטרנט */
    fun cached(context: Context, packages: List<String>): Map<String, DrawerGroup> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val overrides = context.getSharedPreferences(OVERRIDES, Context.MODE_PRIVATE)
        val custom = customFolders(context).associateBy { it.id }
        val brands = context.getSharedPreferences("launcher_layout", Context.MODE_PRIVATE)
            .getBoolean("brandFolders", false)
        return packages.associateWith { pkg ->
            // בחירה ידנית של המשתמש קודמת ל-Google Play
            val chosen = overrides.getString(pkg, null)
            custom[chosen]
                ?: parse(chosen)?.let { DrawerGroup.of(it) }
                ?: DrawerGroup.BRANDS.firstOrNull { it.id == chosen }
                // אפליקציות של Samsung ו-Google בתיקיות משלהן, כשהאפשרות פעילה
                ?: (if (brands) DrawerGroup.brandOf(pkg) else null)
                ?: DrawerGroup.of(resolve(context, pkg, prefs.getString(pkg, null)))
        }
    }

    // ---------- העברה ידנית בין תיקיות ----------

    /** null = חזרה לקטגוריה מ-Google Play */
    fun setOverride(context: Context, packageName: String, groupId: String?) {
        val edit = context.getSharedPreferences(OVERRIDES, Context.MODE_PRIVATE).edit()
        if (groupId == null) edit.remove(packageName) else edit.putString(packageName, groupId)
        edit.apply()
    }

    fun hasOverride(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(OVERRIDES, Context.MODE_PRIVATE).contains(packageName)

    /** כמה שינויים ידניים יש: אפליקציות שהועברו ותיקיות שנוצרו */
    fun overrideCount(context: Context): Int =
        context.getSharedPreferences(OVERRIDES, Context.MODE_PRIVATE).all.size + customFolders(context).size

    /** מחזיר הכל לקטגוריות של Google Play, ומוחק את התיקיות שנוצרו */
    fun clearOverrides(context: Context) {
        context.getSharedPreferences(OVERRIDES, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(FOLDERS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun parse(name: String?): AppCategory? =
        name?.let { runCatching { AppCategory.valueOf(it) }.getOrNull() }

    // ---------- תיקיות של המשתמש ----------

    fun customFolders(context: Context): List<DrawerGroup> {
        val json = context.getSharedPreferences(FOLDERS, Context.MODE_PRIVATE).getString("list", null)
            ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                DrawerGroup(o.getString("id"), o.getString("name"), i, isCustom = true)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun createFolder(context: Context, name: String): DrawerGroup {
        val folders = customFolders(context)
        val group = DrawerGroup(CUSTOM_PREFIX + System.currentTimeMillis(), name.trim(), folders.size, isCustom = true)
        saveFolders(context, folders + group)
        return group
    }

    fun renameFolder(context: Context, id: String, name: String) {
        saveFolders(context, customFolders(context).map { if (it.id == id) it.copy(label = name.trim()) else it })
    }

    /** מוחק תיקייה. האפליקציות שבה חוזרות לקטגוריה שלהן ב-Google Play */
    fun deleteFolder(context: Context, id: String) {
        saveFolders(context, customFolders(context).filter { it.id != id })
        val overrides = context.getSharedPreferences(OVERRIDES, Context.MODE_PRIVATE)
        val edit = overrides.edit()
        overrides.all.forEach { (pkg, value) -> if (value == id) edit.remove(pkg) }
        edit.apply()
    }

    private fun saveFolders(context: Context, folders: List<DrawerGroup>) {
        val array = JSONArray()
        folders.forEach { array.put(JSONObject().put("id", it.id).put("name", it.label)) }
        context.getSharedPreferences(FOLDERS, Context.MODE_PRIVATE).edit().putString("list", array.toString()).apply()
    }

    /** בודק ב-Play את מה שעוד לא נבדק, ומחזיר את כל הקטגוריות */
    suspend fun categorize(
        context: Context,
        packages: List<String>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Map<String, DrawerGroup> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val missing = packages.filter { !prefs.contains(it) }

        if (missing.isNotEmpty()) {
            val done = AtomicInteger(0)
            val limit = Semaphore(4)
            onProgress(0, missing.size)
            coroutineScope {
                missing.map { pkg ->
                    async {
                        limit.withPermit {
                            val raw = fetchPlayCategory(pkg)
                            // כשל רשת לא נשמר, כדי לנסות שוב בפעם הבאה
                            if (raw != null) prefs.edit().putString(pkg, raw).apply()
                            onProgress(done.incrementAndGet(), missing.size)
                        }
                    }
                }.awaitAll()
            }
        }
        cached(context, packages)
    }

    fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** null = כשל רשת, "" = האפליקציה לא נמצאה ב-Play */
    private fun fetchPlayCategory(packageName: String): String? {
        val encoded = URLEncoder.encode(packageName, "UTF-8")
        val url = URL("https://play.google.com/store/apps/details?id=$encoded&hl=en&gl=US")
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val html = connection.inputStream.bufferedReader().use { it.readText() }
                    PLAY_CATEGORY.find(html)?.groupValues?.get(1) ?: NOT_ON_PLAY
                }
                HttpURLConnection.HTTP_NOT_FOUND -> NOT_ON_PLAY
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun resolve(context: Context, packageName: String, playCategory: String?): AppCategory {
        if (!playCategory.isNullOrEmpty()) {
            fromPlay(playCategory)?.let { return it }
        }
        return localCategory(context, packageName)
    }

    private fun fromPlay(raw: String): AppCategory? = when {
        raw.startsWith("GAME") -> AppCategory.GAMES
        else -> when (raw) {
            "COMMUNICATION" -> AppCategory.COMMUNICATION
            "SOCIAL", "DATING" -> AppCategory.SOCIAL
            "PRODUCTIVITY", "BUSINESS" -> AppCategory.PRODUCTIVITY
            "EDUCATION" -> AppCategory.EDUCATION
            "MUSIC_AND_AUDIO" -> AppCategory.MUSIC
            "VIDEO_PLAYERS", "ENTERTAINMENT", "COMICS" -> AppCategory.VIDEO
            "PHOTOGRAPHY" -> AppCategory.PHOTO
            "ART_AND_DESIGN", "BEAUTY" -> AppCategory.DESIGN
            "SHOPPING" -> AppCategory.SHOPPING
            "FINANCE" -> AppCategory.FINANCE
            "FOOD_AND_DRINK" -> AppCategory.FOOD
            "MAPS_AND_NAVIGATION", "AUTO_AND_VEHICLES" -> AppCategory.NAVIGATION
            "TRAVEL_AND_LOCAL" -> AppCategory.TRAVEL
            "WEATHER" -> AppCategory.WEATHER
            "NEWS_AND_MAGAZINES" -> AppCategory.NEWS
            "BOOKS_AND_REFERENCE" -> AppCategory.BOOKS
            "HEALTH_AND_FITNESS", "MEDICAL" -> AppCategory.HEALTH
            "SPORTS" -> AppCategory.SPORTS
            "LIFESTYLE", "HOUSE_AND_HOME", "PARENTING", "EVENTS" -> AppCategory.LIFESTYLE
            "PERSONALIZATION" -> AppCategory.PERSONALIZATION
            "TOOLS", "LIBRARIES_AND_DEMO" -> AppCategory.TOOLS
            else -> null
        }
    }

    private fun localCategory(context: Context, packageName: String): AppCategory {
        val info = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }
        when (info?.category) {
            ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAMES
            ApplicationInfo.CATEGORY_AUDIO -> return AppCategory.MUSIC
            ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.VIDEO
            ApplicationInfo.CATEGORY_IMAGE -> return AppCategory.PHOTO
            ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
            ApplicationInfo.CATEGORY_NEWS -> return AppCategory.NEWS
            ApplicationInfo.CATEGORY_MAPS -> return AppCategory.NAVIGATION
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppCategory.PRODUCTIVITY
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> return AppCategory.TOOLS
        }

        // אפליקציות מערכת שלא נמצאות ב-Play
        val p = packageName.lowercase()
        return when {
            listOf("dialer", "contacts", "messag", "mms", "telecom", "phone", "mail").any { it in p } ->
                AppCategory.COMMUNICATION
            listOf("camera", "gallery", "photos").any { it in p } -> AppCategory.PHOTO
            listOf("music", "audio", "podcast").any { it in p } -> AppCategory.MUSIC
            listOf("video", "youtube", "player", "tv").any { it in p } -> AppCategory.VIDEO
            listOf("calendar", "notes", "docs").any { it in p } -> AppCategory.PRODUCTIVITY
            "weather" in p -> AppCategory.WEATHER
            listOf("maps", "navigation").any { it in p } -> AppCategory.NAVIGATION
            listOf("settings", "clock", "calculator", "files", "documentsui", "browser", "chrome", "vending")
                .any { it in p } -> AppCategory.TOOLS
            else -> AppCategory.OTHER
        }
    }
}
