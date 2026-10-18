package com.meni.pixellauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

// ================= מחוות =================

/** המחוות שאפשר להגדיר במסך הבית */
enum class LauncherGesture(val label: String, val fingers: Int, val default: GestureAction) {
    SWIPE_UP("החלקה למעלה", 1, GestureAction.DRAWER),
    SWIPE_DOWN("החלקה למטה", 1, GestureAction.NOTIFICATIONS),
    DOUBLE_TAP("הקשה כפולה על מקום ריק", 1, GestureAction.NONE),
    TWO_UP("החלקה למעלה בשתי אצבעות", 2, GestureAction.NONE),
    TWO_DOWN("החלקה למטה בשתי אצבעות", 2, GestureAction.NONE),
    THREE_SIDE("החלקה הצידה בשלוש אצבעות", 3, GestureAction.NONE),
    VOLUME_UP_TWICE("לחיצה כפולה על ווליום עליון", 0, GestureAction.HIDDEN)
}

/** מה קורה כשעושים מחווה */
enum class GestureAction(val label: String) {
    NONE("ללא"),
    DRAWER("פתיחת המגירה"),
    SEARCH("חיפוש"),
    NOTIFICATIONS("פתיחת ההתראות"),
    HIDDEN("אפליקציות מוסתרות"),
    SETTINGS("הגדרות הלאנצ'ר"),
    WIDGETS("ווידג'טים"),
    APP("פתיחת אפליקציה…")
}

/**
 * מזהה החלקות עם 2 או 3 אצבעות. ברגע שיש כמה אצבעות על המסך, המגע נחסם
 * כדי שה-Pager והמגירה לא יגיבו גם הם.
 */
fun Modifier.multiFingerGestures(enabled: Boolean, onGesture: (LauncherGesture) -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        val threshold = 70.dp.toPx()
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var start: Offset? = null
            var fingers = 0
            var triggered = false
            var blocking = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    blocking = true
                    val center = pressed.fold(Offset.Zero) { sum, c -> sum + c.position } / pressed.size.toFloat()
                    // מספר האצבעות השתנה: מתחילים למדוד מחדש
                    if (pressed.size != fingers) {
                        fingers = pressed.size
                        start = center
                    }
                    val move = center - (start ?: center)
                    if (!triggered) {
                        val gesture = when {
                            fingers == 2 && move.y < -threshold && abs(move.y) > abs(move.x) -> LauncherGesture.TWO_UP
                            fingers == 2 && move.y > threshold && abs(move.y) > abs(move.x) -> LauncherGesture.TWO_DOWN
                            fingers >= 3 && abs(move.x) > threshold && abs(move.x) > abs(move.y) -> LauncherGesture.THREE_SIDE
                            else -> null
                        }
                        if (gesture != null) {
                            triggered = true
                            onGesture(gesture)
                        }
                    }
                }
                if (blocking) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

// ================= קוד =================

/** שומר את הקוד כ-hash עם salt, לא כטקסט */
object PinCode {
    fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

enum class PinMode { UNLOCK, SET, REMOVE }

/**
 * כל התהליך של קוד:
 * UNLOCK: הזנת קוד ואז פתיחה. SET: (קוד ישן אם יש) ← קוד חדש ← אימות. REMOVE: קוד ישן ← הסרה.
 */
@Composable
fun PinFlow(
    mode: PinMode,
    store: LauncherStore,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    emergency: Boolean = false
) {
    val hasExisting = if (emergency) store.hasEmergencyPin else store.hasPin
    val check: (String) -> Boolean = { if (emergency) store.checkEmergencyPin(it) else store.checkPin(it) }
    val save: (String?) -> Unit = { if (emergency) store.setEmergencyPin(it) else store.setPin(it) }
    var step by remember {
        mutableStateOf(if (mode == PinMode.SET && !hasExisting) "new" else "verify")
    }
    var firstPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val (title, subtitle) = when (step) {
        "verify" -> when (mode) {
            PinMode.UNLOCK -> (if (emergency) "קוד חירום" else "אימות") to "הזנת קוד"
            PinMode.SET -> "שינוי קוד" to "הזנת הקוד הנוכחי"
            PinMode.REMOVE -> "הסרת קוד" to "הזנת הקוד הנוכחי"
        }
        "new" -> (if (emergency) "קוד חירום חדש" else "קוד חדש") to "לפחות 4 ספרות"
        else -> "קוד חדש" to "הזנת הקוד שוב לאימות"
    }

    PinPad(
        title = title,
        subtitle = subtitle,
        error = error,
        resetKey = step,
        onCancel = onCancel,
        onSubmit = { pin ->
            when (step) {
                "verify" -> if (check(pin)) {
                    error = null
                    when (mode) {
                        PinMode.UNLOCK -> onSuccess()
                        PinMode.REMOVE -> {
                            save(null)
                            onSuccess()
                        }
                        PinMode.SET -> step = "new"
                    }
                    true
                } else {
                    error = "קוד שגוי"
                    false
                }
                "new" -> if (pin.length < 4) {
                    error = "הקוד צריך לפחות 4 ספרות"
                    false
                } else {
                    error = null
                    firstPin = pin
                    step = "confirm"
                    true
                }
                else -> if (pin == firstPin) {
                    save(pin)
                    onSuccess()
                    true
                } else {
                    error = "הקודים לא תואמים, נסה שוב"
                    step = "new"
                    false
                }
            }
        }
    )
}

/** מסך הזנת קוד בסגנון אנדרואיד: נקודות ומקלדת ספרות */
@Composable
private fun PinPad(
    title: String,
    subtitle: String,
    error: String?,
    resetKey: Any,
    onCancel: () -> Unit,
    onSubmit: (String) -> Boolean
) {
    var pin by remember(resetKey) { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    var wrongCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(wrongCount) {
        if (wrongCount > 0) {
            for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x)
        }
    }

    val submit = {
        if (pin.isNotEmpty()) {
            val ok = onSubmit(pin)
            if (!ok) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                wrongCount++
            }
            pin = ""
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp)
        ) {
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                error ?: subtitle,
                fontSize = 15.sp,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))

            // נקודות
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .height(18.dp)
                    .graphicsLayer { translationX = shake.value.dp.toPx() }
            ) {
                repeat(maxOf(4, pin.length)) { i ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .then(
                                if (i < pin.length) Modifier.background(MaterialTheme.colorScheme.primary)
                                else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            )
                    )
                }
            }
            Spacer(Modifier.weight(1f))

            // מקלדת: תמיד משמאל לימין, כמו מקלדת טלפון
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
                        .forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                row.forEach { key ->
                                    val isAction = key == "⌫" || key == "✓"
                                    Box(
                                        Modifier
                                            .size(74.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    key == "✓" -> MaterialTheme.colorScheme.primaryContainer
                                                    isAction -> MaterialTheme.colorScheme.surface
                                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                                }
                                            )
                                            .clickable {
                                                when (key) {
                                                    "⌫" -> pin = pin.dropLast(1)
                                                    "✓" -> submit()
                                                    else -> if (pin.length < 8) pin += key
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            key,
                                            fontSize = if (isAction) 24.sp else 28.sp,
                                            color = if (key == "✓") MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel) { Text("ביטול") }
        }
    }
}
