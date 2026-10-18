package com.meni.pixellauncher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** הקטגוריות במסך ההגדרות, לפי הסדר שבו הן מוצגות */
private enum class SettingsSection(val title: String, val subtitle: String) {
    APPEARANCE("מראה", "גודל וצורת אייקונים, חבילת אייקונים"),
    HOME("מסך הבית", "פריסת רשת, מעברים, נקודות התראה, טפט"),
    GLANCE("במבט חטוף", "תאריך עברי, שקיעה, יומן, מתנגן עכשיו"),
    DRAWER("מגירת אפליקציות", "עמודות, קטגוריות, תיקיות, הצעות"),
    SEARCH("חיפוש במגירה", "אנשי קשר, מחשבון, הגדרות, אינטרנט"),
    GESTURES("מחוות", "מה כל מחווה במסך הבית עושה"),
    DOCK("Dock חכם", "החלפת ה-Dock כשמחוברות אוזניות או רכב"),
    PRIVACY("פרטיות ונעילה", "אפליקציות מוסתרות, קוד, מצב בטוח"),
    BACKUP("גיבוי ושחזור", "שמירה לקובץ, שחזור ואיפוס"),
    GENERAL("כללי", "אפליקציית ברירת המחדל")
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

    // הרשאות: היכולת נדלקת רק אחרי שההרשאה ניתנה
    var pendingFeature by remember { mutableStateOf<Feature?>(null) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingFeature?.let { if (granted) store.setFeature(it, true) else toast("ההרשאה לא ניתנה") }
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
            toast("צריך לאשר ל-PixelLauncher גישה להתראות")
            openNotificationAccess()
        }
    }
    val needsAccess = "צריך גישה להתראות. לחיצה לאישור"

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

    if (showKioskSetup) KioskSetupDialog(onDismiss = { showKioskSetup = false })
    if (confirmRemoveOwner) {
        AlertDialog(
            onDismissRequest = { confirmRemoveOwner = false },
            title = { Text("לבטל את הנעילה המלאה?") },
            text = { Text("הלאנצ'ר יפסיק להיות מנהל המכשיר. מצב בטוח ימשיך להסתיר אפליקציות, אבל לא יחסום יציאה מהן. כדי להפעיל שוב צריך לחזור על ההגדרה מהמחשב.") },
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
            text = { Text("התיקיות שיצרת יימחקו, וכל האפליקציות יחזרו לקטגוריה שלהן ב-Google Play. האפליקציות עצמן לא יימחקו.") },
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

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (section == null) SectionList { section = it }

                // ---------- מצב בטוח ----------
                if (section == SettingsSection.PRIVACY) SettingsGroup("מצב בטוח") {
                    SettingSwitch(
                        title = "מצב בטוח",
                        subtitle = if (store.safeMode) "פעיל: רק האפליקציות שבחרת זמינות, וההגדרות דורשות אימות"
                            else "רק אפליקציות שבחרת יופיעו בלאנצ'ר",
                        checked = store.safeMode,
                        onCheckedChange = onSafeModeChange
                    )
                    SettingAction(
                        title = "אפליקציות מותרות",
                        subtitle = if (store.safeApps.isEmpty()) "עדיין לא נבחרו אפליקציות"
                            else "${store.safeApps.size} אפליקציות",
                        onClick = { pickingSafeApps = true }
                    )
                    if (isDeviceOwner) {
                        SettingAction(
                            title = "נעילה מלאה פעילה",
                            subtitle = "כשמצב בטוח פועל אין יציאה מהאפליקציות המותרות: בלי התראות ובלי אפליקציות אחרונות",
                            onClick = {}
                        )
                        if (store.biometricLock) {
                            SettingAction(
                                title = "קוד חירום",
                                subtitle = if (store.hasEmergencyPin) "מוגדר. לחיצה לשינוי"
                                    else "חובה לפני הפעלה: ליציאה כשטביעת האצבע לא עובדת",
                                onClick = { onEmergencyPin(PinMode.SET) }
                            )
                        }
                        SettingAction(
                            title = "ביטול נעילה מלאה",
                            subtitle = "מסיר את הרשאת מנהל המכשיר. נדרש לפני הסרת הלאנצ'ר מהטלפון",
                            onClick = { confirmRemoveOwner = true }
                        )
                    } else {
                        SettingAction(
                            title = "נעילה מלאה (מצב קיוסק)",
                            subtitle = "חסימה ברמת המערכת. דורש הגדרה חד-פעמית מהמחשב",
                            onClick = { showKioskSetup = true }
                        )
                    }
                }

                // ---------- סמלים ----------
                if (section == SettingsSection.APPEARANCE) SettingsGroup("סמלים") {
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
                        subtitle = "באנדרואיד 13 ומעלה, באפליקציות שתומכות בזה",
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
                if (section == SettingsSection.HOME) SettingsGroup("מסך הבית") {
                    GridSetting(store)
                    SettingChoice(
                        title = "מעבר בין מסכים",
                        options = PageTransition.entries,
                        selected = store.pageTransition,
                        label = { it.label },
                        onSelect = { store.updatePageTransition(it) }
                    )
                    SettingSlider(
                        title = "שקיפות שורת החיפוש",
                        valueLabel = "${((1f - store.searchOpacity) * 100).roundToInt()}%",
                        value = 1f - store.searchOpacity,
                        range = 0f..0.9f,
                        steps = 8,
                        onChange = { store.updateSearchOpacity(1f - it) }
                    )
                    SettingSwitch(
                        title = "נקודות התראה",
                        subtitle = if (store.on(Feature.NOTIFICATION_DOTS) && !notificationAccess) needsAccess
                            else "נקודה על אייקון של אפליקציה שיש לה התראות",
                        checked = store.on(Feature.NOTIFICATION_DOTS),
                        onCheckedChange = { toggleWithNotificationAccess(Feature.NOTIFICATION_DOTS, it) }
                    )
                    SettingSwitch(
                        title = "טשטוש הטפט",
                        subtitle = "כשהמגירה או ההגדרות פתוחות (אנדרואיד 12 ומעלה)",
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
                if (section == SettingsSection.GLANCE) SettingsGroup("במבט חטוף") {
                    SettingSwitch(
                        title = "תאריך עברי",
                        subtitle = "מתחת לתאריך. אחרי השקיעה עובר ליום הבא",
                        checked = store.on(Feature.GLANCE_HEBREW_DATE),
                        onCheckedChange = { store.setFeature(Feature.GLANCE_HEBREW_DATE, it) }
                    )
                    SettingSwitch(
                        title = "שעת השקיעה",
                        subtitle = "לפי המיקום שלך (צריך הרשאת מיקום)",
                        checked = store.on(Feature.GLANCE_SUNSET),
                        onCheckedChange = {
                            toggleWithPermission(Feature.GLANCE_SUNSET, android.Manifest.permission.ACCESS_COARSE_LOCATION, it)
                        }
                    )
                    SettingSwitch(
                        title = "האירוע הבא ביומן",
                        subtitle = "ביומיים הקרובים (צריך הרשאת יומן)",
                        checked = store.on(Feature.GLANCE_CALENDAR),
                        onCheckedChange = {
                            toggleWithPermission(Feature.GLANCE_CALENDAR, android.Manifest.permission.READ_CALENDAR, it)
                        }
                    )
                    SettingSwitch(
                        title = "מתנגן עכשיו",
                        subtitle = if (store.on(Feature.GLANCE_MEDIA) && !notificationAccess) needsAccess
                            else "כרטיס עם השיר שמתנגן וכפתורי שליטה",
                        checked = store.on(Feature.GLANCE_MEDIA),
                        onCheckedChange = { toggleWithNotificationAccess(Feature.GLANCE_MEDIA, it) }
                    )
                }

                // ---------- מגירת אפליקציות ----------
                if (section == SettingsSection.DRAWER) SettingsGroup("מגירת אפליקציות") {
                    SettingChoice(
                        title = "עמודות במגירה",
                        options = listOf(4, 5, 6),
                        selected = store.drawerColumns,
                        label = { "$it" },
                        onSelect = { store.updateDrawerColumns(it) }
                    )
                    SettingSwitch(
                        title = "הצעות חכמות",
                        subtitle = "שורה בראש המגירה עם מה שכנראה תפתח עכשיו. נלמד רק במכשיר",
                        checked = store.on(Feature.SUGGESTIONS),
                        onCheckedChange = { store.setFeature(Feature.SUGGESTIONS, it) }
                    )
                    SettingSwitch(
                        title = "סימון אפליקציות חדשות",
                        subtitle = "נקודה ליד השם עד שפותחים בפעם הראשונה",
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
                        subtitle = "האפליקציות מתחלקות לפי הקטגוריה שלהן ב-Google Play",
                        checked = store.drawerCategories,
                        onCheckedChange = { store.updateDrawerCategories(it) }
                    )
                    if (store.drawerCategories) {
                        SettingSwitch(
                            title = "תיקיות",
                            subtitle = "כל קטגוריה מוצגת כתיקייה. כבוי: כותרות עם קיצורי קפיצה",
                            checked = store.drawerFolders,
                            onCheckedChange = { store.updateDrawerFolders(it) }
                        )
                        SettingAction(
                            title = "החזרה לברירת מחדל",
                            subtitle = if (customCategoryCount == 0) {
                                "כל האפליקציות מסודרות לפי Google Play"
                            } else {
                                "ביטול $customCategoryCount שינויים ידניים (העברות ותיקיות שיצרת), וחזרה לקטגוריות של Google Play"
                            },
                            onClick = { if (customCategoryCount > 0) confirmReset = true }
                        )
                        SettingAction(
                            title = "רענון קטגוריות",
                            subtitle = categoryStatus ?: "בדיקה מחדש של כל האפליקציות ב-Google Play",
                            onClick = onRefreshCategories
                        )
                    }
                }

                // ---------- מחוות ----------
                if (section == SettingsSection.GESTURES) SettingsGroup("מחוות במסך הבית") {
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
                        subtitle = "חזרה להגדרות המקוריות",
                        onClick = { store.resetGestures() }
                    )
                }

                // ---------- אפליקציות מוסתרות ----------
                if (section == SettingsSection.PRIVACY) SettingsGroup("אפליקציות מוסתרות") {
                    SettingAction(
                        title = "הצגת האפליקציות המוסתרות",
                        subtitle = if (hiddenCount == 0) "אין אפליקציות מוסתרות" else "$hiddenCount אפליקציות מוסתרות",
                        onClick = onShowHidden
                    )
                    SettingSwitch(
                        title = "נעילה בטביעת אצבע",
                        subtitle = if (store.biometricLock) "המסתור נפתח רק בטביעת אצבע" else "במקום קוד",
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
                            subtitle = "המסתור ייפתח בלי קוד",
                            onClick = { onPin(PinMode.REMOVE) }
                        )
                    } else {
                        SettingAction(
                            title = "הוספת קוד",
                            subtitle = "נעילת האפליקציות המוסתרות בקוד ספרות",
                            onClick = { onPin(PinMode.SET) }
                        )
                    }
                }

                // ---------- Dock חכם ----------
                if (section == SettingsSection.DOCK) SettingsGroup("Dock חכם") {
                    SettingSwitch(
                        title = "Dock לאוזניות ולרכב",
                        subtitle = "כשאוזניות או מערכת שמע של רכב מחוברות, ה-Dock מתחלף",
                        checked = store.on(Feature.CONTEXT_DOCK),
                        onCheckedChange = {
                            store.setFeature(Feature.CONTEXT_DOCK, it)
                            if (it && store.contextDock.isEmpty()) pickingContextDock = true
                        }
                    )
                    SettingAction(
                        title = "אפליקציות ל-Dock הזה",
                        subtitle = if (store.contextDock.isEmpty()) "עדיין לא נבחרו (עד $DOCK_MAX)"
                            else allApps.filter { it.key in store.contextDock }.joinToString(", ") { it.label },
                        onClick = { pickingContextDock = true }
                    )
                }

                // ---------- חיפוש ----------
                if (section == SettingsSection.SEARCH) SettingsGroup("חיפוש במגירה") {
                    SettingSwitch(
                        title = "אנשי קשר",
                        subtitle = "חיפוש ושיחה ישר מהמגירה (צריך הרשאת אנשי קשר)",
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
                        subtitle = "שורה אחרונה שמחפשת את הטקסט באינטרנט",
                        checked = store.on(Feature.SEARCH_WEB),
                        onCheckedChange = { store.setFeature(Feature.SEARCH_WEB, it) }
                    )
                }

                // ---------- גיבוי ----------
                if (section == SettingsSection.BACKUP) SettingsGroup("גיבוי ושחזור") {
                    SettingAction(
                        title = "שמירת גיבוי",
                        subtitle = "הסידור, התיקיות, השמות וההגדרות לקובץ אחד (בלי קודים)",
                        onClick = { host.exportBackup() }
                    )
                    SettingAction(
                        title = "שחזור מגיבוי",
                        subtitle = "טעינה מקובץ גיבוי. מחליף את הסידור הנוכחי",
                        onClick = { host.importBackup() }
                    )
                    SettingAction(
                        title = "איפוס ההצעות החכמות",
                        subtitle = "מחיקת היסטוריית הפתיחות שהלאנצ'ר למד",
                        onClick = {
                            Suggestions.clear(context)
                            toast("ההיסטוריה נמחקה")
                        }
                    )
                }

                // ---------- כללי ----------
                if (section == SettingsSection.GENERAL) SettingsGroup("כללי") {
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

/** הוראות להפעלת נעילה מלאה: הגדרת הלאנצ'ר כמנהל מכשיר דרך adb */
@Composable
private fun KioskSetupDialog(onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הפעלת נעילה מלאה") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "אנדרואיד מאפשר נעילה כזו רק ל\"מנהל מכשיר\", ומגדירים אותו פעם אחת מהמחשב:",
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    "1. בטלפון: הגדרות ← חשבונות. מסירים זמנית את כל החשבונות (גוגל, סמסונג וכו'). אנדרואיד לא מאפשר מנהל מכשיר כשיש חשבונות.",
                    "2. מחברים את הטלפון למק בכבל, עם ניפוי באגים ב-USB.",
                    "3. בטרמינל במק מריצים את הפקודה:"
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
                    "4. כשמופיע \"Success\", מחזירים את החשבונות.",
                    "5. חוזרים לכאן: מעכשיו מצב בטוח נועל את הטלפון לאפליקציות שבחרת.",
                    "שים לב: כל עוד הלאנצ'ר מנהל מכשיר, אי אפשר להסיר אותו מהטלפון. כדי להסיר: \"ביטול נעילה מלאה\" בהגדרות."
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
                        "לא נמצאו חבילות אייקונים במכשיר. אפשר להוריד מ-Google Play, למשל Arcticons או Lawnicons.",
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
