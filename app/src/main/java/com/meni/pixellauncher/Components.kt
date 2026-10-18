package com.meni.pixellauncher

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
    onRelease: () -> Unit
): Modifier = composed {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)

    this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var outcome = 0 // 0 = לחיצה ארוכה, 1 = לחיצה רגילה, 2 = בוטל (גלילה)
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    val up = waitForUpOrCancellation()
                    outcome = if (up != null) {
                        up.consume()
                        1
                    } else {
                        2
                    }
                }
                if (outcome == 1) {
                    click()
                    return@awaitEachGesture
                }
                if (outcome == 2) return@awaitEachGesture

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
    iconSize: Dp = 54.dp
) {
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer { alpha = if (hidden) 0f else 1f }
                .clip(RoundedCornerShape(18.dp))
                .launcherItemGestures(app.key, onClick, onLongPress, onMove, onRelease)
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
                if (notifications > 0) NotificationDot(Modifier.align(Alignment.TopEnd), iconSize)
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
            CompositionLocalProvider(LocalLayoutDirection provides LocalNaturalDirection.current) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = onDismissMenu,
                    shape = RoundedCornerShape(24.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                    tonalElevation = 0.dp
                ) {
                    actions.forEach { action ->
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
                }
            }
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
fun NotificationDot(modifier: Modifier, iconSize: Dp) {
    Box(
        modifier
            .size(iconSize * 0.24f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}
