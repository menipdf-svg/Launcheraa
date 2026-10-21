package com.meni.pixellauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

const val DOCK_MAX = 5
const val MAX_GRID_COLUMNS = 6
const val MAX_GRID_ROWS = 7

// פריט במסך הבית: אפליקציה (appKey) או ווידג'ט (widgetId)
/** היכן מוצגים שמות האפליקציות */
enum class LabelMode(val label: String) {
    ALL("בכל מקום"),
    DRAWER("רק במגירה"),
    NONE("ללא")
}

data class HomeItem(
    val id: Long,
    val appKey: String? = null,
    val widgetId: Int = -1,
    val page: Int,
    val x: Int,
    val y: Int,
    val w: Int = 1,
    val h: Int = 1,
    /** תיקייה במסך הבית: רשימת האפליקציות שבה ושם לבחירה */
    val folderApps: List<String> = emptyList(),
    val folderName: String = ""
) {
    val isFolder: Boolean get() = folderApps.isNotEmpty()
    val isWidget: Boolean get() = appKey == null && !isFolder
}

// שומר את הסידור של מסך הבית וה-Dock ב-SharedPreferences
class LauncherStore(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_layout", Context.MODE_PRIVATE)

    val dock = mutableStateListOf<String>()
    val home = mutableStateListOf<HomeItem>()
    var pageCount by mutableIntStateOf(1)
        private set
    var dockInitialized = false
        private set
    private var nextId = 1L

    // סמלים בצבעי הטפט (Themed icons)
    var themedIcons by mutableStateOf(prefs.getBoolean("themed", false))
        private set

    // ---------- מראה ----------
    var iconScale by mutableStateOf(prefs.getFloat("iconScale", 1f))
        private set
    var iconShape by mutableStateOf(
        runCatching { IconShape.valueOf(prefs.getString("iconShape", null) ?: "") }.getOrDefault(IconShape.SYSTEM)
    )
        private set
    var gridColumns by mutableIntStateOf(prefs.getInt("gridColumns", 5))
        private set
    var gridRows by mutableIntStateOf(prefs.getInt("gridRows", 5))
        private set
    var drawerColumns by mutableIntStateOf(prefs.getInt("drawerColumns", 5))
        private set
    var drawerOpacity by mutableStateOf(prefs.getFloat("drawerOpacity", 0.96f))
        private set
    var searchOpacity by mutableStateOf(prefs.getFloat("searchOpacity", 0.92f))
        private set
    var pageTransition by mutableStateOf(
        runCatching { PageTransition.valueOf(prefs.getString("pageTransition", null) ?: "") }
            .getOrDefault(PageTransition.SLIDE)
    )
        private set

    fun updateIconScale(value: Float) {
        iconScale = value
        prefs.edit().putFloat("iconScale", value).apply()
    }

    // ---------- יכולות (מתגים) ----------

    val features = androidx.compose.runtime.mutableStateMapOf<Feature, Boolean>().apply {
        Feature.entries.forEach { put(it, prefs.getBoolean("f_${it.name}", it.default)) }
    }

    fun on(feature: Feature): Boolean = features[feature] ?: feature.default

    fun setFeature(feature: Feature, enabled: Boolean) {
        features[feature] = enabled
        prefs.edit().putBoolean("f_${feature.name}", enabled).apply()
    }

    // ---------- חבילת אייקונים ----------

    var iconPack by mutableStateOf(prefs.getString("iconPack", null))
        private set

    fun updateIconPack(pack: String?) {
        iconPack = pack
        prefs.edit().putString("iconPack", pack).apply()
    }

    // ---------- Dock חכם (אוזניות / רכב) ----------

    val contextDock = mutableStateListOf<String>().apply {
        prefs.getString("contextDock", null)?.let { json ->
            runCatching {
                val array = JSONArray(json)
                for (i in 0 until array.length()) add(array.getString(i))
            }
        }
    }

    fun setContextDock(keys: List<String>) {
        contextDock.clear()
        contextDock.addAll(keys.take(DOCK_MAX))
        prefs.edit().putString("contextDock", JSONArray(contextDock.toList()).toString()).apply()
    }

    // ---------- אפליקציות חדשות ----------

    val newApps = mutableStateListOf<String>().apply {
        addAll(prefs.getStringSet("newApps", emptySet()).orEmpty())
    }

    /** משווה לרשימה המוכרת: מה שלא היה קודם מסומן כחדש (לא בהפעלה הראשונה) */
    fun trackInstalled(keys: Set<String>) {
        val known = prefs.getStringSet("knownApps", null)
        if (known != null) {
            val fresh = keys - known
            if (fresh.isNotEmpty()) newApps.addAll(fresh.filter { it !in newApps })
        }
        newApps.retainAll(keys)
        prefs.edit()
            .putStringSet("knownApps", keys)
            .putStringSet("newApps", newApps.toSet())
            .apply()
    }

    fun markOpened(key: String) {
        if (newApps.remove(key)) prefs.edit().putStringSet("newApps", newApps.toSet()).apply()
    }

    fun updateIconShape(value: IconShape) {
        iconShape = value
        prefs.edit().putString("iconShape", value.name).apply()
    }

    fun updateDrawerColumns(value: Int) {
        drawerColumns = value
        prefs.edit().putInt("drawerColumns", value).apply()
    }

    fun updateDrawerOpacity(value: Float) {
        drawerOpacity = value
        prefs.edit().putFloat("drawerOpacity", value).apply()
    }

    fun updateSearchOpacity(value: Float) {
        searchOpacity = value
        prefs.edit().putFloat("searchOpacity", value).apply()
    }

    fun updatePageTransition(value: PageTransition) {
        pageTransition = value
        prefs.edit().putString("pageTransition", value.name).apply()
    }

    /** משנה את גודל הרשת, ומזיז פריטים שכבר לא נכנסים למקום פנוי */
    fun updateGrid(columns: Int, rows: Int) {
        if (columns == gridColumns && rows == gridRows) return
        gridColumns = columns
        gridRows = rows
        prefs.edit().putInt("gridColumns", columns).putInt("gridRows", rows).apply()

        val kept = mutableListOf<HomeItem>()
        val moved = mutableListOf<HomeItem>()
        home.sortedWith(compareBy({ it.page }, { it.y }, { it.x })).forEach { item ->
            val resized = item.copy(w = item.w.coerceAtMost(columns), h = item.h.coerceAtMost(rows))
            val fits = resized.x + resized.w <= columns && resized.y + resized.h <= rows
            val overlaps = kept.any {
                it.page == resized.page &&
                    resized.x < it.x + it.w && it.x < resized.x + resized.w &&
                    resized.y < it.y + it.h && it.y < resized.y + resized.h
            }
            if (fits && !overlaps) kept += resized else moved += resized
        }
        home.clear()
        home.addAll(kept)
        moved.forEach { item ->
            val (page, x, y) = findFreeSpot(item.page, item.w, item.h)
            home.add(item.copy(page = page, x = x, y = y))
            ensurePage(page)
        }
        save()
    }

    init {
        load()
    }

    // סידור המגירה לפי קטגוריות מ-Google Play
    var drawerCategories by mutableStateOf(prefs.getBoolean("drawerCategories", false))
        private set

    // הקטגוריות מוצגות כתיקיות (כמו בגלקסי) או ככותרות
    var drawerFolders by mutableStateOf(prefs.getBoolean("drawerFolders", true))
        private set

    fun updateDrawerFolders(enabled: Boolean) {
        drawerFolders = enabled
        prefs.edit().putBoolean("drawerFolders", enabled).apply()
    }

    fun updateDrawerCategories(enabled: Boolean) {
        drawerCategories = enabled
        prefs.edit().putBoolean("drawerCategories", enabled).apply()
    }

    /**
     * הצבע של כל האייקונים: מקורי, לפי הטפט, או צבע קבוע.
     * מי שהפעיל בעבר "סמלים בצבעי הטפט" ממשיך עם הטפט.
     */
    var iconColorMode by mutableStateOf(
        prefs.getString("iconColorMode", null)
            ?: if (prefs.getBoolean("themed", false)) IconColorMode.WALLPAPER else IconColorMode.ORIGINAL
    )
        private set

    fun updateIconColorMode(mode: String) {
        iconColorMode = mode
        prefs.edit().putString("iconColorMode", mode).apply()
    }

    fun updateThemedIcons(enabled: Boolean) {
        themedIcons = enabled
        prefs.edit().putBoolean("themed", enabled).apply()
    }

    // ---------- Dock ----------

    fun initDock(keys: List<String>) {
        dock.clear()
        dock.addAll(keys.take(DOCK_MAX))
        dockInitialized = true
        save()
    }

    fun addToDock(key: String, index: Int): Boolean {
        if (dock.size >= DOCK_MAX || key in dock) return false
        dock.add(index.coerceIn(0, dock.size), key)
        save()
        return true
    }

    fun moveInDock(from: Int, to: Int) {
        if (from !in dock.indices) return
        val key = dock.removeAt(from)
        dock.add(to.coerceIn(0, dock.size), key)
        save()
    }

    // ---------- אפליקציות מוסתרות ----------

    val hiddenApps = mutableStateListOf<String>().apply {
        addAll(prefs.getStringSet("hiddenApps", emptySet()).orEmpty())
    }
    // ---------- מחוות ----------

    val gestureActions = mutableStateMapOf<LauncherGesture, GestureAction>().apply {
        LauncherGesture.entries.forEach { g ->
            put(g, runCatching { GestureAction.valueOf(prefs.getString("gesture_${g.name}", null) ?: "") }
                .getOrDefault(g.default))
        }
    }
    // האפליקציה שנפתחת, למחוות שהוגדרו ל"פתיחת אפליקציה"
    val gestureApps = mutableStateMapOf<LauncherGesture, String>().apply {
        LauncherGesture.entries.forEach { g -> prefs.getString("gestureApp_${g.name}", null)?.let { put(g, it) } }
    }

    fun setGesture(gesture: LauncherGesture, action: GestureAction, appKey: String? = null) {
        gestureActions[gesture] = action
        val edit = prefs.edit().putString("gesture_${gesture.name}", action.name)
        if (action == GestureAction.APP && appKey != null) {
            gestureApps[gesture] = appKey
            edit.putString("gestureApp_${gesture.name}", appKey)
        }
        edit.apply()
    }

    fun resetGestures() {
        val edit = prefs.edit()
        LauncherGesture.entries.forEach { g ->
            gestureActions[g] = g.default
            edit.remove("gesture_${g.name}").remove("gestureApp_${g.name}")
        }
        gestureApps.clear()
        edit.apply()
    }

    // ---------- קוד לאפליקציות המוסתרות ----------

    var hasPin by mutableStateOf(prefs.getString("pinHash", null) != null)
        private set

    // נעילה בטביעת אצבע בלבד (חלופה לקוד)
    var biometricLock by mutableStateOf(prefs.getBoolean("biometricLock", false))
        private set

    // ---------- מצב בטוח ----------

    var safeMode by mutableStateOf(prefs.getBoolean("safeMode", false))
        private set
    val safeApps = mutableStateListOf<String>().apply {
        addAll(prefs.getStringSet("safeApps", emptySet()).orEmpty())
    }

    /** חסימת האפליקציות שאינן מאושרות דרך Shizuku, במקום נעילה מלאה */
    var shizukuBlock by mutableStateOf(prefs.getBoolean("shizukuBlock", false))
        private set

    fun updateShizukuBlock(enabled: Boolean) {
        shizukuBlock = enabled
        prefs.edit().putBoolean("shizukuBlock", enabled).apply()
    }

    /** היכן מוצגים שמות האפליקציות מתחת לאייקונים */
    var labelMode by mutableStateOf(
        runCatching { LabelMode.valueOf(prefs.getString("labelMode", null) ?: "") }.getOrNull()
        // תאימות לאחור: מי שכיבה את השמות במסך הבית ממשיך לראות אותם רק במגירה
            ?: if (prefs.getBoolean("homeLabels", true)) LabelMode.ALL else LabelMode.DRAWER
    )
        private set

    fun updateLabelMode(mode: LabelMode) {
        labelMode = mode
        prefs.edit().putString("labelMode", mode.name).apply()
    }

    val homeLabels: Boolean get() = labelMode == LabelMode.ALL
    val drawerLabels: Boolean get() = labelMode != LabelMode.NONE

    /** נעילת הפריסה: אי אפשר להזיז או להסיר אייקונים בגרירה */
    var lockLayout by mutableStateOf(prefs.getBoolean("lockLayout", false))
        private set

    fun updateLockLayout(enabled: Boolean) {
        lockLayout = enabled
        prefs.edit().putBoolean("lockLayout", enabled).apply()
    }

    /** אפליקציות של Samsung ו-Google בתיקיות משלהן במגירה */
    var brandFolders by mutableStateOf(prefs.getBoolean("brandFolders", false))
        private set

    fun updateBrandFolders(enabled: Boolean) {
        brandFolders = enabled
        prefs.edit().putBoolean("brandFolders", enabled).commit()
    }

    /** הטפט זז מעט במעבר בין דפי מסך הבית */
    var wallpaperParallax by mutableStateOf(prefs.getBoolean("wallpaperParallax", true))
        private set

    fun updateWallpaperParallax(enabled: Boolean) {
        wallpaperParallax = enabled
        prefs.edit().putBoolean("wallpaperParallax", enabled).apply()
    }

    /** האם מסך הבית מסתובב עם המכשיר. כבוי כברירת מחדל, כמו ברוב הלאנצ'רים */
    var rotateHome by mutableStateOf(prefs.getBoolean("rotateHome", false))
        private set

    fun updateRotateHome(enabled: Boolean) {
        rotateHome = enabled
        prefs.edit().putBoolean("rotateHome", enabled).apply()
    }

    /** חסימת מגירת ההתראות בזמן נעילה, באמצעות שירות נגישות */
    var blockShade by mutableStateOf(prefs.getBoolean("blockShade", false))
        private set

    fun updateBlockShade(enabled: Boolean) {
        blockShade = enabled
        prefs.edit().putBoolean("blockShade", enabled).apply()
    }

    // ---------- נעילה לזמן קצוב ----------

    /** עד מתי הטלפון נעול (0 = לא נעול) */
    var lockUntil by androidx.compose.runtime.mutableLongStateOf(prefs.getLong("lockUntil", 0L))
        private set
    /** true = אי אפשר לבטל עד שהזמן נגמר */
    var lockStrict by mutableStateOf(prefs.getBoolean("lockStrict", false))
        private set
    val lockApps = mutableStateListOf<String>().apply {
        addAll(prefs.getStringSet("lockApps", emptySet()).orEmpty())
    }

    fun startTimedLock(until: Long, strict: Boolean, apps: Set<String>) {
        lockUntil = until
        lockStrict = strict
        lockApps.clear()
        lockApps.addAll(apps)
        prefs.edit()
            .putLong("lockUntil", until)
            .putBoolean("lockStrict", strict)
            .putStringSet("lockApps", apps)
            .apply()
    }

    fun endTimedLock() {
        lockUntil = 0L
        lockStrict = false
        prefs.edit().putLong("lockUntil", 0L).putBoolean("lockStrict", false).apply()
    }

    fun updateSafeMode(enabled: Boolean) {
        safeMode = enabled
        prefs.edit().putBoolean("safeMode", enabled).apply()
    }

    fun setSafeApps(keys: Set<String>) {
        safeApps.clear()
        safeApps.addAll(keys)
        prefs.edit().putStringSet("safeApps", keys).apply()
    }

    fun updateBiometricLock(enabled: Boolean) {
        biometricLock = enabled
        prefs.edit().putBoolean("biometricLock", enabled).apply()
        if (enabled) setPin(null)
    }

    /** null = הסרת הקוד */
    fun setPin(pin: String?) {
        if (pin == null) {
            prefs.edit().remove("pinHash").remove("pinSalt").apply()
            hasPin = false
        } else {
            val salt = PinCode.newSalt()
            prefs.edit().putString("pinSalt", salt).putString("pinHash", PinCode.hash(pin, salt)).apply()
            hasPin = true
        }
    }

    // קוד חירום: יציאה מנעילה מלאה כשטביעת האצבע לא עובדת
    var hasEmergencyPin by mutableStateOf(prefs.getString("emergencyHash", null) != null)
        private set

    fun setEmergencyPin(pin: String?) {
        if (pin == null) {
            prefs.edit().remove("emergencyHash").remove("emergencySalt").apply()
            hasEmergencyPin = false
        } else {
            val salt = PinCode.newSalt()
            prefs.edit().putString("emergencySalt", salt).putString("emergencyHash", PinCode.hash(pin, salt)).apply()
            hasEmergencyPin = true
        }
    }

    fun checkEmergencyPin(pin: String): Boolean {
        val hash = prefs.getString("emergencyHash", null) ?: return false
        val salt = prefs.getString("emergencySalt", "") ?: ""
        return PinCode.hash(pin, salt) == hash
    }

    fun checkPin(pin: String): Boolean {
        val hash = prefs.getString("pinHash", null) ?: return true
        val salt = prefs.getString("pinSalt", "") ?: ""
        return PinCode.hash(pin, salt) == hash
    }

    /** מסתיר אפליקציה, ומוריד אותה גם ממסך הבית ומה-Dock */
    fun hideApp(key: String) {
        if (key !in hiddenApps) hiddenApps.add(key)
        dock.remove(key)
        home.removeAll { it.appKey == key }
        prefs.edit().putStringSet("hiddenApps", hiddenApps.toSet()).apply()
        save()
    }

    fun unhideApp(key: String) {
        hiddenApps.remove(key)
        prefs.edit().putStringSet("hiddenApps", hiddenApps.toSet()).apply()
    }

    fun removeFromDock(index: Int) {
        if (index in dock.indices) {
            dock.removeAt(index)
            save()
        }
    }

    fun replaceInDock(index: Int, key: String) {
        if (index in dock.indices) {
            dock[index] = key
            save()
        }
    }

    // ---------- מסך הבית ----------

    fun isAreaFree(page: Int, x: Int, y: Int, w: Int, h: Int, ignoreId: Long = -1): Boolean {
        if (x < 0 || y < 0 || x + w > gridColumns || y + h > gridRows) return false
        return home.none {
            it.id != ignoreId && it.page == page &&
                x < it.x + it.w && it.x < x + w &&
                y < it.y + it.h && it.y < y + h
        }
    }

    private fun findFreeSpot(startPage: Int, w: Int, h: Int): Triple<Int, Int, Int> {
        var page = startPage.coerceAtLeast(0)
        while (true) {
            for (y in 0..gridRows - h) {
                for (x in 0..gridColumns - w) {
                    if (isAreaFree(page, x, y, w, h)) return Triple(page, x, y)
                }
            }
            page++
        }
    }

    fun addApp(key: String, page: Int, x: Int, y: Int): Boolean {
        if (!isAreaFree(page, x, y, 1, 1)) return false
        add(HomeItem(id = nextId++, appKey = key, page = page, x = x, y = y))
        return true
    }

    /** מוסיף במקום הפנוי הראשון ומחזיר את מספר הדף */
    fun addAppAnywhere(key: String, startPage: Int): Int {
        val (page, x, y) = findFreeSpot(startPage, 1, 1)
        add(HomeItem(id = nextId++, appKey = key, page = page, x = x, y = y))
        return page
    }

    fun addWidget(widgetId: Int, w: Int, h: Int, startPage: Int): Int {
        val width = w.coerceIn(1, gridColumns)
        val height = h.coerceIn(1, gridRows)
        val (page, x, y) = findFreeSpot(startPage, width, height)
        add(HomeItem(id = nextId++, widgetId = widgetId, page = page, x = x, y = y, w = width, h = height))
        return page
    }

    fun moveItem(id: Long, page: Int, x: Int, y: Int): Boolean {
        val index = home.indexOfFirst { it.id == id }
        if (index < 0) return false
        val item = home[index]
        if (!isAreaFree(page, x, y, item.w, item.h, ignoreId = id)) return false
        home[index] = item.copy(page = page, x = x, y = y)
        ensurePage(page)
        save()
        return true
    }

    /** שינוי גודל של ווידג'ט. נכשל אם הגודל חורג מהרשת או מתנגש בפריט אחר */
    fun resizeItem(id: Long, w: Int, h: Int): Boolean {
        val index = home.indexOfFirst { it.id == id }
        if (index < 0) return false
        val item = home[index]
        val newW = w.coerceIn(1, gridColumns)
        val newH = h.coerceIn(1, gridRows)
        if (newW == item.w && newH == item.h) return false
        if (!isAreaFree(item.page, item.x, item.y, newW, newH, ignoreId = id)) return false
        home[index] = item.copy(w = newW, h = newH)
        save()
        return true
    }

    fun removeItem(id: Long) {
        if (home.removeAll { it.id == id }) save()
    }

    fun addPage() {
        pageCount++
        save()
    }

    /** מוחק דפים ריקים בסוף, אבל לא את הדף שעליו עומדים */
    fun trimEmptyPages(keepPage: Int) {
        var changed = false
        while (pageCount > 1 && pageCount - 1 > keepPage && home.none { it.page == pageCount - 1 }) {
            pageCount--
            changed = true
        }
        if (changed) save()
    }

    /** מנקה אפליקציות שהוסרו וווידג'טים שכבר לא קיימים */
    fun prune(validKeys: Set<String>, isWidgetValid: (Int) -> Boolean) {
        val dockChanged = dock.removeAll { it !in validKeys }
        val homeChanged = home.removeAll {
            if (it.isWidget) !isWidgetValid(it.widgetId) else it.appKey !in validKeys
        }
        if (dockChanged || homeChanged) save()
    }

    // ---------- תיקיות במסך הבית ----------

    /** הפריט שנמצא בתא מסוים, אם יש. ווידג'טים תופסים כמה תאים */
    fun itemAt(page: Int, x: Int, y: Int, ignoreId: Long = -1): HomeItem? =
        home.firstOrNull {
            it.page == page && it.id != ignoreId &&
                x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h
        }

    /** הופך אייקון בודד לתיקייה עם שתי אפליקציות, או מוסיף לתיקייה קיימת */
    fun mergeIntoFolder(targetId: Long, appKey: String): Boolean {
        val index = home.indexOfFirst { it.id == targetId }
        if (index < 0) return false
        val target = home[index]
        if (target.isWidget) return false
        val existing = if (target.isFolder) target.folderApps else listOfNotNull(target.appKey)
        if (appKey in existing) return false
        home[index] = target.copy(
            appKey = null,
            folderApps = existing + appKey,
            folderName = target.folderName
        )
        save()
        return true
    }

    /** תיקייה שלמה למסך הבית, למשל כשגוררים תיקייה מהמגירה */
    fun addFolder(apps: List<String>, name: String, page: Int, x: Int, y: Int): Boolean {
        val unique = apps.distinct()
        if (unique.isEmpty() || !isAreaFree(page, x, y, 1, 1)) return false
        add(
            if (unique.size == 1) HomeItem(id = nextId++, appKey = unique.first(), page = page, x = x, y = y)
            else HomeItem(id = nextId++, page = page, x = x, y = y, folderApps = unique, folderName = name)
        )
        return true
    }

    fun addFolderAnywhere(apps: List<String>, name: String, startPage: Int): Int {
        val (page, x, y) = findFreeSpot(startPage, 1, 1)
        addFolder(apps, name, page, x, y)
        return page
    }

    fun renameHomeFolder(id: Long, name: String) {
        val index = home.indexOfFirst { it.id == id }
        if (index < 0) return
        home[index] = home[index].copy(folderName = name.trim())
        save()
    }

    /** מוציא אפליקציה מתיקייה. תיקייה שנשארת עם אפליקציה אחת חוזרת להיות אייקון */
    fun removeFromFolder(id: Long, appKey: String) {
        val index = home.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = home[index]
        val left = item.folderApps.filterNot { it == appKey }
        home[index] = when (left.size) {
            0 -> item.copy(folderApps = emptyList(), appKey = null)
            1 -> item.copy(folderApps = emptyList(), appKey = left.first(), folderName = "")
            else -> item.copy(folderApps = left)
        }
        if (left.isEmpty()) home.removeAt(index)
        save()
    }

    private fun add(item: HomeItem) {
        home.add(item)
        ensurePage(item.page)
        save()
    }

    private fun ensurePage(page: Int) {
        if (page + 1 > pageCount) pageCount = page + 1
    }

    // ---------- שמירה וטעינה ----------

    private fun save() {
        val items = JSONArray()
        home.forEach { item ->
            items.put(JSONObject().apply {
                put("id", item.id)
                item.appKey?.let { put("app", it) }
                put("widget", item.widgetId)
                put("page", item.page)
                put("x", item.x)
                put("y", item.y)
                put("w", item.w)
                put("h", item.h)
                if (item.isFolder) {
                    put("folder", JSONArray(item.folderApps))
                    put("folderName", item.folderName)
                }
            })
        }
        prefs.edit()
            .putString("dock", JSONArray(dock.toList()).toString())
            .putString("home", items.toString())
            .putInt("pages", pageCount)
            .putBoolean("dockInit", dockInitialized)
            .putLong("nextId", nextId)
            .apply()
    }

    private fun load() {
        try {
            dockInitialized = prefs.getBoolean("dockInit", false)
            pageCount = prefs.getInt("pages", 1).coerceAtLeast(1)
            nextId = prefs.getLong("nextId", 1L)
            prefs.getString("dock", null)?.let { json ->
                val array = JSONArray(json)
                for (i in 0 until array.length()) dock.add(array.getString(i))
            }
            prefs.getString("home", null)?.let { json ->
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    home.add(
                        HomeItem(
                            id = o.getLong("id"),
                            appKey = if (o.has("app")) o.getString("app") else null,
                            widgetId = o.optInt("widget", -1),
                            page = o.getInt("page"),
                            x = o.getInt("x"),
                            y = o.getInt("y"),
                            w = o.optInt("w", 1),
                            h = o.optInt("h", 1),
                            folderApps = o.optJSONArray("folder")?.let { array ->
                                (0 until array.length()).map { array.getString(it) }
                            } ?: emptyList(),
                            folderName = o.optString("folderName", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            dock.clear()
            home.clear()
            pageCount = 1
            dockInitialized = false
        }
    }
}
