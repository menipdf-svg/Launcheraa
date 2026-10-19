package com.meni.pixellauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** הקטגוריות במסך ההגדרות, לפי הסדר שבו הן מוצגות */
private enum class SettingsSection(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color
) {
    APPEARANCE("מראה", "גודל האייקונים, צורתם וחבילת אייקונים", Icons.Outlined.Star, Color(0xFF9334E6)),
    HOME("מסך הבית", "פריסת הרשת, מעברים, נקודות התראה וטפט", Icons.Outlined.Home, Color(0xFF1A73E8)),
    GLANCE("במבט חטוף", "תאריך עברי, שעת שקיעה, יומן והנגן", Icons.Outlined.DateRange, Color(0xFF12B5CB)),
    DRAWER("מגירת אפליקציות", "עמודות, קטגוריות, תיקיות והצעות", Icons.Outlined.List, Color(0xFF188038)),
    SEARCH("חיפוש במגירה", "אנשי קשר, מחשבון, הגדרות ואינטרנט", Icons.Outlined.Search, Color(0xFFE8710A)),
    GESTURES("מחוות", "הפעולה שתתבצע בכל מחווה", Icons.Outlined.Build, Color(0xFF8E6E53)),
    DOCK("Dock חכם", "החלפת ה-Dock בעת חיבור אוזניות או רכב", Icons.Outlined.PlayArrow, Color(0xFFD01884)),
    PRIVACY("פרטיות ונעילה", "אפליקציות מוסתרות, קוד ומצב בטוח", Icons.Outlined.Lock, Color(0xFFD93025)),
    BACKUP("גיבוי ושחזור", "שמירה לקובץ, שחזור ואיפוס", Icons.Outlined.Refresh, Color(0xFF3F51B5)),
    GENERAL("כללי", "אפליקציית ברירת המחדל", Icons.Outlined.Settings, Color(0xFF5F6368))
}

@Composable
fun SettingsScreen(
    store: LauncherStore,
    host: LauncherHost,
    notificationAccess: Boolean,
    previewApps: List<AppInfo>,
    categoryStatus: String?,
    customCategoryCount: Int,
    onResetCategories: () -> Unit,
    onRefreshCategories: () -> Unit,
    onWallpaper: () -> Unit,
    onDefaultHome: () -> Unit,
    hiddenCount: Int,
    onShowHidden: () -> Unit,
    gestureApps: List<AppInfo>,
    onPin: (PinMode) -> Unit,
    onBiometricChange: (Boolean) -> Unit,
    allApps: List<AppInfo>,
    onSafeModeChange: (Boolean) -> Unit,
    isDeviceOwner: Boolean,
    onEmergencyPin: (PinMode) -> Unit,
    onRemoveOwner: () -> Unit,
    onOwnerChanged: () -> Unit,
    onShizukuBlockChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    // null = המסך הראשי עם רשימת הקטגוריות
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    BackHandler(enabled = section != null) { section = null }

    var confirmReset by remember { mutableStateOf(false) }
    var editingGesture by remember { mutableStateOf<LauncherGesture?>(null) }
    var pickingAppFor by remember { mutableStateOf<LauncherGesture?>(null) }
    var pickingSafeApps by remember { mutableStateOf(false) }
    var pickingContextDock by remember { mutableStateOf(false) }
    var pickingIconPack by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    // אישור הנגישות ניתן במסך של המערכת, ולכן נבדק שוב בחזרה לכאן
    val safeOrLocked = store.safeMode || store.lockUntil > System.currentTimeMillis()
    var shadeGuardOn by remember { mutableStateOf(ShadeGuardService.enabled(context)) }
    var shadeGuardConnected by remember { mutableStateOf(ShadeGuardService.connected) }
    LaunchedEffect(Unit) {
        while (true) {
            shadeGuardOn = ShadeGuardService.enabled(context)
            shadeGuardConnected = ShadeGuardService.connected
            kotlinx.coroutines.delay(1_500)
        }
    }

    // הרשאות: היכולת נדלקת רק אחרי שההרשאה ניתנה
    var pendingFeature by remember { mutableStateOf<Feature?>(null) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingFeature?.let { if (granted) store.setFeature(it, true) else toast("ההרשאה לא אושרה") }
        pendingFeature = null
    }
    fun toggleWithPermission(feature: Feature, permission: String, enable: Boolean) {
        when {
            !enable -> store.setFeature(feature, false)
            GlanceData.hasPermission(context, permission) -> store.setFeature(feature, true)
            else -> {
                pendingFeature = feature
                permissionLauncher.launch(permission)
            }
        }
    }
    fun openNotificationAccess() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    fun toggleWithNotificationAccess(feature: Feature, enable: Boolean) {
        store.setFeature(feature, enable)
        if (enable && !notificationAccess) {
            toast("יש לאשר ל-PixelLauncher גישה להתראות")
            openNotificationAccess()
        }
    }
    val needsAccess = "נדרשת גישה להתראות. לחיצה לאישור"

    if (pickingContextDock) {
        SafeAppsDialog(
            apps = allApps,
            selected = store.contextDock.toSet(),
            title = "Dock לאוזניות ולרכב",
            max = DOCK_MAX,
            onSave = {
                store.setContextDock(it.toList())
                pickingContextDock = false
            },
            onDismiss = { pickingContextDock = false }
        )
    }
    if (pickingIconPack) {
        IconPackDialog(
            current = store.iconPack,
            onPick = {
                store.updateIconPack(it)
                pickingIconPack = false
            },
            onDismiss = { pickingIconPack = false }
        )
    }
    var showKioskSetup by remember { mutableStateOf(false) }
    var confirmRemoveOwner by remember { mutableStateOf(false) }

    if (showKioskSetup) KioskSetupDialog(
        onGranted = {
            onOwnerChanged()
            showKioskSetup = false
            toast("הנעילה המלאה הופעלה")
        },
        onDismiss = { showKioskSetup = false }
    )
    if (confirmRemoveOwner) {
        AlertDialog(
            onDismissRequest = { confirmRemoveOwner = false },
            title = { Text("לבטל את הנעילה המלאה?") },
            text = { Text("הלאנצ'ר יחדל לשמש כמנהל המכשיר. מצב בטוח ימשיך להסתיר אפליקציות, אך לא יחסום יציאה מהן. להפעלה מחדש יש לחזור על ההגדרה מהמחשב.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveOwner = false
                    onRemoveOwner()
                }) { Text("ביטול הנעילה") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveOwner = false }) { Text("השארה") } }
        )
    }

    if (pickingSafeApps) {
        SafeAppsDialog(
            apps = allApps,
            selected = store.safeApps.toSet(),
            onSave = {
                store.setSafeApps(it)
                pickingSafeApps = false
            },
            onDismiss = { pickingSafeApps = false }
        )
    }

    editingGesture?.let { gesture ->
        GestureActionDialog(
            gesture = gesture,
            current = store.gestureActions[gesture] ?: GestureAction.NONE,
            onSelect = { action ->
                editingGesture = null
                if (action == GestureAction.APP) pickingAppFor = gesture else store.setGesture(gesture, action)
            },
            onDismiss = { editingGesture = null }
        )
    }
    pickingAppFor?.let { gesture ->
        AppPickerDialog(
            apps = gestureApps,
            onPick = { app ->
                store.setGesture(gesture, GestureAction.APP, app.key)
                pickingAppFor = null
            },
            onDismiss = { pickingAppFor = null }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("להחזיר לברירת מחדל?") },
            text = { Text("התיקיות שנוצרו יימחקו, וכל האפליקציות יחזרו לקטגוריה שלהן ב-Google Play. האפליקציות עצמן לא יימחקו.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetCategories()
                }) { Text("החזרה") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("ביטול") }
            }
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
            ) {
                if (section != null) {
                    IconButton(onClick = { section = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "חזרה")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    section?.title ?: "הגדרות",
                    fontSize = if (section == null) 30.sp else 24.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "סגירה")
                }
            }

            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    // כניסה לקטגוריה מחליקה קדימה, חזרה מחליקה אחורה
                    val shift = if (targetState != null) 1 else -1
                    (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { w -> shift * w / 4 } +
                        fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { w -> -shift * w / 4 } +
                            fadeOut(tween(150)))
                },
                label = "settingsSection"
            ) { sec ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (sec == null) SectionList { section = it }

                // ---------- מצב בטוח ----------
                if (sec == SettingsSection.PRIVACY) SettingsGroup("מצב בטוח") {
                    SettingSwitch(
                        title = "מצב בטוח",
                        subtitle = if (store.safeMode) "פעיל: רק האפליקציות שנבחרו זמינות, וההגדרות מחייבות אימות"
                            else "רק האפליקציות שנבחרו יופיעו בלאנצ'ר",
                        checked = store.safeMode,
                        onCheckedChange = onSafeModeChange
                    )
                    SettingAction(
                        title = "אפליקציות מותרות",
                        subtitle = if (store.safeApps.isEmpty()) "טרם נבחרו אפליקציות"
                            else "${store.safeApps.size} אפליקציות",
                        onClick = { pickingSafeApps = true }
                    )
                    if (isDeviceOwner) {
                        SettingAction(
                            title = "נעילה מלאה פעילה",
                            subtitle = "כאשר מצב בטוח פעיל לא ניתן לצאת מהאפליקציות המותרות, ללא גישה להתראות ולאפליקציות האחרונות",
                            onClick = {}
                        )
                        if (store.biometricLock) {
                            SettingAction(
                                title = "קוד חירום",
                                subtitle = if (store.hasEmergencyPin) "מוגדר. לחיצה לשינוי הקוד"
                                    else "נדרש לפני ההפעלה, ליציאה כאשר טביעת האצבע אינה פועלת",
                                onClick = { onEmergencyPin(PinMode.SET) }
                            )
                        }
                        SettingAction(
                            title = "ביטול נעילה מלאה",
                            subtitle = "מבטל את הרשאת מנהל המכשיר. נדרש לפני הסרת הלאנצ'ר מהטלפון",
                            onClick = { confirmRemoveOwner = true }
                        )
                    } else {
                        SettingAction(
                            title = "נעילה מלאה (מצב קיוסק)",
                            subtitle = "חסימה ברמת המערכת. נדרשת הגדרה חד-פעמית, מהטלפון באמצעות Shizuku או מהמחשב",
                            onClick = { showKioskSetup = true }
                        )
                        SettingSwitch(
                            title = "חסימת מגירת ההתראות",
                            subtitle = when {
                                !store.blockShade ->
                                    "בזמן מצב בטוח או נעילה לזמן קצוב, מגירת ההתראות תיסגר מיד עם פתיחתה"
                                !shadeGuardOn -> "נדרש אישור נגישות. לחיצה לאישור"
                                !shadeGuardConnected ->
                                    "האישור ניתן, אך השירות אינו פעיל. לחיצה לפתיחת מסך הנגישות"
                                !safeOrLocked -> "פעיל. ייכנס לתוקף כשיידלק המצב הבטוח או נעילה לזמן קצוב"
                                else -> "פעיל וחוסם כעת"
                            },
                            checked = store.blockShade,
                            onCheckedChange = { enable ->
                                store.updateBlockShade(enable)
                                if (enable && !shadeGuardOn) {
                                    toast("יש לאשר ל-PixelLauncher גישת נגישות")
                                    ShadeGuardService.openSettings(context)
                                }
                            }
                        )
                        if (store.blockShade && shadeGuardOn && !shadeGuardConnected) {
                            SettingAction(
                                title = "פתיחת הגדרות הנגישות",
                                subtitle = "יש לכבות ולהדליק מחדש את PixelLauncher ברשימה",
                                onClick = { ShadeGuardService.openSettings(context) }
                            )
                        }
                        SettingSwitch(
                            title = "חסימת אפליקציות דרך Shizuku",
                            subtitle = when {
                                !store.shizukuBlock ->
                                    "חלופה לנעילה מלאה: במצב בטוח, כל אפליקציה שאינה מאושרת מושעית ואינה נפתחת. " +
                                        "אינה חוסמת יציאה למסך הבית"
                                ShizukuBridge.state(context) != ShizukuBridge.State.READY ->
                                    "Shizuku אינו פעיל כרגע, ולכן החסימה אינה פועלת. יש להפעילו מחדש"
                                !safeOrLocked -> "פעיל. ייכנס לתוקף כשיידלק המצב הבטוח או נעילה לזמן קצוב"
                                else -> "פעיל וחוסם כעת"
                            },
                            checked = store.shizukuBlock,
                            onCheckedChange = { enable ->
                                if (enable && ShizukuBridge.state(context) != ShizukuBridge.State.READY) {
                                    toast("יש להתקין ולאשר את Shizuku תחילה")
                                    showKioskSetup = true
                                } else {
                                    onShizukuBlockChange(enable)
                                }
                            }
                        )
                    }
                }

                // ---------- סמלים ----------
                if (sec == SettingsSection.APPEARANCE) SettingsGroup("סמלים") {
                    IconPreview(previewApps, store)
                    SettingSlider(
                        title = "גודל אייקון",
                        valueLabel = "${(store.iconScale * 100).roundToInt()}%",
                        value = store.iconScale,
                        range = 0.7f..1.3f,
                        steps = 11,
                        onChange = { store.updateIconScale(it) }
                    )
                    SettingChoice(
                        title = "צורת אייקון",
                        options = IconShape.entries,
                        selected = store.iconShape,
                        label = { it.label },
                        onSelect = { store.updateIconShape(it) }
                    )
                    SettingSwitch(
                        title = "סמלים בצבעי הטפט",
                        subtitle = "באנדרואיד 13 ומעלה, באפליקציות התומכות בכך",
                        checked = store.themedIcons,
                        onCheckedChange = { store.updateThemedIcons(it) }
                    )
                    SettingAction(
                        title = "חבילת אייקונים",
                        subtitle = store.iconPack?.let { pack ->
                            runCatching {
                                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pack, 0)).toString()
                            }.getOrDefault(pack)
                        } ?: "ברירת מחדל",
                        onClick = { pickingIconPack = true }
                    )
                }

                // ---------- מסך הבית ----------
                if (sec == SettingsSection.HOME) SettingsGroup("מסך הבית") {
                    GridSetting(store)
                    SettingChoice(
                        title = "מעבר בין מסכים",
                        options = PageTransition.entries,
                        selected = store.pageTransition,
                        label = { it.label },
                        onSelect = { store.updatePageTransition(it) }
                    )
                    SettingSwitch(
                        title = "שורת חיפוש במסך הבית",
                        subtitle = "הצגת שורת החיפוש מעל ה-Dock. גם כשהיא מוסתרת, החיפוש זמין בראש המגירה",
                        checked = store.on(Feature.HOME_SEARCH),
                        onCheckedChange = { store.setFeature(Feature.HOME_SEARCH, it) }
                    )
                    AnimatedVisibility(store.on(Feature.HOME_SEARCH)) {
                        SettingSlider(
                            title = "שקיפות שורת החיפוש",
                            valueLabel = "${((1f - store.searchOpacity) * 100).roundToInt()}%",
                            value = 1f - store.searchOpacity,
                            range = 0f..0.9f,
                            steps = 8,
                            onChange = { store.updateSearchOpacity(1f - it) }
                        )
                    }
                    SettingSwitch(
                        title = "נקודות התראה",
                        subtitle = if (store.on(Feature.NOTIFICATION_DOTS) && !notificationAccess) needsAccess
                            else "נקודה על האייקון של אפליקציה שממתינות לה התראות",
                        checked = store.on(Feature.NOTIFICATION_DOTS),
                        onCheckedChange = { toggleWithNotificationAccess(Feature.NOTIFICATION_DOTS, it) }
                    )
                    SettingSwitch(
                        title = "טשטוש הטפט",
                        subtitle = "בעת פתיחת המגירה או ההגדרות (אנדרואיד 12 ומעלה)",
                        checked = store.on(Feature.BLUR),
                        onCheckedChange = { store.setFeature(Feature.BLUR, it) }
                    )
                    SettingAction(
                        title = "טפט",
                        subtitle = "בחירת תמונת רקע",
                        onClick = onWallpaper
                    )
                }

                // ---------- במבט חטוף ----------
                if (sec == SettingsSection.GLANCE) SettingsGroup("במבט חטוף") {
                    SettingSwitch(
                        title = "תאריך עברי",
                        subtitle = "מוצג מתחת לתאריך. לאחר השקיעה מתקדם ליום הבא",
                        checked = store.on(Feature.GLANCE_HEBREW_DATE),
                        onCheckedChange = { store.setFeature(Feature.GLANCE_HEBREW_DATE, it) }
                    )
                    SettingSwitch(
                        title = "מזג אוויר",
                        subtitle = "טמפרטורה נוכחית לצד התאריך, לפי מיקום המכשיר (נדרשת הרשאת מיקום)",
                        checked = store.on(Feature.GLANCE_WEATHER),
                        onCheckedChange = {
                            toggleWithPermission(Feature.GLANCE_WEATHER, android.Manifest.permission.ACCESS_COARSE_LOCATION, it)
                        }
                    )
                    SettingSwitch(
                        title = "שעת השקיעה",
                        subtitle = "לפי מיקום המכשיר (נדרשת הרשאת מיקום)",
                        checked = store.on(Feature.GLANCE_SUNSET),
                        onCheckedChange = {
                            toggleWithPermission(Feature.GLANCE_SUNSET, android.Manifest.permission.ACCESS_COARSE_LOCATION, it)
                        }
                    )
                    SettingSwitch(
                        title = "האירוע הבא ביומן",
                        subtitle = "אירועים ביומיים הקרובים (נדרשת הרשאת יומן)",
                        checked = store.on(Feature.GLANCE_CALENDAR),
                        onCheckedChange = {
                            toggleWithPermission(Feature.GLANCE_CALENDAR, android.Manifest.permission.READ_CALENDAR, it)
                        }
                    )
                    SettingSwitch(
                        title = "מתנגן עכשיו",
                        subtitle = if (store.on(Feature.GLANCE_MEDIA) && !notificationAccess) needsAccess
                            else "כרטיס ובו השיר המתנגן ולחצני שליטה",
                        checked = store.on(Feature.GLANCE_MEDIA),
                        onCheckedChange = { toggleWithNotificationAccess(Feature.GLANCE_MEDIA, it) }
                    )
                }

                // ---------- מגירת אפליקציות ----------
                if (sec == SettingsSection.DRAWER) SettingsGroup("מגירת אפליקציות") {
                    SettingChoice(
                        title = "עמודות במגירה",
                        options = listOf(4, 5, 6),
                        selected = store.drawerColumns,
                        label = { "$it" },
                        onSelect = { store.updateDrawerColumns(it) }
                    )
                    SettingSwitch(
                        title = "הצעות חכמות",
                        subtitle = "שורה בראש המגירה ובה האפליקציות שסביר שייפתחו כעת. הלמידה מתבצעת במכשיר בלבד",
                        checked = store.on(Feature.SUGGESTIONS),
                        onCheckedChange = { store.setFeature(Feature.SUGGESTIONS, it) }
                    )
                    SettingSwitch(
                        title = "סימון אפליקציות חדשות",
                        subtitle = "נקודה לצד השם עד לפתיחה הראשונה",
                        checked = store.on(Feature.NEW_BADGES),
                        onCheckedChange = { store.setFeature(Feature.NEW_BADGES, it) }
                    )
                    SettingSlider(
                        title = "שקיפות המגירה",
                        valueLabel = "${((1f - store.drawerOpacity) * 100).roundToInt()}%",
                        value = 1f - store.drawerOpacity,
                        range = 0f..0.7f,
                        steps = 13,
                        onChange = { store.updateDrawerOpacity(1f - it) }
                    )
                    SettingSwitch(
                        title = "סידור אוטומטי לפי קטגוריות",
                        subtitle = "האפליקציות מחולקות לפי הקטגוריה שלהן ב-Google Play",
                        checked = store.drawerCategories,
                        onCheckedChange = { store.updateDrawerCategories(it) }
                    )
                    if (store.drawerCategories) {
                        SettingSwitch(
                            title = "תיקיות",
                            subtitle = "כל קטגוריה מוצגת כתיקייה. כאשר האפשרות כבויה יוצגו כותרות עם קיצורי מעבר",
                            checked = store.drawerFolders,
                            onCheckedChange = { store.updateDrawerFolders(it) }
                        )
                        SettingAction(
                            title = "החזרה לברירת מחדל",
                            subtitle = if (customCategoryCount == 0) {
                                "כל האפליקציות מסודרות לפי Google Play"
                            } else {
                                "ביטול $customCategoryCount שינויים ידניים (העברות ותיקיות שנוצרו) וחזרה לקטגוריות של Google Play"
                            },
                            onClick = { if (customCategoryCount > 0) confirmReset = true }
                        )
                        SettingAction(
                            title = "רענון קטגוריות",
                            subtitle = categoryStatus ?: "בדיקה מחודשת של כל האפליקציות ב-Google Play",
                            onClick = onRefreshCategories
                        )
                    }
                }

                // ---------- מחוות ----------
                if (sec == SettingsSection.GESTURES) SettingsGroup("מחוות במסך הבית") {
                    LauncherGesture.entries.forEach { gesture ->
                        val action = store.gestureActions[gesture] ?: GestureAction.NONE
                        val appName = if (action == GestureAction.APP) {
                            gestureApps.firstOrNull { it.key == store.gestureApps[gesture] }?.label
                        } else {
                            null
                        }
                        SettingAction(
                            title = gesture.label,
                            subtitle = appName?.let { "פתיחת $it" } ?: action.label,
                            onClick = { editingGesture = gesture }
                        )
                    }
                    SettingAction(
                        title = "איפוס מחוות",
                        subtitle = "החזרת כל המחוות להגדרות ברירת המחדל",
                        onClick = { store.resetGestures() }
                    )
                }

                // ---------- אפליקציות מוסתרות ----------
                if (sec == SettingsSection.PRIVACY) SettingsGroup("אפליקציות מוסתרות") {
                    SettingAction(
                        title = "הצגת האפליקציות המוסתרות",
                        subtitle = if (hiddenCount == 0) "אין אפליקציות מוסתרות" else "$hiddenCount אפליקציות מוסתרות",
                        onClick = onShowHidden
                    )
                    SettingSwitch(
                        title = "נעילה בטביעת אצבע",
                        subtitle = if (store.biometricLock) "המסתור נפתח רק בטביעת אצבע" else "חלופה לקוד",
                        checked = store.biometricLock,
                        onCheckedChange = onBiometricChange
                    )
                    if (store.biometricLock) {
                        // בטביעת אצבע בלבד אין קוד
                    } else if (store.hasPin) {
                        SettingAction(
                            title = "שינוי קוד",
                            subtitle = "המסתור נעול בקוד",
                            onClick = { onPin(PinMode.SET) }
                        )
                        SettingAction(
                            title = "הסרת קוד",
                            subtitle = "המסתור ייפתח ללא קוד",
                            onClick = { onPin(PinMode.REMOVE) }
                        )
                    } else {
                        SettingAction(
                            title = "הוספת קוד",
                            subtitle = "נעילת האפליקציות המוסתרות באמצעות קוד ספרות",
                            onClick = { onPin(PinMode.SET) }
                        )
                    }
                }

                // ---------- Dock חכם ----------
                if (sec == SettingsSection.DOCK) SettingsGroup("Dock חכם") {
                    SettingSwitch(
                        title = "Dock לאוזניות ולרכב",
                        subtitle = "בעת חיבור אוזניות או מערכת שמע ברכב, ה-Dock מתחלף",
                        checked = store.on(Feature.CONTEXT_DOCK),
                        onCheckedChange = {
                            store.setFeature(Feature.CONTEXT_DOCK, it)
                            if (it && store.contextDock.isEmpty()) pickingContextDock = true
                        }
                    )
                    SettingAction(
                        title = "אפליקציות ל-Dock הזה",
                        subtitle = if (store.contextDock.isEmpty()) "טרם נבחרו אפליקציות (עד $DOCK_MAX)"
                            else allApps.filter { it.key in store.contextDock }.joinToString(", ") { it.label },
                        onClick = { pickingContextDock = true }
                    )
                }

                // ---------- חיפוש ----------
                if (sec == SettingsSection.SEARCH) SettingsGroup("חיפוש במגירה") {
                    SettingSwitch(
                        title = "אנשי קשר",
                        subtitle = "חיפוש והתקשרות ישירות מהמגירה (נדרשת הרשאת אנשי קשר)",
                        checked = store.on(Feature.SEARCH_CONTACTS),
                        onCheckedChange = {
                            toggleWithPermission(Feature.SEARCH_CONTACTS, android.Manifest.permission.READ_CONTACTS, it)
                        }
                    )
                    SettingSwitch(
                        title = "מחשבון והמרות",
                        subtitle = "למשל 12*8, ‏100$ בשקלים, ‏5 ק\"מ במייל",
                        checked = store.on(Feature.SEARCH_CALCULATOR),
                        onCheckedChange = { store.setFeature(Feature.SEARCH_CALCULATOR, it) }
                    )
                    SettingSwitch(
                        title = "הגדרות המכשיר",
                        subtitle = "למשל \"בלוטות'\" או \"סוללה\"",
                        checked = store.on(Feature.SEARCH_SETTINGS),
                        onCheckedChange = { store.setFeature(Feature.SEARCH_SETTINGS, it) }
                    )
                    SettingSwitch(
                        title = "חיפוש בגוגל",
                        subtitle = "שורה אחרונה לחיפוש הטקסט באינטרנט",
                        checked = store.on(Feature.SEARCH_WEB),
                        onCheckedChange = { store.setFeature(Feature.SEARCH_WEB, it) }
                    )
                }

                // ---------- גיבוי ----------
                if (sec == SettingsSection.BACKUP) SettingsGroup("גיבוי ושחזור") {
                    SettingAction(
                        title = "שמירת גיבוי",
                        subtitle = "שמירת הסידור, התיקיות, השמות וההגדרות בקובץ אחד, ללא קודים",
                        onClick = { host.exportBackup() }
                    )
                    SettingAction(
                        title = "שחזור מגיבוי",
                        subtitle = "טעינה מקובץ גיבוי. פעולה זו מחליפה את הסידור הנוכחי",
                        onClick = { host.importBackup() }
                    )
                    SettingAction(
                        title = "איפוס ההצעות החכמות",
                        subtitle = "מחיקת היסטוריית השימוש שנאגרה בלאנצ'ר",
                        onClick = {
                            Suggestions.clear(context)
                            toast("היסטוריית השימוש נמחקה")
                        }
                    )
                }

                // ---------- כללי ----------
                if (sec == SettingsSection.GENERAL) SettingsGroup("כללי") {
                    SettingAction(
                        title = "אפליקציית הבית",
                        subtitle = "הגדרת PixelLauncher כברירת מחדל",
                        onClick = onDefaultHome
                    )
                }
            }
            }
        }
    }
}

/** תצוגה מקדימה חיה של גודל וצורת האייקונים */
@Composable
private fun IconPreview(apps: List<AppInfo>, store: LauncherStore) {
    val sample = apps.take(5)
    if (sample.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
                .padding(vertical = 18.dp)
        ) {
            sample.forEach { app ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier.size(54.dp * store.iconScale)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        app.label,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

/** פריסת רשת: עמודות ושורות. נשמר כשעוזבים את המחוון, כדי לא להזיז אפליקציות בזמן גרירה */
@Composable
private fun GridSetting(store: LauncherStore) {
    var columns by remember(store.gridColumns) { mutableFloatStateOf(store.gridColumns.toFloat()) }
    var rows by remember(store.gridRows) { mutableFloatStateOf(store.gridRows.toFloat()) }
    val commit = { store.updateGrid(columns.roundToInt(), rows.roundToInt()) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "פריסת רשת",
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${columns.roundToInt()} × ${rows.roundToInt()}",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            GridPreview(columns.roundToInt(), rows.roundToInt())
            Spacer(Modifier.height(8.dp))
            Text("עמודות", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = columns,
                onValueChange = { columns = it },
                onValueChangeFinished = commit,
                valueRange = 3f..MAX_GRID_COLUMNS.toFloat(),
                steps = MAX_GRID_COLUMNS - 4
            )
            Text("שורות", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = rows,
                onValueChange = { rows = it },
                onValueChangeFinished = commit,
                valueRange = 4f..MAX_GRID_ROWS.toFloat(),
                steps = MAX_GRID_ROWS - 5
            )
        }
    }
}

/** ציור קטן של הרשת */
@Composable
private fun GridPreview(columns: Int, rows: Int) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(width = 84.dp, height = 150.dp)
        ) {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        repeat(columns) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(9.dp)
                            ) {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
private fun SettingAction(title: String, subtitle: String?, onClick: () -> Unit) {
    SettingRow(title = title, subtitle = subtitle, onClick = onClick)
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(valueLabel, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        }
    }
}

@Composable
private fun <T> SettingChoice(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                title,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(options) { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { Text(label(option)) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureActionDialog(
    gesture: LauncherGesture,
    current: GestureAction,
    onSelect: (GestureAction) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(gesture.label) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                GestureAction.entries.forEach { action ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(action) }
                    ) {
                        RadioButton(selected = action == current, onClick = { onSelect(action) })
                        Text(action.label, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
private fun AppPickerDialog(apps: List<AppInfo>, onPick: (AppInfo) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("איזו אפליקציה לפתוח?") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                apps.forEach { app ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(app) }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(app.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

/** בחירת האפליקציות שזמינות במצב בטוח */
@Composable
fun SafeAppsDialog(
    apps: List<AppInfo>,
    selected: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    title: String = "אפליקציות מותרות",
    max: Int = Int.MAX_VALUE
) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                apps.forEach { app ->
                    val checked = app.key in chosen
                    val toggle: () -> Unit = {
                        if (checked) chosen.remove(app.key)
                        else if (chosen.size < max) chosen.add(app.key)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { toggle() }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                        Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(app.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen.toSet()) }) { Text("שמירה (${chosen.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

private const val DEVICE_OWNER_COMMAND = "adb shell dpm set-device-owner com.meni.pixellauncher/.AdminReceiver"

/** הוראות להפעלת נעילה מלאה: דרך Shizuku מהטלפון, או דרך adb מהמחשב */
@Composable
private fun KioskSetupDialog(onGranted: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    // מצב Shizuku נבדק מחדש בכל חזרה למסך, כדי לשקף התקנה או אישור שנעשו בינתיים
    var shizuku by remember { mutableStateOf(ShizukuBridge.state(context)) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            shizuku = ShizukuBridge.state(context)
            kotlinx.coroutines.delay(1_000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הפעלת נעילה מלאה") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "נעילה מסוג זה מתאפשרת באנדרואיד רק ל\"מנהל מכשיר\". ניתן להגדיר זאת מהטלפון עצמו באמצעות Shizuku, או מהמחשב באמצעות adb.",
                    fontSize = 15.sp
                )

                // ---------- המסלול הקצר: Shizuku ----------
                Spacer(Modifier.height(16.dp))
                Text(
                    "מהטלפון, באמצעות Shizuku",
                    fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    when (shizuku) {
                        ShizukuBridge.State.NOT_INSTALLED ->
                            "Shizuku הוא יישום חינמי המעניק הרשאות ברמת ADB בלי מחשב ובלי רוט. יש להתקינו ולהפעילו פעם אחת."
                        ShizukuBridge.State.NOT_RUNNING ->
                            "Shizuku מותקן אך השירות אינו פעיל. יש לפתוח אותו ולהפעיל את השירות (באנדרואיד 11 ומעלה הדבר אפשרי דרך ניפוי באגים אלחוטי, ללא מחשב)."
                        ShizukuBridge.State.NO_PERMISSION ->
                            "השירות פעיל. נדרש אישור חד-פעמי של הלאנצ'ר ב-Shizuku."
                        ShizukuBridge.State.READY ->
                            "Shizuku פעיל ומאושר. לחיצה על הכפתור תגדיר את הלאנצ'ר כמנהל מכשיר."
                    },
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                error?.let {
                    Text(
                        it,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                TextButton(
                    enabled = !running,
                    onClick = {
                        error = null
                        when (shizuku) {
                            ShizukuBridge.State.NOT_INSTALLED -> AppsRepository.openPlayStore(context, ShizukuBridge.PACKAGE)
                            ShizukuBridge.State.NOT_RUNNING -> AppsRepository.openApp(context, ShizukuBridge.PACKAGE)
                            ShizukuBridge.State.NO_PERMISSION -> ShizukuBridge.requestPermission()
                            ShizukuBridge.State.READY -> {
                                running = true
                                scope.launch {
                                    val failure = ShizukuBridge.setDeviceOwner()
                                    running = false
                                    if (failure == null) onGranted() else error = failure
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        when {
                            running -> "מגדיר…"
                            shizuku == ShizukuBridge.State.NOT_INSTALLED -> "התקנת Shizuku"
                            shizuku == ShizukuBridge.State.NOT_RUNNING -> "פתיחת Shizuku"
                            shizuku == ShizukuBridge.State.NO_PERMISSION -> "אישור ההרשאה"
                            else -> "הפעלת נעילה מלאה עכשיו"
                        }
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    "מהמחשב, באמצעות adb",
                    fontSize = 15.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    "1. בטלפון: הגדרות ← חשבונות. יש להסיר זמנית את כל החשבונות (גוגל, סמסונג וכדומה), שכן אנדרואיד אינו מאפשר הגדרת מנהל מכשיר כאשר קיימים חשבונות.",
                    "2. יש לחבר את הטלפון למחשב באמצעות כבל, כאשר ניפוי באגים ב-USB מופעל.",
                    "3. בטרמינל שבמחשב יש להריץ את הפקודה הבאה:"
                ).forEach {
                    Text(it, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
                    ) {
                        Text(
                            DEVICE_OWNER_COMMAND,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(DEVICE_OWNER_COMMAND))
                    copied = true
                }) { Text(if (copied) "הועתק ✓" else "העתקת הפקודה") }
                listOf(
                    "4. עם הופעת ההודעה \"Success\", ניתן להחזיר את החשבונות.",
                    "5. בשלב זה יש לחזור לכאן. מעתה מצב בטוח נועל את הטלפון לאפליקציות שנבחרו.",
                    "לתשומת לבך: כל עוד הלאנצ'ר משמש כמנהל מכשיר, לא ניתן להסיר אותו מהטלפון. לשם הסרתו יש לבחור \"ביטול נעילה מלאה\" בהגדרות."
                ).forEach {
                    Text(it, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("הבנתי") } }
    )
}

/** בחירת חבילת אייקונים מאלה שמותקנות */
@Composable
private fun IconPackDialog(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val packs = remember { IconPacks.installed(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("חבילת אייקונים") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val options: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "ברירת מחדל") +
                    packs.map { it.packageName to it.label }
                options.forEach { (pkg, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(pkg) }
                    ) {
                        RadioButton(selected = pkg == current, onClick = { onPick(pkg) })
                        Text(label, fontSize = 16.sp)
                    }
                }
                if (packs.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "לא נמצאו חבילות אייקונים במכשיר. ניתן להוריד חבילות מ-Google Play, לדוגמה Arcticons או Lawnicons.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("market://search?q=icon%20pack&c=apps")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("חיפוש ב-Google Play") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגירה") } }
    )
}

/** המסך הראשי: רשימת הקטגוריות */
@Composable
private fun SectionList(onPick: (SettingsSection) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        SettingsSection.entries.forEach { item ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().clickable { onPick(item) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(item.color.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = item.color,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.subtitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).rotate(180f)
                    )
                }
            }
        }
    }
}
