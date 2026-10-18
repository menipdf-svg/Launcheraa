package com.meni.pixellauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object TimedLockText {
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun clock(time: Long): String = clock.format(Date(time))

    /** 45 ← "45 דקות", 90 ← "שעה ו-30 דקות" */
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val hours = when (h) {
            0 -> ""
            1 -> "שעה"
            2 -> "שעתיים"
            else -> "$h שעות"
        }
        return when {
            h == 0 -> "$m דקות"
            m == 0 -> hours
            else -> "$hours ו-$m דקות"
        }
    }

    /** זמן שנשאר, למשל "1:05:09" או "23:41" */
    fun remaining(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

/**
 * הגדרת נעילה לזמן קצוב: כמה זמן, אילו אפליקציות, והאם אפשר לבטל.
 * onStart(עד מתי, ללא אפשרות ביטול, האפליקציות)
 */
@Composable
fun TimedLockDialog(
    apps: List<AppInfo>,
    initialApps: Set<String>,
    hasOwnerLock: Boolean,
    isDeviceOwner: Boolean,
    onStart: (until: Long, strict: Boolean, apps: Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var minutes by remember { mutableFloatStateOf(30f) }
    var chosen by remember { mutableStateOf(initialApps.filter { key -> apps.any { it.key == key } }.toSet()) }
    var strict by remember { mutableStateOf(!hasOwnerLock) }
    var pickingApps by remember { mutableStateOf(false) }
    var confirmStrict by remember { mutableStateOf(false) }

    val mins = minutes.roundToInt()
    val until = remember(mins) { System.currentTimeMillis() + mins * 60_000L }

    if (pickingApps) {
        SafeAppsDialog(
            apps = apps,
            selected = chosen,
            title = "אפליקציות מותרות בזמן הנעילה",
            onSave = {
                chosen = it
                pickingApps = false
            },
            onDismiss = { pickingApps = false }
        )
    }

    if (confirmStrict) {
        AlertDialog(
            onDismissRequest = { confirmStrict = false },
            title = { Text("לנעול ללא אפשרות ביטול?") },
            text = {
                Text("הטלפון יינעל ל-${chosen.size} אפליקציות עד השעה ${TimedLockText.clock(until)}. לא ניתן יהיה לבטל את הנעילה, גם לא באמצעות קוד או טביעת אצבע.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStrict = false
                    onStart(System.currentTimeMillis() + mins * 60_000L, true, chosen)
                }) { Text("נעילה") }
            },
            dismissButton = { TextButton(onClick = { confirmStrict = false }) { Text("חזרה") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("נעילה לזמן קצוב") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                // ---------- כמה זמן ----------
                Text(
                    "${TimedLockText.duration(mins)} · עד ${TimedLockText.clock(until)}",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(15, 30, 60, 120, 180)) { preset ->
                        FilterChip(
                            selected = mins == preset,
                            onClick = { minutes = preset.toFloat() },
                            label = { Text(TimedLockText.duration(preset)) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }
                Slider(
                    value = minutes,
                    onValueChange = { minutes = it },
                    valueRange = 5f..240f,
                    steps = 46
                )

                // ---------- אילו אפליקציות ----------
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pickingApps = true }
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("אפליקציות מותרות", fontSize = 16.sp)
                        Text(
                            if (chosen.isEmpty()) "לחיצה לבחירת אפליקציות"
                            else apps.filter { it.key in chosen }.joinToString(", ") { it.label },
                            fontSize = 13.sp,
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ---------- ביטול ----------
                Spacer(Modifier.height(12.dp))
                Text("ביטול לפני תום הזמן", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                ChoiceRow(
                    selected = !strict,
                    enabled = hasOwnerLock,
                    title = "ניתן לבטל באמצעות קוד או טביעת אצבע",
                    subtitle = if (hasOwnerLock) null else "יש להגדיר תחילה קוד או טביעת אצבע, תחת הגדרות ← אפליקציות מוסתרות",
                    onClick = { strict = false }
                )
                ChoiceRow(
                    selected = strict,
                    enabled = true,
                    title = "ללא אפשרות ביטול",
                    subtitle = "הנעילה תשתחרר רק בתום הזמן שנקבע",
                    onClick = { strict = true }
                )

                if (!isDeviceOwner) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ללא נעילה מלאה ניתן לצאת דרך ההתראות או האפליקציות האחרונות. להפעלתה: הגדרות ← מצב בטוח ← נעילה מלאה.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotEmpty(),
                onClick = {
                    if (strict) confirmStrict = true
                    else onStart(System.currentTimeMillis() + mins * 60_000L, false, chosen)
                }
            ) { Text("נעילה") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, enabled: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column {
            Text(
                title,
                fontSize = 15.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
