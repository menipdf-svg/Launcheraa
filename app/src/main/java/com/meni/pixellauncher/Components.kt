package com.meni.pixellauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// כיוון הטקסט הטבעי של המכשיר (עברית = RTL), גם בתוך אזורים שכפינו בהם LTR
val LocalNaturalDirection = staticCompositionLocalOf { LayoutDirection.Ltr }

/** מה להציג על האייקונים: נקודות התראה וסימון "חדש" */
data class IconBadges(val dots: Boolean = false, val newApps: Set<String> = emptySet())

val LocalIconBadges = compositionLocalOf { IconBadges() }

class MenuAction(
    val label: String,
    val icon: ImageBitmap? = null,
    val vector: ImageVector? = null,
    val isDivider: Boolean = false,
    /** פעולה שמוצגת כסמל בלבד, בשורה אחת בתחתית התפריט */
    val iconOnly: Boolean = false,
    val onClick: () -> Unit = {}
) {
    companion object {
        val Divider = MenuAction("", isDivider = true)
    }
}

/**
 * מחווה אחת לאייקון: לחיצה רגילה, לחיצה ארוכה, וגרירה אחרי לחיצה ארוכה.
 * onLongPress מקבל את מיקום האצבע בקואורדינטות של המסך כולו.
 */
fun Modifier.launcherItemGestures(
    key: Any,
    onClick: () -> Unit,
    onLongPress: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: () -> Unit,
    onSwipeUp: (() -> Unit)? = null,
    /** כשמוגדר, לחיצה רגילה מדווחת עם המיקום שלה (לתיקייה גדולה, שבה כל סמל לחיץ) */
    onClickAt: ((Offset) -> Unit)? = null
): Modifier = composed {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    val swipeUp by rememberUpdatedState(onSwipeUp)
    val clickAt by rememberUpdatedState(onClickAt)

    // האייקון נלחץ פנימה ברגע הנגיעה, וקופץ חזרה בשחרור
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 700f),
        label = "iconPress"
    )

    this
        .graphicsLayer {
            scaleX = press
            scaleY = press
        }
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pressed = true
                try {
                var outcome = 0 // 0 = לחיצה ארוכה, 1 = לחיצה רגילה, 2 = בוטל (גלילה)
                var swiped = false
                // מעקב אחרי התנועה בחלון הזמן של הלחיצה הארוכה, כדי לזהות החלקה מעלה
                val slop = viewConfiguration.touchSlop
                var totalX = 0f
                var totalY = 0f
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            outcome = 2
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            outcome = 1
                            break
                        }
                        val d = change.positionChange()
                        totalX += d.x
                        totalY += d.y
                        if (abs(totalX) > slop || abs(totalY) > slop) {
                            // החלקה מעלה נתפסת רק כשהוגדרה פעולה, אחרת המחווה
                            // ממשיכה להורה (פתיחת המגירה, גלילה בין דפים)
                            if (swipeUp != null && totalY < -slop && abs(totalY) > abs(totalX)) {
                                change.consume()
                                swiped = true
                            }
                            outcome = 2
                            break
                        }
                    }
                }
                if (outcome == 1) {
                    val at = clickAt
                    if (at != null) at(down.position) else click()
                    return@awaitEachGesture
                }
                if (outcome == 2) {
                    if (swiped) {
                        // ממתינים לסוף המגע כדי שההורה לא יקבל את השארית
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                        }
                        swipeUp?.invoke()
                    }
                    return@awaitEachGesture
                }

                val layout = coords[0]
                val root = if (layout != null && layout.isAttached) layout.localToRoot(down.position) else down.position
                longPress(root)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            change.consume()
                            move(delta)
                        }
                    }
                } finally {
                    release()
                }
                } finally {
                    pressed = false
                }
            }
        }
}

@Composable
fun AppIcon(
    app: AppInfo,
    showLabel: Boolean,
    labelColor: Color,
    labelShadow: Boolean,
    hidden: Boolean,
    menuOpen: Boolean,
    menuActions: () -> List<MenuAction>,
    onDismissMenu: () -> Unit,
    onClick: () -> Unit,
    onLongPress: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: () -> Unit,
    iconSize: Dp = 54.dp,
    onSwipeUp: (() -> Unit)? = null
) {
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer { alpha = if (hidden) 0f else 1f }
                .clip(RoundedCornerShape(18.dp))
                .launcherItemGestures(app.key, onClick, onLongPress, onMove, onRelease, onSwipeUp)
                .padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            val badges = LocalIconBadges.current
            val notifications = if (badges.dots) NotifService.counts[app.packageName] ?: 0 else 0
            Box {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    modifier = Modifier.size(iconSize)
                )
                if (notifications > 0) NotificationDot(Modifier.align(Alignment.TopEnd), iconSize, notifications)
            }
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // נקודה קטנה ליד השם של אפליקציה שהותקנה ועוד לא נפתחה
                    if (app.key in badges.newApps) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = app.label,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = labelColor,
                            textAlign = TextAlign.Center,
                            shadow = if (labelShadow) Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 4f) else null
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 72.dp)
                    )
                }
            }
        }

        if (menuOpen) {
            val actions = remember(app.key) { menuActions() }
            LauncherMenu(actions, onDismissMenu)
        }
    }
}

/** שורה בתפריט של מסך הבית */
@Composable
fun MenuRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** האזור "הסרה" שמופיע למעלה בזמן גרירה */
@Composable
fun RemoveZone(hovering: Boolean, modifier: Modifier = Modifier) {
    val background by animateColorAsState(
        if (hovering) MaterialTheme.colorScheme.errorContainer else Color.Black.copy(alpha = 0.25f),
        label = "removeBg"
    )
    val content = if (hovering) MaterialTheme.colorScheme.onErrorContainer else Color.White
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(background)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Outlined.Close, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("הסרה", color = content, fontSize = 15.sp)
        }
    }
}

/** נקודת התראה בפינת האייקון, כמו בפיקסל */
@Composable
fun NotificationDot(modifier: Modifier, iconSize: Dp, count: Int = 0) {
    // נקודה בלבד כשאין ספירה, ועיגול עם המספר כשיש יותר מהתראה אחת
    val showNumber = count > 1
    val size = if (showNumber) iconSize * 0.42f else iconSize * 0.24f
    Box(
        modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .height(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        if (showNumber) {
            Text(
                text = if (count > 99) "99+" else "$count",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = with(LocalDensity.current) { (size * 0.52f).toSp() },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * תיקייה במסך הבית. קטנה: ריבוע עם ארבעה סמלים זעירים, ולחיצה פותחת אותה.
 * גדולה (2×2): רשת של עד תשעה סמלים, וכל סמל נפתח ישירות בלחיצה.
 */
@Composable
fun HomeFolderTile(
    itemKey: Any,
    apps: List<AppInfo>,
    name: String,
    iconSize: Dp,
    shape: Shape,
    showLabel: Boolean,
    large: Boolean,
    hidden: Boolean,
    menuOpen: Boolean,
    menuActions: () -> List<MenuAction>,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onLaunch: (AppInfo) -> Unit,
    onLongPress: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: () -> Unit
) {
    val label = name.ifBlank { "תיקייה" }
    val labelStyle = TextStyle(
        fontSize = 12.sp,
        color = Color.White,
        textAlign = TextAlign.Center,
        shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 4f)
    )
    Box(Modifier.graphicsLayer { alpha = if (hidden) 0f else 1f }) {
        if (!large) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .launcherItemGestures(itemKey, onOpen, onLongPress, onMove, onRelease)
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Box(
                    Modifier
                        .size(iconSize)
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    val mini = iconSize * 0.36f
                    Column(
                        verticalArrangement = Arrangement.spacedBy(iconSize * 0.05f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        apps.take(4).chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(iconSize * 0.05f)) {
                                row.forEach { app ->
                                    Image(app.icon, contentDescription = null, modifier = Modifier.size(mini))
                                }
                            }
                        }
                    }
                }
                if (showLabel) {
                    Spacer(Modifier.height(6.dp))
                    Text(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 72.dp))
                }
            }
        } else {
            // תיקייה גדולה: 3×3. מעל תשעה, התא האחרון מראה כמה עוד יש ופותח את התיקייה
            val cols = 3
            val capacity = cols * cols
            val shown = if (apps.size > capacity) apps.take(capacity - 1) else apps
            val extra = apps.size - shown.size
            var gridSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            val padPx = with(LocalDensity.current) { 10.dp.toPx() }
            Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                        .onSizeChanged { gridSize = it }
                        .launcherItemGestures(
                            itemKey, onOpen, onLongPress, onMove, onRelease,
                            onClickAt = { pos ->
                                val w = gridSize.width - padPx * 2
                                val h = gridSize.height - padPx * 2
                                val x = pos.x - padPx
                                val y = pos.y - padPx
                                if (w <= 0f || h <= 0f || x < 0f || y < 0f || x >= w || y >= h) {
                                    onOpen()
                                } else {
                                    val index = (y / (h / cols)).toInt() * cols + (x / (w / cols)).toInt()
                                    val app = shown.getOrNull(index)
                                    if (app != null) onLaunch(app) else onOpen()
                                }
                            }
                        )
                        .padding(10.dp)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        repeat(cols) { r ->
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                repeat(cols) { c ->
                                    val index = r * cols + c
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                        val app = shown.getOrNull(index)
                                        if (app != null) {
                                            Image(
                                                app.icon,
                                                contentDescription = app.label,
                                                modifier = Modifier.fillMaxSize(0.8f)
                                            )
                                        } else if (index == shown.size && extra > 0) {
                                            Box(
                                                Modifier
                                                    .fillMaxSize(0.8f)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.25f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("+$extra", color = Color.White, fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (showLabel) {
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (menuOpen) {
            val actions = remember(itemKey) { menuActions() }
            LauncherMenu(actions, onDismissMenu)
        }
    }
}

/** התיקייה הפתוחה במסך הבית: רשת של האפליקציות שבתוכה */
@Composable
fun HomeFolderDialog(
    apps: List<AppInfo>,
    name: String,
    onOpen: (AppInfo) -> Unit,
    onRemove: (AppInfo) -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                name.ifBlank { "תיקייה" },
                modifier = Modifier.clickable { onRename() }
            )
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                apps.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { app ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .pointerInput(app.key) {
                                        detectTapGestures(
                                            onTap = { onOpen(app) },
                                            onLongPress = { onRemove(app) }
                                        )
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(52.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    app.label,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(
                    "לחיצה ארוכה על אפליקציה מוציאה אותה מהתיקייה",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגירה") } }
    )
}

/** שורה אחת בחלון העריכה */
@Composable
private fun EditRow(title: String, subtitle: String?, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** כל אפשרויות העריכה של אפליקציה, במקום תפריט ארוך בלחיצה ארוכה */
@Composable
fun AppEditDialog(
    app: AppInfo,
    swipeLabel: String?,
    hasCustomIcon: Boolean,
    canMoveToFolder: Boolean,
    onRename: () -> Unit,
    onPickIcon: () -> Unit,
    onResetIcon: () -> Unit,
    onSwipe: () -> Unit,
    onClearSwipe: () -> Unit,
    onMoveToFolder: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EditRow("שינוי שם", app.originalLabel.takeIf { it != app.label }, Icons.Outlined.Create, onRename)
                EditRow(
                    "בחירת אייקון",
                    if (hasCustomIcon) "אייקון מותאם פעיל" else "תמונה מהגלריה",
                    Icons.Outlined.Star,
                    onPickIcon
                )
                if (hasCustomIcon) {
                    EditRow("החזרת האייקון המקורי", null, Icons.Outlined.Refresh, onResetIcon)
                }
                EditRow(
                    "מחווה על האייקון",
                    swipeLabel?.let { "החלקה מעלה פותחת את $it" } ?: "החלקה מעלה תפתח אפליקציה אחרת",
                    Icons.Outlined.Add,
                    onSwipe
                )
                if (swipeLabel != null) {
                    EditRow("ביטול המחווה", null, Icons.Outlined.Close, onClearSwipe)
                }
                if (canMoveToFolder) {
                    EditRow("העברה לתיקייה במגירה", null, Icons.Outlined.Edit, onMoveToFolder)
                }
                EditRow("הסתרת האפליקציה", null, Icons.Outlined.Lock, onHide)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגירה") } }
    )
}

/** תפריט הלחיצה הארוכה: קיצורי דרך עם טקסט, ופעולות הלאנצ'ר כשורת סמלים */
@Composable
fun LauncherMenu(actions: List<MenuAction>, onDismissMenu: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalNaturalDirection.current) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismissMenu,
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            tonalElevation = 0.dp
        ) {
            // פעולות הלאנצ'ר מוצגות כסמלים בשורה אחת, והטקסט נשמר
            // לקיצורי הדרך של האפליקציה עצמה
            val rows = actions.filterNot { it.iconOnly }
            val compact = actions.filter { it.iconOnly }
            rows.forEach { action ->
                if (action.isDivider) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(action.label, fontSize = 15.sp) },
                        onClick = {
                            onDismissMenu()
                            action.onClick()
                        },
                        leadingIcon = if (action.icon != null) {
                            {
                                Image(
                                    bitmap = action.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).clip(CircleShape)
                                )
                            }
                        } else if (action.vector != null) {
                            {
                                Icon(
                                    action.vector,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            null
                        },
                        contentPadding = PaddingValues(horizontal = 18.dp)
                    )
                }
            }
            if (compact.isNotEmpty()) {
                if (rows.isNotEmpty()) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    compact.forEach { action ->
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onDismissMenu()
                                    action.onClick()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            action.vector?.let {
                                Icon(
                                    it,
                                    contentDescription = action.label,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** סמל הגדלה: חצים לפינות (לפי open_in_full של Material) */
val ExpandIcon: ImageVector by lazy {
    ImageVector.Builder("expand", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.addPathNodes(
                "M21,11V3h-8l3.29,3.29l-10,10L3,13v8h8l-3.29,-3.29l10,-10L21,11z"
            ),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black)
        )
    }.build()
}

/** סמל הקטנה: חצים פנימה (לפי close_fullscreen של Material) */
val ShrinkIcon: ImageVector by lazy {
    ImageVector.Builder("shrink", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.addPathNodes(
                "M22,3.41l-5.29,5.29L20,12h-8V4l3.29,3.29L20.59,2L22,3.41z" +
                    "M3.41,22l5.29,-5.29L12,20v-8H4l3.29,3.29L2,20.59L3.41,22z"
            ),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black)
        )
    }.build()
}
