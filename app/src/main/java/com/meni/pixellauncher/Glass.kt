package com.meni.pixellauncher

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * שפת הזכוכית של מסך הבית: כרטיס המוזיקה, התיקיות וה-Dock משתמשים באותו משטח,
 * כך שהמסך נראה אחיד. הצבע הדומיננטי של הטפט צובע את הזכוכית בעדינות,
 * בדומה למנוע הצבעים של Material You.
 */

/** הצבע הדומיננטי של הטפט, או null כשהמערכת לא מספקת אותו */
val LocalGlassTint = compositionLocalOf<Color?> { null }

/** קורא את הצבע הדומיננטי של הטפט, ומתעדכן כשהטפט מתחלף. לא דורש הרשאה */
@Composable
fun rememberWallpaperAccent(): Color? {
    val context = LocalContext.current
    var accent by remember { mutableStateOf(readAccent(context)) }
    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return@DisposableEffect onDispose { }
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                accent = colors?.primaryColor?.toArgb()?.let { Color(it) }
            }
        }
        runCatching { manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { manager.removeOnColorsChangedListener(listener) } }
    }
    return accent
}

private fun readAccent(context: Context): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return runCatching {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb()?.let { Color(it) }
    }.getOrNull()
}

/**
 * זכוכית כהה: מילוי שחור שקוף למחצה, גוון עדין מצבע הטפט, ברק מלמעלה
 * ומסגרת דקה בהירה. טקסט לבן עליה עומד ביחס ניגודיות 4.5:1 גם מעל אזור בהיר בטפט.
 */
fun Modifier.glass(shape: Shape): Modifier = composed {
    val tint = LocalGlassTint.current
    Modifier
        .clip(shape)
        .background(Color.Black.copy(alpha = 0.40f))
        .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.16f)) else Modifier)
        .background(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.04f)))
        )
        .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
}
