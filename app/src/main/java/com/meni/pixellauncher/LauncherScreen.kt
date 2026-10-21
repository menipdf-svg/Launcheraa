package com.meni.pixellauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.biometric.BiometricManager
import androidx.compose.foundation.Image
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DragSource { DRAWER, HOME, DOCK }

/** מצב הגרירה הנוכחית של אייקון */
class DragState {
    var app by mutableStateOf<AppInfo?>(null)
    var source by mutableStateOf(DragSource.DRAWER)
    var homeItemId by mutableLongStateOf(-1L)
    var dockIndex by mutableIntStateOf(-1)
    var position by mutableStateOf(Offset.Zero) // מיקום האצבע במסך
    var active by mutableStateOf(false)          // האצבע זזה, הגרירה התחילה באמת
    var travelled = 0f
    // גרירה של תיקייה שלמה מהמגירה: רשימת האפליקציות והשם
    var folderApps by mutableStateOf<List<String>>(emptyList())
    var folderName = ""

    fun reset() {
        app = null
        active = false
        homeItemId = -1L
        dockIndex = -1
        travelled = 0f
        folderApps = emptyList()
        folderName = ""
    }
}

/** המיקומים על המסך של האזורים שאפשר לשחרר עליהם */
class DropTargets {
    var pager: Rect = Rect.Zero
    var dock: Rect = Rect.Zero
    var remove: Rect = Rect.Zero
}

private val GRID_PAD_H = 20.dp
private val GRID_PAD_V = 8.dp

@Composable
fun LauncherScreen(
    host: LauncherHost,
    refreshKey: Int,
    homePressed: Int,
    volumeDoublePress: Int = 0,
    stopCount: Int = 0
) {
    val context = LocalContext.current
    val store = host.store

    /** פתיחת אפליקציה: נרשם בשביל ההצעות החכמות ומוריד את סימון "חדש" */
    fun launchApp(app: AppInfo) {
        Suggestions.record(context, app.key)
        store.markOpened(app.key)
        AppsRepository.launch(context, app)
    }
    // יש גישה להתראות? (נבדק שוב בכל חזרה ללאנצ'ר)
    val notificationAccess = remember(refreshKey) { NotifService.hasAccess(context) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val naturalDirection = LocalLayoutDirection.current

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    // אפליקציות מוסתרות לא מופיעות במסך הבית, ב-Dock, במגירה ובחיפוש
    val hiddenKeys = store.hiddenApps.toSet()
    // במצב בטוח: רק האפליקציות שאושרו
    val safeKeys = store.safeApps.toSet()
    // נעילה לזמן קצוב: כמו מצב בטוח, עם רשימה וזמן משלה
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val timedLock = store.lockUntil > now
    val safeActive = store.safeMode || timedLock
    val allowedKeys = if (timedLock) store.lockApps.toSet() else safeKeys
    val visibleApps = remember(apps, hiddenKeys, allowedKeys, safeActive) {
        if (safeActive) apps.filter { it.key in allowedKeys } else apps.filter { it.key !in hiddenKeys }
    }
    var timedLockDialog by remember { mutableStateOf(false) }
    val appsByKey = remember(visibleApps) { visibleApps.associateBy { it.key } }
    var hiddenOpen by remember { mutableStateOf(false) }
    var pinMode by remember { mutableStateOf<PinMode?>(null) }

    var drawerOpen by remember { mutableStateOf(false) }
    var focusSearch by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf<String?>(null) }
    var homeMenu by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    var widgetPickerOpen by remember { mutableStateOf(false) }
    var widgetToRemove by remember { mutableStateOf<HomeItem?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var pinAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pinEmergency by remember { mutableStateOf(false) }

    // ---------- נעילה מלאה (מצב קיוסק) ----------
    var isOwner by remember { mutableStateOf(host.isDeviceOwner()) }
    LaunchedEffect(refreshKey) { isOwner = host.isDeviceOwner() }
    LaunchedEffect(store.rotateHome) { host.setRotationAllowed(store.rotateHome) }

    // מצב בטוח + מנהל מכשיר = נעילה ברמת המערכת לאפליקציות המותרות בלבד
    LaunchedEffect(safeActive, allowedKeys, isOwner) {
        if (isOwner) host.setKiosk(safeActive, allowedKeys.map { it.substringBefore('/') }.distinct())
    }

    // כשהלאנצ'ר יורד לרקע (מסך כבוי, אפליקציה אחרת) המסתור נסגר וצריך שוב אימות
    LaunchedEffect(stopCount) {
        hiddenOpen = false
        // במצב בטוח ההגדרות ננעלות שוב כשיוצאים מהלאנצ'ר
        if (safeActive) settingsOpen = false
    }

    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    /**
     * חסימה דרך Shizuku: משעה את כל האפליקציות שאינן מאושרות, או משחררת אותן.
     * חלופה לנעילה מלאה, למי שאינו מגדיר את הלאנצ'ר כמנהל מכשיר.
     */
    suspend fun applyShizukuBlock(block: Boolean) {
        if (ShizukuBridge.state(context) != ShizukuBridge.State.READY) {
            if (block) toast("Shizuku אינו זמין, ולכן האפליקציות לא נחסמו")
            return
        }
        val allowedPackages = allowedKeys.map { it.substringBefore('/') }.toSet()
        val all = apps.map { it.packageName }.distinct()
        val failure = if (block) {
            ShizukuBridge.suspendApps(all.filterNot { it in allowedPackages }, true)
        } else {
            ShizukuBridge.suspendApps(all, false)
        }
        if (failure != null) toast(failure)
    }

    // חסימה וביטול חסימה עוקבים אחרי המצב הבטוח ואחרי הנעילה לזמן קצוב
    LaunchedEffect(safeActive, allowedKeys, store.shizukuBlock) {
        if (store.shizukuBlock) applyShizukuBlock(safeActive)
    }

    // ספירה לאחור של הנעילה, ושחרור אוטומטי כשהזמן נגמר
    LaunchedEffect(store.lockUntil, refreshKey) {
        now = System.currentTimeMillis()
        while (store.lockUntil > now) {
            delay(1000)
            now = System.currentTimeMillis()
        }
        if (store.lockUntil != 0L) {
            store.endTimedLock()
            toast("הנעילה לזמן קצוב הסתיימה")
        }
    }

    /** אימות הבעלים: טביעת אצבע או קוד, לפי מה שהוגדר. בלי נעילה ממשיכים מיד */
    fun authenticateOwner(title: String, onSuccess: () -> Unit) {
        when {
            store.biometricLock -> {
                // קוד החירום (אם הוגדר) משמש כשטביעת האצבע לא עובדת
                val fallback: (() -> Unit)? = if (store.hasEmergencyPin) {
                    {
                        pinAction = onSuccess
                        pinEmergency = true
                        pinMode = PinMode.UNLOCK
                    }
                } else {
                    null
                }
                when (host.biometricStatus()) {
                    BiometricManager.BIOMETRIC_SUCCESS ->
                        host.authenticate(title, "אימות בטביעת אצבע", "קוד חירום", fallback, onSuccess)
                    // כל טביעות האצבע נמחקו מהמכשיר (דורש את קוד המכשיר): לא נשארים נעולים בחוץ
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> if (fallback != null) fallback() else {
                        store.updateBiometricLock(false)
                        onSuccess()
                    }
                    else -> if (fallback != null) fallback() else toast("חיישן טביעת האצבע אינו זמין כעת")
                }
            }
            store.hasPin -> {
                pinAction = onSuccess
                pinEmergency = false
                pinMode = PinMode.UNLOCK
            }
            else -> onSuccess()
        }
    }

    /** פתיחת המסתור (לא זמין במצב בטוח) */
    fun requestHidden() {
        if (safeActive) {
            toast("אינו זמין במצב בטוח")
            return
        }
        authenticateOwner("אפליקציות מוסתרות") { hiddenOpen = true }
    }

    /** ביטול נעילה לזמן קצוב לפני הזמן (רק אם לא נבחר "ללא אפשרות ביטול") */
    fun cancelTimedLock() {
        if (store.lockStrict) {
            toast("לא ניתן לבטל. הנעילה תשתחרר בשעה ${TimedLockText.clock(store.lockUntil)}")
            return
        }
        authenticateOwner("ביטול הנעילה") {
            store.endTimedLock()
            toast("הנעילה בוטלה")
        }
    }

    /** במצב בטוח ההגדרות דורשות אימות */
    fun requestSettings() {
        when {
            timedLock && store.lockStrict -> toast("הטלפון נעול עד השעה ${TimedLockText.clock(store.lockUntil)}")
            safeActive -> authenticateOwner("הגדרות") { settingsOpen = true }
            else -> settingsOpen = true
        }
    }

    // קטגוריות למגירה
    var categories by remember { mutableStateOf<Map<String, DrawerGroup>?>(null) }
    var categoryProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var categoryRefresh by remember { mutableIntStateOf(0) }
    var overrideCount by remember { mutableIntStateOf(CategoryRepository.overrideCount(context)) }
    var moveTarget by remember { mutableStateOf<AppInfo?>(null) }
    var renameTarget by remember { mutableStateOf<DrawerGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<DrawerGroup?>(null) }
    var renameApp by remember { mutableStateOf<AppInfo?>(null) }
    // בחירת אייקון מהגלריה. iconRefresh מאלץ טעינה מחדש אחרי שמירה
    var iconTarget by remember { mutableStateOf<AppInfo?>(null) }
    var iconRefresh by remember { mutableIntStateOf(0) }
    // מחווה על אייקון: מפתח האפליקציה ← מפתח האפליקציה שתיפתח בהחלקה מעלה
    var swipeTarget by remember { mutableStateOf<AppInfo?>(null) }
    // תיקייה פתוחה במסך הבית, ותיקייה שמשנים את שמה
    var openFolder by remember { mutableStateOf<Long?>(null) }
    // ווידג'ט במצב שינוי גודל: גרירת הידיות משנה כמה תאים הוא תופס
    var resizingWidget by remember { mutableStateOf<Long?>(null) }
    // סידור בתיקיות: התוכנית מוצגת לאישור לפני שמשהו זז
    var organizePlan by remember { mutableStateOf<List<Pair<String, List<HomeItem>>>?>(null) }
    // חלון העריכה של אפליקציה: שם, אייקון, מחווה, תיקייה והסתרה
    var editTarget by remember { mutableStateOf<AppInfo?>(null) }
    var folderRename by remember { mutableStateOf<HomeItem?>(null) }
    var swipeMap by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(refreshKey) { swipeMap = AppSwipes.all(context) }

    // טוען מחדש את השיוך של כל האפליקציות אחרי שינוי ידני
    fun reloadCategories() {
        categories = CategoryRepository.cached(context, apps.map { it.packageName }.distinct())
        overrideCount = CategoryRepository.overrideCount(context)
    }

    val drag = remember { DragState() }
    val targets = remember { DropTargets() }
    val pagerState = rememberPagerState { store.pageCount }

    // טפט נגלל: הטפט זז מעט עם המעבר בין הדפים, כמו בלאנצ'ר של פיקסל
    val rootView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(store.wallpaperParallax, store.pageCount) {
        if (!store.wallpaperParallax) return@LaunchedEffect
        // מי שביטל אנימציות בהגדרות המערכת (נגישות) לא יקבל טפט זז
        val motionScale = android.provider.Settings.Global.getFloat(
            context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        if (motionScale == 0f) return@LaunchedEffect
        val wallpaper = android.app.WallpaperManager.getInstance(context)
        val pages = store.pageCount
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
            .collect { position ->
                val token = rootView.windowToken ?: return@collect
                val step = if (pages > 1) 1f / (pages - 1) else 0f
                val offset = if (pages > 1) (position / (pages - 1)).coerceIn(0f, 1f) else 0.5f
                runCatching { wallpaper.setWallpaperOffsets(token, offset, 0f) }
                runCatching { wallpaper.setWallpaperOffsetSteps(step, 0f) }
            }
    }

    val colors = MaterialTheme.colorScheme
    // צבעי הטפט, וממנם הצבע הכללי של האייקונים לפי מה שנבחר בהגדרות
    val wallpaperTheme = IconTheme(colors.primaryContainer.toArgb(), colors.onPrimaryContainer.toArgb())
    val iconTheme = IconColorMode.theme(store.iconColorMode, wallpaperTheme)

    val iconShape = store.iconShape
    val iconSize = 54.dp * store.iconScale

    // בסיבוב המסך הגובה מצטמצם, ולכן ה-Dock מתכווץ יחד עם שאר המסך
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val dockIcon = if (isLandscape) minOf(iconSize, 44.dp) else iconSize
    val dockHeight = if (isLandscape) 62.dp else 84.dp

    // גודל תא מינימלי שבו אייקון עם שם עדיין נראה כראוי
    val minCellH = iconSize + 30.dp

    val iconPack = store.iconPack
    LaunchedEffect(refreshKey, iconTheme, iconShape, iconPack, iconRefresh, wallpaperTheme) {
        val loaded = withContext(Dispatchers.IO) {
            AppsRepository.loadApps(context, iconTheme, iconShape, iconPack, wallpaperTheme)
        }
        apps = loaded
        if (loaded.isNotEmpty()) {
            if (!store.dockInitialized) {
                store.initDock(AppsRepository.defaultDockKeys(context, loaded))
            }
            store.prune(loaded.map { it.key }.toSet()) { host.isWidgetValid(it) }
            store.trackInstalled(loaded.map { it.key }.toSet())
        }
    }

    LaunchedEffect(apps, store.drawerCategories, categoryRefresh, store.brandFolders) {
        if (!store.drawerCategories || apps.isEmpty()) {
            categories = null
            categoryProgress = null
            return@LaunchedEffect
        }
        val packages = apps.map { it.packageName }.distinct()
        // מה שכבר ידוע מוצג מיד, ואז משלימים מ-Google Play
        categories = withContext(Dispatchers.IO) { CategoryRepository.cached(context, packages) }
        categories = CategoryRepository.categorize(context, packages) { done, total ->
            categoryProgress = if (done < total) done to total else null
        }
        categoryProgress = null
    }

    LaunchedEffect(homePressed) {
        pinMode = null
        hiddenOpen = false
        settingsOpen = false
        drawerOpen = false
        widgetPickerOpen = false
        openMenu = null
        homeMenu = null
        if (homePressed > 0 && pagerState.currentPage != 0) pagerState.animateScrollToPage(0)
    }

    // במסך בית Back לא יוצא, רק סוגר מה שפתוח
    BackHandler {
        pinMode = null
        hiddenOpen = false
        settingsOpen = false
        widgetPickerOpen = false
        drawerOpen = false
        homeMenu = null
    }

    // ---------- מחוות ----------

    fun performGesture(gesture: LauncherGesture) {
        when (store.gestureActions[gesture] ?: GestureAction.NONE) {
            GestureAction.NONE -> Unit
            GestureAction.DRAWER -> {
                focusSearch = false
                drawerOpen = true
            }
            GestureAction.SEARCH -> {
                focusSearch = true
                drawerOpen = true
            }
            GestureAction.NOTIFICATIONS -> AppsRepository.expandNotifications(context)
            GestureAction.HIDDEN -> requestHidden()
            GestureAction.SETTINGS -> requestSettings()
            GestureAction.WIDGETS -> if (!safeActive) widgetPickerOpen = true
            GestureAction.LOCK_SCREEN -> if (!ShadeGuardService.lockScreen()) {
                toast("נעילת המסך דורשת אישור נגישות, בהגדרות ← פרטיות ונעילה")
            }
            GestureAction.APP -> {
                val key = store.gestureApps[gesture]
                (if (safeActive) visibleApps else apps)
                    .firstOrNull { it.key == key }?.let { launchApp(it) }
            }
        }
    }
    val multiFingerEnabled = listOf(LauncherGesture.TWO_UP, LauncherGesture.TWO_DOWN, LauncherGesture.THREE_SIDE)
        .any { store.gestureActions[it] != GestureAction.NONE }

    // לחיצה כפולה על ווליום עליון (מגיעה מה-Activity)
    LaunchedEffect(volumeDoublePress) {
        if (volumeDoublePress > 0) performGesture(LauncherGesture.VOLUME_UP_TWICE)
    }

    // ---------- עזרי מיקום ----------

    fun gridRect(): Rect {
        val p = targets.pager
        val h = with(density) { GRID_PAD_H.toPx() }
        val v = with(density) { GRID_PAD_V.toPx() }
        return Rect(p.left + h, p.top + v, p.right - h, p.bottom - v)
    }

    fun overGrid(pos: Offset): Boolean {
        val r = gridRect()
        return r.width > 0f && r.contains(pos)
    }

    /** התא שמתחת לאצבע. לרוחב הפריסה נבנית מחדש לפי המקום, ולכן אין תא קבוע */
    fun cellAt(pos: Offset): Pair<Int, Int>? {
        if (isLandscape || !overGrid(pos)) return null
        val r = gridRect()
        val cx = ((pos.x - r.left) / (r.width / store.gridColumns)).toInt()
            .coerceIn(0, store.gridColumns - 1)
        val cy = ((pos.y - r.top) / (r.height / store.gridRows)).toInt()
            .coerceIn(0, store.gridRows - 1)
        return cx to cy
    }

    // ---------- תפריט לחיצה ארוכה ----------

    fun actionsFor(app: AppInfo, source: DragSource, homeItemId: Long, dockIndex: Int): List<MenuAction> {
        // במצב בטוח: רק קיצורי הדרך של האפליקציה, בלי עריכה, הסתרה או הסרה
        if (safeActive) {
            val safe = AppsRepository.shortcuts(context, app.packageName).map { s ->
                MenuAction(s.label, s.icon) { AppsRepository.startShortcut(context, s) }
            }
            return safe.ifEmpty { listOf(MenuAction("מצב בטוח פעיל", vector = Icons.Outlined.Lock)) }
        }
        val list = mutableListOf<MenuAction>()
        val shortcuts = AppsRepository.shortcuts(context, app.packageName)
        shortcuts.forEach { s ->
            list += MenuAction(s.label, s.icon) { AppsRepository.startShortcut(context, s) }
        }
        // שורת הסמלים שבהמשך מוסיפה קו מפריד משלה, ולכן אין צורך בעוד אחד
        if (shortcuts.isEmpty() && !AppsRepository.canShowShortcuts(context)) {
            list += MenuAction("להצגת קיצורי דרך יש להגדיר את PixelLauncher כמסך הבית", vector = Icons.Outlined.Settings) {
                AppsRepository.openHomeSettings(context)
            }
        }

        // פעולות הלאנצ'ר: סמלים בלבד, בשורה אחת
        when (source) {
            DragSource.DRAWER -> list += MenuAction(
                "הוספה למסך הבית", vector = Icons.Outlined.Add, iconOnly = true
            ) {
                val page = store.addAppAnywhere(app.key, pagerState.currentPage)
                drawerOpen = false
                scope.launch { pagerState.animateScrollToPage(page) }
            }
            DragSource.HOME -> list += MenuAction(
                "הסרה מהמסך", vector = Icons.Outlined.Close, iconOnly = true
            ) {
                store.removeItem(homeItemId)
                store.trimEmptyPages(pagerState.currentPage)
            }
            DragSource.DOCK -> list += MenuAction(
                "הסרה מה-Dock", vector = Icons.Outlined.Close, iconOnly = true
            ) {
                store.removeFromDock(dockIndex)
            }
        }
        // כל אפשרויות העריכה מרוכזות בחלון נפרד, כדי שהתפריט יישאר קצר
        list += MenuAction("עריכה", vector = Icons.Outlined.Create, iconOnly = true) { editTarget = app }
        list += MenuAction("פרטי האפליקציה", vector = Icons.Outlined.Info, iconOnly = true) {
            AppsRepository.openAppInfo(context, app.packageName)
        }
        // בדיקת עדכון: רק לאפליקציות שהותקנו מ-Google Play
        if (AppsRepository.installedFromPlay(context, app.packageName)) {
            list += MenuAction("בדיקת עדכון", vector = Icons.Outlined.Refresh, iconOnly = true) {
                AppsRepository.openPlayStore(context, app.packageName)
            }
        }
        if (!app.isSystem) {
            list += MenuAction("הסרת התקנה", vector = Icons.Outlined.Delete, iconOnly = true) {
                AppsRepository.uninstall(context, app.packageName)
            }
        }
        return list
    }

    /** תפריט של תיקייה במסך הבית */
    fun folderActions(item: HomeItem): List<MenuAction> {
        if (safeActive) return listOf(MenuAction("מצב בטוח פעיל", vector = Icons.Outlined.Lock))
        val large = item.w >= 2 && item.h >= 2
        return listOf(
            MenuAction("הסרה מהמסך", vector = Icons.Outlined.Close, iconOnly = true) {
                store.removeItem(item.id)
                store.trimEmptyPages(pagerState.currentPage)
            },
            MenuAction("שינוי שם", vector = Icons.Outlined.Create, iconOnly = true) { folderRename = item },
            MenuAction(
                if (large) "הקטנה" else "הגדלה",
                vector = if (large) ShrinkIcon else ExpandIcon,
                iconOnly = true
            ) {
                val ok = if (large) store.resizeItem(item.id, 1, 1) else store.resizeItem(item.id, 2, 2)
                if (!ok && !large) toast("אין מקום פנוי להגדלה")
            }
        )
    }

    // ---------- גרירה ושחרור ----------

    /** החלקה מעלה על אייקון: פותחת את האפליקציה שהוגדרה, אם הוגדרה */
    fun swipeActionFor(app: AppInfo): (() -> Unit)? {
        if (safeActive || store.lockLayout) return null
        val target = swipeMap[app.key] ?: return null
        val other = apps.firstOrNull { it.key == target } ?: return null
        return { launchApp(other) }
    }

    /** האם הפריט שנגרר במסך הבית הוא תיקייה */
    fun draggingHomeFolder(): Boolean =
        drag.source == DragSource.HOME && store.home.firstOrNull { it.id == drag.homeItemId }?.isFolder == true

    /** גרירת תיקייה מהמגירה למסך הבית */
    fun startFolderDrag(group: DrawerGroup, folderApps: List<AppInfo>, root: Offset) {
        if (folderApps.isEmpty()) return
        drag.app = folderApps.first()
        drag.source = DragSource.DRAWER
        drag.homeItemId = -1L
        drag.dockIndex = -1
        drag.position = root
        drag.active = false
        drag.travelled = 0f
        drag.folderApps = folderApps.map { it.key }
        drag.folderName = group.label
    }

    fun startDrag(
        app: AppInfo,
        source: DragSource,
        homeItemId: Long,
        dockIndex: Int,
        menuKey: String
    ): (Offset) -> Unit = { root ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        drag.folderApps = emptyList()
        drag.app = app
        drag.source = source
        drag.homeItemId = homeItemId
        drag.dockIndex = dockIndex
        drag.position = root
        drag.active = false
        drag.travelled = 0f
        openMenu = menuKey
    }

    val moveDrag: (Offset) -> Unit = { delta ->
        if (drag.app != null) {
            drag.position += delta
            drag.travelled += delta.getDistance()
            // בפריסה נעולה אייקון שכבר על המסך או ב-Dock אינו זז, רק התפריט נפתח
            val pinned = store.lockLayout && drag.source != DragSource.DRAWER
            if (!pinned && !drag.active && drag.travelled > with(density) { 12.dp.toPx() }) {
                // האצבע זזה: סוגרים את התפריט ומתחילים לגרור
                drag.active = true
                openMenu = null
                if (drag.source == DragSource.DRAWER) {
                    keyboard?.hide()
                    drawerOpen = false
                }
            }
        }
    }

    fun dropOnDock(app: AppInfo, pos: Offset) {
        val r = targets.dock
        val frac = ((pos.x - r.left) / r.width).coerceIn(0f, 0.999f)
        val n = store.dock.size
        if (drag.source == DragSource.DOCK) {
            store.moveInDock(drag.dockIndex, (frac * n).toInt())
            return
        }
        if (app.key in store.dock) return
        if (n < DOCK_MAX) {
            val added = store.addToDock(app.key, (frac * (n + 1)).toInt())
            if (added && drag.source == DragSource.HOME) store.removeItem(drag.homeItemId)
        } else if (drag.source == DragSource.HOME) {
            // Dock מלא: מחליפים מקום בין האייקון שגררנו לאייקון שב-Dock
            val slot = (frac * n).toInt()
            val replaced = store.dock[slot]
            val item = store.home.firstOrNull { it.id == drag.homeItemId } ?: return
            store.removeItem(item.id)
            store.addApp(replaced, item.page, item.x, item.y)
            store.replaceInDock(slot, app.key)
        }
    }

    fun dropOnGrid(app: AppInfo, pos: Offset) {
        val page = pagerState.currentPage
        val cell = cellAt(pos)
        // תיקייה מהמגירה: נוחתת בתא שמתחת לאצבע, או במקום הפנוי הראשון
        if (drag.folderApps.isNotEmpty()) {
            val placed = cell != null &&
                store.addFolder(drag.folderApps, drag.folderName, page, cell.first, cell.second)
            if (!placed) store.addFolderAnywhere(drag.folderApps, drag.folderName, page)
            return
        }
        if (cell == null) {
            // לרוחב: אין תא קבוע, ולכן אפליקציה חדשה נוספת למקום הפנוי הראשון,
            // וסידור מחדש של מה שכבר על המסך נעשה במצב לאורך
            when (drag.source) {
                DragSource.DOCK -> {
                    store.addAppAnywhere(app.key, page)
                    store.removeFromDock(drag.dockIndex)
                }
                DragSource.DRAWER -> store.addAppAnywhere(app.key, page)
                DragSource.HOME -> toast("סידור האייקונים נעשה כשהמסך לאורך")
            }
            return
        }
        val (cx, cy) = cell
        // נפילה על אייקון קיים יוצרת תיקייה, או מוסיפה לתיקייה שכבר שם
        val under = store.itemAt(page, cx, cy, ignoreId = drag.homeItemId)
        if (!draggingHomeFolder() && under != null && !under.isWidget &&
            store.mergeIntoFolder(under.id, app.key)
        ) {
            when (drag.source) {
                DragSource.HOME -> store.removeItem(drag.homeItemId)
                DragSource.DOCK -> store.removeFromDock(drag.dockIndex)
                DragSource.DRAWER -> Unit
            }
            return
        }
        when (drag.source) {
            DragSource.HOME -> store.moveItem(drag.homeItemId, page, cx, cy)
            DragSource.DOCK -> {
                if (store.addApp(app.key, page, cx, cy)) store.removeFromDock(drag.dockIndex)
            }
            DragSource.DRAWER -> {
                if (!store.addApp(app.key, page, cx, cy)) store.addAppAnywhere(app.key, page)
            }
        }
    }

    val endDrag: () -> Unit = {
        val app = drag.app
        if (app != null) {
            if (drag.active) {
                val pos = drag.position
                when {
                    drag.source != DragSource.DRAWER && targets.remove.contains(pos) -> {
                        if (drag.source == DragSource.HOME) store.removeItem(drag.homeItemId)
                        else store.removeFromDock(drag.dockIndex)
                    }
                    targets.dock.contains(pos) -> {
                        if (drag.folderApps.isEmpty() && !draggingHomeFolder()) dropOnDock(app, pos)
                    }
                    overGrid(pos) -> dropOnGrid(app, pos)
                }
                store.trimEmptyPages(pagerState.currentPage)
            }
            drag.reset()
        }
    }

    // מעבר דף כשמחזיקים אייקון בקצה המסך
    LaunchedEffect(drag.active) {
        if (!drag.active) return@LaunchedEffect
        val edge = with(density) { 28.dp.toPx() }
        var dwell = 0
        var lastDir = 0
        while (drag.active) {
            val r = targets.pager
            val pos = drag.position
            val dir = when {
                pos.y !in r.top..r.bottom -> 0
                pos.x > r.right - edge -> 1
                pos.x < r.left + edge -> -1
                else -> 0
            }
            dwell = if (dir != 0 && dir == lastDir) dwell + 1 else 0
            lastDir = dir
            if (dwell >= 5) {
                dwell = 0
                val current = pagerState.currentPage
                val target = current + dir
                if (dir > 0 && target >= store.pageCount && store.home.any { it.page == current }) {
                    store.addPage()
                }
                if (target in 0 until store.pageCount) pagerState.animateScrollToPage(target)
            }
            delay(120)
        }
    }

    // ---------- מגירה ----------

    // קפיץ במקום עקומה אחידה: המגירה נכנסת עם מעט תנופה ונעצרת רך
    val drawerProgress by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 600f),
        label = "drawer"
    )
    // המגירה נשארת "חיה" בזמן גרירה ממנה, כדי שהאצבע לא תאבד את האייקון
    val drawerMounted = drawerOpen || drawerProgress > 0.01f ||
        (drag.app != null && drag.source == DragSource.DRAWER)

    // מעל משטח בהיר (מגירה, הגדרות, מסתור, קוד) צריך אייקונים כהים בשורת הסטטוס
    val lightSurface = drawerOpen || settingsOpen || hiddenOpen || pinMode != null
    LaunchedEffect(lightSurface) { host.setLightSystemBars(lightSurface) }

    // טשטוש הטפט כשהמגירה או ההגדרות פתוחות
    val blurWanted = store.on(Feature.BLUR) && (drawerOpen || settingsOpen)
    LaunchedEffect(blurWanted) { host.setBackgroundBlur(if (blurWanted) 90 else 0) }

    // הצעות חכמות למגירה
    var suggestions by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(drawerOpen, visibleApps, store.on(Feature.SUGGESTIONS), store.drawerColumns) {
        suggestions = if (drawerOpen && store.on(Feature.SUGGESTIONS)) {
            val byKey = visibleApps.associateBy { it.key }
            withContext(Dispatchers.IO) {
                Suggestions.suggest(context, byKey.keys, store.drawerColumns)
            }.mapNotNull { byKey[it] }
        } else {
            emptyList()
        }
    }

    val badges = IconBadges(
        dots = store.on(Feature.NOTIFICATION_DOTS) && notificationAccess,
        newApps = if (store.on(Feature.NEW_BADGES)) store.newApps.toSet() else emptySet()
    )

    CompositionLocalProvider(LocalNaturalDirection provides naturalDirection, LocalIconBadges provides badges) {
        Box(Modifier.fillMaxSize()) {

            // ================= מסך הבית =================
            Column(
                Modifier
                    .fillMaxSize()
                    // מחוות של כמה אצבעות (לפני שאר המחוות)
                    .multiFingerGestures(multiFingerEnabled) { performGesture(it) }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                when {
                                    total < -120f -> performGesture(LauncherGesture.SWIPE_UP)
                                    total > 120f -> performGesture(LauncherGesture.SWIPE_DOWN)
                                }
                            },
                            onVerticalDrag = { change, delta ->
                                change.consume()
                                total += delta
                            }
                        )
                    }
                    .graphicsLayer {
                        val p = drawerProgress
                        alpha = 1f - p
                        scaleX = 1f - 0.04f * p
                        scaleY = 1f - 0.04f * p
                    }
                    // הצללה עדינה בראש המסך, מאחורי התאריך, מזג האוויר והתאריך העברי:
                    // הטקסט הלבן נשאר קריא גם על אזור בהיר בטפט, בלי פס כהה מורגש
                    .drawBehind {
                        drawRect(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.42f),
                                0.55f to Color.Black.copy(alpha = 0.16f),
                                1f to Color.Transparent,
                                endY = 340.dp.toPx()
                            ),
                            size = androidx.compose.ui.geometry.Size(size.width, 340.dp.toPx())
                        )
                    }
                    .systemBarsPadding()
            ) {
                val weather = rememberWeather(store.on(Feature.GLANCE_WEATHER) && !safeActive, refreshKey)
                if (isLandscape && !safeActive) {
                    // לרוחב: המידע המהיר מימין, וכרטיס המוזיקה מוקטן לצידו, כדי לחסוך גובה
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = 24.dp)) {
                        Column(Modifier.weight(1f)) {
                            AtAGlance(
                                onClick = { AppsRepository.openCalendar(context) },
                                modifier = Modifier.padding(horizontal = 24.dp),
                                weather = weather,
                                onWeatherClick = { GlanceData.openWeather(context) }
                            )
                            GlanceExtras(
                                store, refreshKey,
                                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                                showMedia = false
                            )
                        }
                        GlanceMedia(store, Modifier.width(340.dp).padding(top = 22.dp), forceSmall = true)
                    }
                } else {
                    AtAGlance(
                        onClick = { AppsRepository.openCalendar(context) },
                        modifier = Modifier.padding(horizontal = 24.dp),
                        weather = weather,
                        onWeatherClick = { GlanceData.openWeather(context) }
                    )
                    if (!safeActive) {
                        GlanceExtras(store, refreshKey, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
                    }
                }
                if (safeActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                            .clickable(enabled = timedLock) { cancelTimedLock() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (timedLock) "נעול · עוד ${TimedLockText.remaining(store.lockUntil - now)}" else "מצב בטוח",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // הגריד תמיד משמאל לימין, כדי שהמיקומים יהיו פשוטים
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 4,
                            userScrollEnabled = drag.app == null,
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { targets.pager = it.boundsInRoot() }
                        ) { page ->
                            BoxWithConstraints(
                                Modifier
                                    .fillMaxSize()
                                    .pageTransition(store.pageTransition, pagerState, page)
                                    .padding(horizontal = GRID_PAD_H, vertical = GRID_PAD_V)
                                    .pointerInput(page) {
                                        detectTapGestures(
                                            onDoubleTap = if (store.gestureActions[LauncherGesture.DOUBLE_TAP] != GestureAction.NONE) {
                                                { performGesture(LauncherGesture.DOUBLE_TAP) }
                                            } else {
                                                null
                                            },
                                            onLongPress = { off ->
                                            val cx = (off.x / (size.width.toFloat() / store.gridColumns)).toInt()
                                                .coerceIn(0, store.gridColumns - 1)
                                            val cy = (off.y / (size.height.toFloat() / store.gridRows)).toInt()
                                                .coerceIn(0, store.gridRows - 1)
                                            // לרוחב אין תא קבוע, ולכן התפריט נפתח בכל מקום ריק
                                            if (isLandscape || store.isAreaFree(page, cx, cy, 1, 1)) {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                // בנעילה ללא אפשרות ביטול אין תפריט, רק הודעה עד מתי
                                                if (store.lockStrict && store.lockUntil > System.currentTimeMillis()) {
                                                    toast("הטלפון נעול עד השעה ${TimedLockText.clock(store.lockUntil)}")
                                                } else {
                                                    homeMenu = page to off
                                                }
                                            }
                                        })
                                    }
                            ) {
                                val pageItems = store.home.filter { it.page == page }

                                // לרוחב הרשת נבנית מחדש לפי המקום שיש בפועל: הרבה עמודות
                                // ומעט שורות, במקום לדחוס את רשת האורך למסך נמוך
                                val cols: Int
                                val rows: Int
                                if (isLandscape) {
                                    rows = (maxHeight / minCellH).toInt().coerceIn(1, store.gridRows)
                                    // די עמודות כדי שכל מה שבדף ייכנס, ולפחות כמספר העמודות שנבחר
                                    val needed = pageItems.sumOf { it.w * it.h }
                                    cols = maxOf(store.gridColumns, (needed + rows - 1) / rows)
                                } else {
                                    cols = store.gridColumns
                                    rows = store.gridRows
                                }
                                val cellW = maxWidth / cols
                                val cellH = maxHeight / rows
                                // המקום שהאייקון צריך מעבר לסמל עצמו, לפי המידות האמיתיות של AppIcon:
                                // ריפוד של 6dp למעלה ולמטה, רווח של 6dp, ושורת טקסט של 12sp.
                                // גובה השורה מחושב ב-sp, כדי לכבד גם הגדלת גופן בהגדרות המערכת
                                val iconPadding = 12.dp
                                val textLine = with(density) { 18.sp.toDp() }
                                val labelRoom = iconPadding + 6.dp + textLine
                                val wantLabels = store.homeLabels
                                val cellIcon = minOf(
                                    iconSize,
                                    // עם שם: האייקון מתכווץ קודם, עד 36dp, כדי שהשם ייכנס במלואו
                                    (cellH - if (wantLabels) labelRoom else iconPadding).coerceAtLeast(36.dp),
                                    (cellW - 8.dp).coerceAtLeast(22.dp)
                                )
                                // רק כשגם האייקון המוקטן לא משאיר מקום, השם מוסתר
                                val cellLabels = cellH >= cellIcon + labelRoom

                                // לרוחב: סידור מחדש של הפריטים לפי הסדר שבו הם מופיעים לאורך,
                                // לתוך הרשת החדשה. השמירה לא משתנה, ולכן חזרה לאורך מחזירה הכול
                                val slots: Map<Long, Pair<Int, Int>> = if (!isLandscape) emptyMap() else {
                                    val taken = Array(rows) { BooleanArray(cols) }
                                    val placed = HashMap<Long, Pair<Int, Int>>()
                                    pageItems.sortedWith(compareBy({ it.y }, { it.x })).forEach { entry ->
                                        val w = entry.w.coerceIn(1, cols)
                                        val h = entry.h.coerceIn(1, rows)
                                        search@ for (y in 0..rows - h) {
                                            for (x in 0..cols - w) {
                                                var free = true
                                                for (dy in 0 until h) for (dx in 0 until w) {
                                                    if (taken[y + dy][x + dx]) free = false
                                                }
                                                if (free) {
                                                    for (dy in 0 until h) for (dx in 0 until w) {
                                                        taken[y + dy][x + dx] = true
                                                    }
                                                    placed[entry.id] = x to y
                                                    break@search
                                                }
                                            }
                                        }
                                    }
                                    placed
                                }

                                // סימון התא שעליו ינחת האייקון. לרוחב אין תא קבוע
                                if (drag.active && page == pagerState.currentPage) {
                                    val cell = cellAt(drag.position)
                                    if (cell != null) {
                                        Box(
                                            Modifier
                                                .absoluteOffset(cellW * cell.first, cellH * cell.second)
                                                .size(cellW, cellH)
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(Color.White.copy(alpha = 0.18f))
                                        )
                                    }
                                }

                                pageItems.forEach { item ->
                                    key(item.id) {
                                        val slot = slots[item.id]
                                        val ix = slot?.first ?: item.x
                                        val iy = slot?.second ?: item.y
                                        val iw = item.w.coerceAtMost(cols)
                                        val ih = item.h.coerceAtMost(rows)
                                        val itemModifier = Modifier
                                            .absoluteOffset(cellW * ix, cellH * iy)
                                            .size(cellW * iw, cellH * ih)
                                        if (item.isWidget) {
                                            Box(itemModifier) {
                                                WidgetCell(
                                                    host = host,
                                                    item = item,
                                                    width = cellW * iw,
                                                    height = cellH * ih,
                                                    onLongPress = {
                                                        if (!store.lockLayout && !isLandscape) {
                                                            resizingWidget = item.id
                                                        } else {
                                                            widgetToRemove = item
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                if (resizingWidget == item.id) {
                                                    WidgetResizeFrame(
                                                        cellWidthPx = with(density) { cellW.toPx() },
                                                        cellHeightPx = with(density) { cellH.toPx() },
                                                        onResize = { dw, dh ->
                                                            store.resizeItem(item.id, item.w + dw, item.h + dh)
                                                        },
                                                        onRemove = {
                                                            resizingWidget = null
                                                            widgetToRemove = item
                                                        },
                                                        onDone = { resizingWidget = null }
                                                    )
                                                }
                                            }
                                        } else if (item.isFolder) {
                                            val inside = item.folderApps.mapNotNull { appsByKey[it] }
                                            val menuKey = "folder:${item.id}"
                                            Box(itemModifier, contentAlignment = Alignment.Center) {
                                                HomeFolderTile(
                                                    itemKey = item.id,
                                                    apps = inside,
                                                    name = item.folderName,
                                                    iconSize = cellIcon,
                                                    shape = iconShape.toComposeShape(),
                                                    showLabel = cellLabels && store.homeLabels,
                                                    large = item.w >= 2 && item.h >= 2,
                                                    hidden = drag.active && drag.source == DragSource.HOME &&
                                                        drag.homeItemId == item.id,
                                                    menuOpen = openMenu == menuKey,
                                                    menuActions = { folderActions(item) },
                                                    onDismissMenu = { openMenu = null },
                                                    onOpen = { openFolder = item.id },
                                                    onLaunch = { launchApp(it) },
                                                    onLongPress = inside.firstOrNull()?.let {
                                                        startDrag(it, DragSource.HOME, item.id, -1, menuKey)
                                                    } ?: { _: Offset -> },
                                                    onMove = moveDrag,
                                                    onRelease = endDrag
                                                )
                                            }
                                        } else {
                                            val app = appsByKey[item.appKey]
                                            if (app != null) {
                                                val menuKey = "home:${item.id}"
                                                Box(itemModifier, contentAlignment = Alignment.Center) {
                                                    AppIcon(
                                                        app = app,
                                                        iconSize = cellIcon,
                                                        showLabel = cellLabels && store.homeLabels,
                                                        labelColor = Color.White,
                                                        labelShadow = true,
                                                        hidden = drag.active && drag.source == DragSource.HOME &&
                                                            drag.homeItemId == item.id,
                                                        menuOpen = openMenu == menuKey,
                                                        menuActions = { actionsFor(app, DragSource.HOME, item.id, -1) },
                                                        onDismissMenu = { openMenu = null },
                                                        onClick = { launchApp(app) },
                                                        onLongPress = startDrag(app, DragSource.HOME, item.id, -1, menuKey),
                                                        onMove = moveDrag,
                                                        onRelease = endDrag,
                                                        onSwipeUp = swipeActionFor(app)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // תפריט לחיצה ארוכה על מקום ריק
                                val menu = homeMenu
                                if (menu != null && menu.first == page) {
                                    HomeMenuPopup(
                                        offset = menu.second,
                                        maxWidthPx = constraints.maxWidth.toFloat(),
                                        onDismiss = { homeMenu = null },
                                        onWidgets = {
                                            homeMenu = null
                                            widgetPickerOpen = true
                                        },
                                        onWallpaper = {
                                            homeMenu = null
                                            AppsRepository.openWallpaperPicker(context)
                                        },
                                        onSettings = {
                                            homeMenu = null
                                            requestSettings()
                                        },
                                        safeMode = safeActive,
                                        timedLock = timedLock,
                                        strictLock = store.lockStrict,
                                        onTimedLock = {
                                            homeMenu = null
                                            timedLockDialog = true
                                        },
                                        onOrganize = {
                                            homeMenu = null
                                            val packages = store.home.mapNotNull { it.appKey?.substringBefore('/') }.distinct()
                                            val groups = categories ?: CategoryRepository.cached(context, packages)
                                            val plan = store.folderPlan { key ->
                                                groups[key.substringBefore('/')]?.let { it.id to it.label }
                                            }
                                            if (plan.isEmpty()) toast("אין אייקונים מאותה קטגוריה לקבץ")
                                            else organizePlan = plan
                                        },
                                        onCancelLock = {
                                            homeMenu = null
                                            cancelTimedLock()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // נקודות דפים + Dock
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(Modifier.fillMaxWidth()) {
                        val dotsAlpha by animateFloatAsState(
                            targetValue = if (store.pageCount > 1 &&
                                (pagerState.isScrollInProgress || drag.app != null)
                            ) 1f else 0f,
                            animationSpec = tween(if (pagerState.isScrollInProgress) 120 else 600),
                            label = "dots"
                        )
                        Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
                            Row(
                                Modifier.graphicsLayer { alpha = dotsAlpha },
                                horizontalArrangement = Arrangement.Center
                            ) {
                                repeat(store.pageCount) { i ->
                                    Box(
                                        Modifier
                                            .padding(horizontal = 3.dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Color.White.copy(alpha = if (i == pagerState.currentPage) 1f else 0.4f)
                                            )
                                    )
                                }
                            }
                        }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(dockHeight)
                                .padding(horizontal = GRID_PAD_H)
                                .onGloballyPositioned { targets.dock = it.boundsInRoot() },
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Dock חכם: כשאוזניות או רכב מחוברים מוצגות האפליקציות שבחרת לזה
                            val headphones = rememberHeadphonesConnected(
                                store.on(Feature.CONTEXT_DOCK) && store.contextDock.isNotEmpty() && !safeActive
                            )
                            if (headphones) {
                                store.contextDock.forEach { appKey ->
                                    val app = appsByKey[appKey]
                                    if (app != null) {
                                        key("ctx:$appKey") {
                                            val menuKey = "ctxdock:$appKey"
                                            AppIcon(
                                                app = app,
                                                iconSize = dockIcon,
                                                showLabel = false,
                                                labelColor = Color.White,
                                                labelShadow = true,
                                                hidden = false,
                                                menuOpen = openMenu == menuKey,
                                                menuActions = { actionsFor(app, DragSource.DRAWER, -1, -1) },
                                                onDismissMenu = { openMenu = null },
                                                onClick = { launchApp(app) },
                                                onLongPress = startDrag(app, DragSource.DRAWER, -1, -1, menuKey),
                                                onMove = moveDrag,
                                                onRelease = endDrag,
                                                onSwipeUp = swipeActionFor(app)
                                            )
                                        }
                                    }
                                }
                            } else store.dock.forEachIndexed { index, appKey ->
                                val app = appsByKey[appKey]
                                if (app != null) {
                                    key(appKey) {
                                        val menuKey = "dock:$appKey"
                                        AppIcon(
                                            app = app,
                                            iconSize = dockIcon,
                                            showLabel = false,
                                            labelColor = Color.White,
                                            labelShadow = true,
                                            hidden = drag.active && drag.source == DragSource.DOCK &&
                                                drag.dockIndex == index,
                                            menuOpen = openMenu == menuKey,
                                            menuActions = { actionsFor(app, DragSource.DOCK, -1, index) },
                                            onDismissMenu = { openMenu = null },
                                            onClick = { launchApp(app) },
                                            onLongPress = startDrag(app, DragSource.DOCK, -1, index, menuKey),
                                            onMove = moveDrag,
                                            onRelease = endDrag,
                                            onSwipeUp = swipeActionFor(app)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (store.on(Feature.HOME_SEARCH)) {
                    Spacer(Modifier.height(8.dp))
                    SearchPill(
                        onClick = {
                            focusSearch = true
                            drawerOpen = true
                        },
                        modifier = Modifier.padding(horizontal = 20.dp),
                        opacity = store.searchOpacity
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ================= אזור "הסרה" בזמן גרירה =================
            if (drag.active && drag.source != DragSource.DRAWER) {
                RemoveZone(
                    hovering = targets.remove.contains(drag.position),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(72.dp)
                        .onGloballyPositioned { targets.remove = it.boundsInRoot() }
                )
            }

            // ================= מגירת אפליקציות =================
            if (drawerMounted) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = drawerProgress
                            translationY = (1f - drawerProgress) * size.height * 0.18f
                            // התוכן גדל מעט בדרך פנימה, לתחושת עומק
                            val s = 0.97f + 0.03f * drawerProgress
                            scaleX = s
                            scaleY = s
                        }
                ) {
                    // אייקון במגירה. prefix מפריד בין ההצעות לרשת, כדי שלא ייפתחו שני תפריטים
                    val drawerIcon: @Composable (AppInfo, String) -> Unit = { app, prefix ->
                        val menuKey = "$prefix:${app.key}"
                        AppIcon(
                            app = app,
                            iconSize = iconSize,
                            showLabel = store.drawerLabels,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            labelShadow = false,
                            hidden = false,
                            menuOpen = openMenu == menuKey,
                            menuActions = { actionsFor(app, DragSource.DRAWER, -1, -1) },
                            onDismissMenu = { openMenu = null },
                            onClick = {
                                launchApp(app)
                                drawerOpen = false
                            },
                            onLongPress = startDrag(app, DragSource.DRAWER, -1, -1, menuKey),
                            onMove = moveDrag,
                            onRelease = endDrag,
                            onSwipeUp = swipeActionFor(app)
                        )
                    }
                    AppDrawer(
                        apps = visibleApps,
                        autoFocusSearch = focusSearch,
                        categories = categories,
                        folderMode = store.drawerFolders,
                        columns = store.drawerColumns,
                        backgroundOpacity = store.drawerOpacity,
                        iconSize = iconSize,
                        iconShape = iconShape,
                        categoryProgress = categoryProgress,
                        onRenameFolder = { renameTarget = it },
                        onDeleteFolder = { deleteTarget = it },
                        suggestions = suggestions,
                        searchExtras = { query ->
                            UniversalResults(query, store, safeActive, onAction = { drawerOpen = false })
                        },
                        onClose = { drawerOpen = false },
                        onSubmit = { app ->
                            launchApp(app)
                            drawerOpen = false
                        },
                        suggestionContent = { app -> drawerIcon(app, "suggest") },
                        dragActive = drag.active,
                        onFolderDrag = { group, folderApps, root ->
                            if (!safeActive && !store.lockLayout) startFolderDrag(group, folderApps, root)
                        },
                        onFolderToHome = { group, folderApps ->
                            val page = store.addFolderAnywhere(folderApps.map { it.key }, group.label, pagerState.currentPage)
                            drawerOpen = false
                            scope.launch { pagerState.animateScrollToPage(page) }
                        },
                        onItemMove = moveDrag,
                        onItemRelease = endDrag,
                        appContent = { app -> drawerIcon(app, "drawer") }
                    )
                }
            }

            // ================= הגדרות =================
            AnimatedVisibility(
                visible = settingsOpen,
                enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 8 } +
                    fadeIn(tween(240)),
                exit = slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { it / 8 } +
                    fadeOut(tween(180))
            ) {
                val progress = categoryProgress
                SettingsScreen(
                    store = store,
                    host = host,
                    notificationAccess = notificationAccess,
                    previewApps = visibleApps,
                    categoryStatus = progress?.let { (done, total) -> "בבדיקה מול Google Play · $done/$total" },
                    customCategoryCount = overrideCount,
                    onResetCategories = {
                        CategoryRepository.clearOverrides(context)
                        reloadCategories()
                    },
                    onRefreshCategories = {
                        CategoryRepository.clearCache(context)
                        categoryRefresh++
                    },
                    onWallpaper = { AppsRepository.openWallpaperPicker(context) },
                    onDefaultHome = { AppsRepository.openHomeSettings(context) },
                    hiddenCount = hiddenKeys.size,
                    onShowHidden = {
                        settingsOpen = false
                        requestHidden()
                    },
                    gestureApps = apps,
                    onPin = {
                        pinEmergency = false
                        pinMode = it
                    },
                    isDeviceOwner = isOwner,
                    onEmergencyPin = {
                        pinEmergency = true
                        pinMode = it
                    },
                    onRemoveOwner = {
                        host.removeDeviceOwner()
                        isOwner = false
                        toast("הנעילה המלאה בוטלה")
                    },
                    onOwnerChanged = { isOwner = host.isDeviceOwner() },
                    allApps = apps,
                    onShizukuBlockChange = { enable ->
                        store.updateShizukuBlock(enable)
                        // כיבוי החסימה משחרר מיד את כל מה שהושעה
                        if (!enable) scope.launch { applyShizukuBlock(false) }
                    },
                    onSafeModeChange = { enable ->
                        if (!enable) {
                            store.updateSafeMode(false)
                            toast("מצב בטוח בוטל")
                        } else when {
                            store.safeApps.isEmpty() -> toast("יש לבחור תחילה אילו אפליקציות יהיו זמינות")
                            isOwner && store.biometricLock && !store.hasEmergencyPin -> android.widget.Toast.makeText(
                                context,
                                "נעילה מלאה עם טביעת אצבע מחייבת קוד חירום. ניתן להגדיר אותו כאן, תחת מצב בטוח",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            !store.biometricLock && !store.hasPin -> android.widget.Toast.makeText(
                                context,
                                "כדי שלא ניתן יהיה לכבות את המצב הבטוח ללא אישורך, יש להגדיר תחילה קוד או טביעת אצבע תחת הגדרות ← אפליקציות מוסתרות",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            else -> {
                                store.updateSafeMode(true)
                                settingsOpen = false
                                drawerOpen = false
                                toast("מצב בטוח הופעל")
                            }
                        }
                    },
                    onBiometricChange = { enable ->
                        if (!enable) {
                            host.authenticate("ביטול נעילה בטביעת אצבע", "אימות להמשך") {
                                store.updateBiometricLock(false)
                            }
                        } else {
                            when (host.biometricStatus()) {
                                BiometricManager.BIOMETRIC_SUCCESS ->
                                    host.authenticate("נעילה בטביעת אצבע", "אימות להפעלה") {
                                        store.updateBiometricLock(true)
                                        android.widget.Toast.makeText(
                                            context, "המסתור נעול בטביעת אצבע", android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                                    android.widget.Toast.makeText(
                                        context, "לא נרשמה טביעת אצבע במכשיר. ניתן להוסיף אותה בהגדרות האבטחה",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                                else -> android.widget.Toast.makeText(
                                    context, "המכשיר אינו תומך בטביעת אצבע", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    onClose = { settingsOpen = false }
                )
            }

            // ================= אפליקציות מוסתרות =================
            AnimatedVisibility(
                visible = hiddenOpen,
                enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 6 } +
                    fadeIn(tween(220)),
                exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it / 6 } +
                    fadeOut(tween(160))
            ) {
                HiddenAppsScreen(
                    apps = apps.filter { it.key in hiddenKeys },
                    columns = store.drawerColumns,
                    iconSize = iconSize,
                    onLaunch = { app ->
                        launchApp(app)
                        hiddenOpen = false
                    },
                    onUnhide = { app -> store.unhideApp(app.key) },
                    onClose = { hiddenOpen = false }
                )
            }

            // ================= נעילה לזמן קצוב =================
            if (timedLockDialog) {
                TimedLockDialog(
                    apps = visibleApps,
                    initialApps = store.lockApps.toSet().ifEmpty { store.safeApps.toSet() },
                    hasOwnerLock = store.biometricLock || store.hasPin,
                    isDeviceOwner = isOwner,
                    onStart = { until, strict, keys ->
                        timedLockDialog = false
                        store.startTimedLock(until, strict, keys)
                        drawerOpen = false
                        settingsOpen = false
                        hiddenOpen = false
                        toast("נעול עד השעה ${TimedLockText.clock(until)}")
                    },
                    onDismiss = { timedLockDialog = false }
                )
            }

            // ================= קוד =================
            val mode = pinMode
            if (mode != null) {
                PinFlow(
                    mode = mode,
                    store = store,
                    onSuccess = {
                        pinMode = null
                        when (mode) {
                            PinMode.UNLOCK -> {
                                pinAction?.invoke()
                                pinAction = null
                            }
                            PinMode.SET -> android.widget.Toast.makeText(context, "הקוד נשמר", android.widget.Toast.LENGTH_SHORT).show()
                            PinMode.REMOVE -> android.widget.Toast.makeText(context, "הקוד הוסר", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCancel = { pinMode = null },
                    emergency = pinEmergency
                )
            }

            // ================= בחירת ווידג'ט =================
            if (widgetPickerOpen) {
                WidgetPicker(
                    onPick = { option ->
                        widgetPickerOpen = false
                        host.requestAddWidget(option.info) { widgetId ->
                            val page = store.addWidget(
                                widgetId,
                                option.span.first,
                                option.span.second,
                                pagerState.currentPage
                            )
                            scope.launch { pagerState.animateScrollToPage(page) }
                        }
                    },
                    onClose = { widgetPickerOpen = false }
                )
            }

            // ================= האייקון שנגרר =================
            val dragged = drag.app
            if (dragged != null && drag.active) {
                val half = with(density) { (iconSize * 0.6f).toPx() }
                val previewModifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .size(iconSize * 1.2f)
                    .graphicsLayer {
                        translationX = drag.position.x - half
                        translationY = drag.position.y - half
                        scaleX = 1.08f
                        scaleY = 1.08f
                    }
                val folderPreview = drag.folderApps.ifEmpty {
                    store.home.firstOrNull { it.id == drag.homeItemId && it.isFolder }?.folderApps.orEmpty()
                }
                if (folderPreview.isNotEmpty()) {
                    // תיקייה נגררת: ריבוע עם ארבעה סמלים זעירים
                    Box(
                        previewModifier
                            .clip(iconShape.toComposeShape())
                            .background(Color.White.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            folderPreview.mapNotNull { appsByKey[it] }.take(4).chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    row.forEach { a ->
                                        Image(a.icon, contentDescription = null, modifier = Modifier.size(iconSize * 0.42f))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Image(bitmap = dragged.icon, contentDescription = null, modifier = previewModifier)
                }
            }

            // ================= העברת אפליקציה לתיקייה =================
            val target = moveTarget
            if (target != null) {
                MoveToFolderDialog(
                    app = target,
                    groups = remember(target, overrideCount) {
                        CategoryRepository.customFolders(context) +
                            (if (store.brandFolders) DrawerGroup.BRANDS else emptyList()) +
                            AppCategory.entries.map { DrawerGroup.of(it) }
                    },
                    current = categories?.get(target.packageName),
                    isCustom = CategoryRepository.hasOverride(context, target.packageName),
                    onSelect = { groupId ->
                        CategoryRepository.setOverride(context, target.packageName, groupId)
                        reloadCategories()
                        moveTarget = null
                    },
                    onCreateFolder = { name ->
                        val folder = CategoryRepository.createFolder(context, name)
                        CategoryRepository.setOverride(context, target.packageName, folder.id)
                        reloadCategories()
                        moveTarget = null
                    },
                    onDismiss = { moveTarget = null }
                )
            }

            // ================= בחירת אייקון מהגלריה =================
            val pickingIconFor = iconTarget
            if (pickingIconFor != null) {
                val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) {
                        if (CustomIcons.save(context, pickingIconFor.key, uri)) iconRefresh++
                        else toast("לא ניתן לטעון את התמונה")
                    }
                    iconTarget = null
                }
                LaunchedEffect(pickingIconFor.key) { picker.launch("image/*") }
            }

            // ================= סידור בתיקיות =================
            val plan = organizePlan
            if (plan != null) {
                AlertDialog(
                    onDismissRequest = { organizePlan = null },
                    title = { Text("סידור בתיקיות") },
                    text = {
                        Text(
                            plan.joinToString("\n") { (name, items) -> "$name · ${items.size} אפליקציות" }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            store.applyFolderPlan(plan)
                            store.trimEmptyPages(pagerState.currentPage)
                            organizePlan = null
                        }) { Text("סידור") }
                    },
                    dismissButton = { TextButton(onClick = { organizePlan = null }) { Text("ביטול") } }
                )
            }

            // ================= חלון העריכה של אפליקציה =================
            val editing = editTarget
            // הבחירה נשמרת גם במצב מקומי, כדי שהטבעת תזוז מיד ולא רק אחרי טעינת האייקונים
            var editColor by remember(editing?.key) {
                mutableStateOf(editing?.let { IconColors.all(context)[it.key] })
            }
            if (editing != null) {
                val swipeNow = swipeMap[editing.key]?.let { target -> apps.firstOrNull { it.key == target } }
                AppEditDialog(
                    app = editing,
                    swipeLabel = swipeNow?.label,
                    hasCustomIcon = CustomIcons.has(context, editing.key),
                    canMoveToFolder = store.drawerCategories && categories != null,
                    colorMode = editColor,
                    wallpaper = wallpaperTheme,
                    onColor = { mode ->
                        editColor = mode
                        IconColors.set(context, editing.key, mode)
                        iconRefresh++
                    },
                    onRename = { editTarget = null; renameApp = editing },
                    onPickIcon = { editTarget = null; iconTarget = editing },
                    onResetIcon = {
                        CustomIcons.clear(context, editing.key)
                        iconRefresh++
                        editTarget = null
                    },
                    onSwipe = { editTarget = null; swipeTarget = editing },
                    onClearSwipe = {
                        AppSwipes.set(context, editing.key, null)
                        swipeMap = AppSwipes.all(context)
                        editTarget = null
                    },
                    onMoveToFolder = { editTarget = null; moveTarget = editing },
                    onHide = {
                        editTarget = null
                        store.hideApp(editing.key)
                        store.trimEmptyPages(pagerState.currentPage)
                        val gesture = LauncherGesture.entries
                            .firstOrNull { store.gestureActions[it] == GestureAction.HIDDEN }
                        val hint = gesture?.let { "${it.label} במסך הבית" } ?: "הגדרות ← אפליקציות מוסתרות"
                        android.widget.Toast.makeText(
                            context,
                            "${editing.label} הוסתרה. להצגתה: $hint",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    },
                    onDismiss = { editTarget = null }
                )
            }

            // ================= תיקייה במסך הבית =================
            val openFolderId = openFolder
            val folderItem = openFolderId?.let { id -> store.home.firstOrNull { it.id == id } }
            if (folderItem != null && folderItem.isFolder) {
                HomeFolderDialog(
                    apps = folderItem.folderApps.mapNotNull { appsByKey[it] },
                    name = folderItem.folderName,
                    onOpen = { app ->
                        openFolder = null
                        launchApp(app)
                    },
                    onRemove = { app ->
                        store.removeFromFolder(folderItem.id, app.key)
                        store.addAppAnywhere(app.key, folderItem.page)
                        toast("${app.label} הוצאה מהתיקייה")
                        if (folderItem.folderApps.size <= 2) openFolder = null
                    },
                    onRename = {
                        openFolder = null
                        folderRename = folderItem
                    },
                    onDismiss = { openFolder = null }
                )
            } else if (openFolderId != null) {
                LaunchedEffect(openFolderId) { openFolder = null }
            }

            val renamingFolder = folderRename
            if (renamingFolder != null) {
                FolderNameDialog(
                    title = "שם התיקייה",
                    initial = renamingFolder.folderName,
                    confirmLabel = "שמירה",
                    onConfirm = { name ->
                        store.renameHomeFolder(renamingFolder.id, name)
                        folderRename = null
                    },
                    onDismiss = { folderRename = null }
                )
            }

            // ================= מחווה על אייקון =================
            val swipingFor = swipeTarget
            if (swipingFor != null) {
                AppPickerDialog(
                    apps = apps.filter { it.key != swipingFor.key },
                    onPick = { picked ->
                        AppSwipes.set(context, swipingFor.key, picked.key)
                        swipeMap = AppSwipes.all(context)
                        swipeTarget = null
                        toast("החלקה מעלה על ${swipingFor.label} תפתח את ${picked.label}")
                    },
                    onDismiss = { swipeTarget = null }
                )
            }

            // ================= שינוי שם של אפליקציה =================
            val renamingApp = renameApp
            if (renamingApp != null) {
                val applyName = { name: String? ->
                    AppLabels.set(context, renamingApp.key, name)
                    val newLabel = name?.trim()?.takeIf { it.isNotEmpty() } ?: renamingApp.originalLabel
                    // מעדכנים את השם בלי לטעון מחדש את כל האייקונים
                    apps = apps
                        .map { if (it.key == renamingApp.key) it.copy(label = newLabel) else it }
                        .sortedWith(compareBy<AppInfo, String>(AlphabeticalOrder.collator) { it.label })
                    renameApp = null
                }
                RenameAppDialog(
                    app = renamingApp,
                    onConfirm = { applyName(it) },
                    onReset = { applyName(null) },
                    onDismiss = { renameApp = null }
                )
            }

            // ================= שינוי שם / מחיקה של תיקייה =================
            val renaming = renameTarget
            if (renaming != null) {
                FolderNameDialog(
                    title = "שינוי שם התיקייה",
                    initial = renaming.label,
                    confirmLabel = "שמירה",
                    onConfirm = { name ->
                        CategoryRepository.renameFolder(context, renaming.id, name)
                        reloadCategories()
                        renameTarget = null
                    },
                    onDismiss = { renameTarget = null }
                )
            }
            val deleting = deleteTarget
            if (deleting != null) {
                AlertDialog(
                    onDismissRequest = { deleteTarget = null },
                    title = { Text("למחוק את \"${deleting.label}\"?") },
                    text = { Text("האפליקציות שבתיקייה לא יימחקו, אלא יחזרו לקטגוריה שלהן ב-Google Play.") },
                    confirmButton = {
                        TextButton(onClick = {
                            CategoryRepository.deleteFolder(context, deleting.id)
                            reloadCategories()
                            deleteTarget = null
                        }) { Text("מחיקה") }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteTarget = null }) { Text("ביטול") }
                    }
                )
            }

            // ================= הסרת ווידג'ט =================
            val toRemove = widgetToRemove
            if (toRemove != null) {
                AlertDialog(
                    onDismissRequest = { widgetToRemove = null },
                    title = { Text("להסיר את הווידג'ט?") },
                    confirmButton = {
                        TextButton(onClick = {
                            store.removeItem(toRemove.id)
                            host.deleteWidget(toRemove.widgetId)
                            store.trimEmptyPages(pagerState.currentPage)
                            widgetToRemove = null
                        }) { Text("הסרה") }
                    },
                    dismissButton = {
                        TextButton(onClick = { widgetToRemove = null }) { Text("ביטול") }
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeMenuPopup(
    offset: Offset,
    maxWidthPx: Float,
    onDismiss: () -> Unit,
    onWidgets: () -> Unit,
    onWallpaper: () -> Unit,
    onSettings: () -> Unit,
    safeMode: Boolean = false,
    timedLock: Boolean = false,
    strictLock: Boolean = false,
    onTimedLock: () -> Unit = {},
    onCancelLock: () -> Unit = {},
    onOrganize: () -> Unit = {}
) {
    val density = LocalDensity.current
    val menuWidthPx = with(density) { 248.dp.toPx() }
    val x = offset.x.coerceAtMost(maxWidthPx - menuWidthPx).coerceAtLeast(0f)

    Popup(
        offset = IntOffset(x.toInt(), offset.y.toInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LocalNaturalDirection.current) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                modifier = Modifier.width(248.dp)
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    when {
                        timedLock -> {
                            if (!strictLock) MenuRow("ביטול הנעילה", Icons.Outlined.Lock, onCancelLock)
                        }
                        !safeMode -> {
                            MenuRow("ווידג'טים", Icons.Outlined.Add, onWidgets)
                            MenuRow("טפטים", Icons.Outlined.Create, onWallpaper)
                            MenuRow("נעילה לזמן קצוב", Icons.Outlined.Lock, onTimedLock)
                            MenuRow("סידור בתיקיות", Icons.Outlined.Edit, onOrganize)
                        }
                    }
                    if (!(timedLock && strictLock)) MenuRow("הגדרות", Icons.Outlined.Settings, onSettings)
                }
            }
        }
    }
}

/** בחירת תיקייה לאפליקציה, כולל יצירת תיקייה חדשה. null = חזרה לקטגוריה מ-Google Play */
@Composable
private fun MoveToFolderDialog(
    app: AppInfo,
    groups: List<DrawerGroup>,
    current: DrawerGroup?,
    isCustom: Boolean,
    onSelect: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        FolderNameDialog(
            title = "תיקייה חדשה",
            initial = "",
            confirmLabel = "יצירה",
            onConfirm = onCreateFolder,
            onDismiss = { creating = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(48.dp)) },
        title = { Text("לאיזו תיקייה להעביר את ${app.label}?") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // יצירת תיקייה חדשה
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { creating = true }
                        .padding(vertical = 10.dp, horizontal = 12.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("תיקייה חדשה", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                groups.forEachIndexed { index, group ->
                    if (index > 0 && groups[index - 1].isCustom && !group.isCustom) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(group.id) }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = group.id == current?.id, onClick = { onSelect(group.id) })
                        Text(group.label, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("ביטול") }
        },
        dismissButton = if (isCustom) {
            @Composable { TextButton(onClick = { onSelect(null) }) { Text("לפי Google Play") } }
        } else {
            null
        }
    )
}

/** חלון להזנת שם תיקייה (יצירה או שינוי שם) */
@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                placeholder = { Text("לדוגמה: עבודה") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול") }
        }
    )
}

/** שינוי השם שמוצג מתחת לאייקון. לא משנה את האפליקציה עצמה */
@Composable
private fun RenameAppDialog(
    app: AppInfo,
    onConfirm: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(app.label) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(48.dp)) },
        title = { Text("שינוי שם") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = if (name.isNotEmpty()) {
                        @Composable {
                            IconButton(onClick = { name = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "ניקוי")
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                )
                if (app.isRenamed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "השם המקורי: ${app.originalLabel}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("שמירה") }
        },
        dismissButton = {
            Row {
                if (app.isRenamed) {
                    TextButton(onClick = onReset) { Text("לשם המקורי") }
                }
                TextButton(onClick = onDismiss) { Text("ביטול") }
            }
        }
    )
}
