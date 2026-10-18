package com.meni.pixellauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** צבעים לסמלים בצבעי הטפט */
data class IconTheme(val background: Int, val foreground: Int)

/**
 * מצייר כל אייקון באותה צורה ובאותו גודל, כמו בפיקסל ו-Lawnchair:
 * - אייקון אדפטיבי נחתך לצורה שנבחרה (או לצורת המערכת)
 * - עם theme ואנדרואיד 13+: שכבת ה-monochrome נצבעת בצבעי הטפט
 * - אייקון ישן מקבל רקע לבן בצורה שנבחרה, כדי שלא ייראה קטן או עקום
 */
object IconRenderer {
    private const val SIZE = 192

    fun render(
        drawable: Drawable,
        theme: IconTheme?,
        size: Int = SIZE,
        shape: IconShape = IconShape.SYSTEM
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val icon = drawable.mutate()

        if (icon is AdaptiveIconDrawable) {
            icon.setBounds(0, 0, size, size)
            val mask = if (shape == IconShape.SYSTEM) icon.iconMask else shapePath(shape, size.toFloat())
            // השכבות של אייקון אדפטיבי גדולות ב-25% מכל צד
            val inset = size / 4

            if (theme != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val monochrome = icon.monochrome
                if (monochrome != null) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.background }
                    canvas.drawPath(mask, paint)
                    val mono = monochrome.mutate()
                    mono.setTint(theme.foreground)
                    mono.setBounds(-inset, -inset, size + inset, size + inset)
                    canvas.save()
                    canvas.clipPath(mask)
                    mono.draw(canvas)
                    canvas.restore()
                    return bitmap
                }
            }

            if (shape == IconShape.SYSTEM) {
                icon.draw(canvas)
            } else {
                canvas.save()
                canvas.clipPath(mask)
                listOfNotNull(icon.background, icon.foreground).forEach { layer ->
                    layer.setBounds(-inset, -inset, size + inset, size + inset)
                    layer.draw(canvas)
                }
                canvas.restore()
            }
            return bitmap
        }

        // אייקון ישן: רקע בצורה שנבחרה והאייקון במרכז
        val background = if (shape == IconShape.SYSTEM) shapePath(IconShape.CIRCLE, size.toFloat())
            else shapePath(shape, size.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme?.background ?: Color.WHITE }
        canvas.drawPath(background, paint)
        val inset = (size * 0.19f).toInt()
        icon.setBounds(inset, inset, size - inset, size - inset)
        icon.draw(canvas)
        return bitmap
    }

    /** אייקון מחבילת אייקונים: מצויר כמו שהוא, בלי לחתוך לצורה */
    fun renderPlain(drawable: Drawable, size: Int = SIZE): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.mutate().setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /** מסלול של הצורה בתוך ריבוע בגודל size */
    fun shapePath(shape: IconShape, size: Float): Path {
        val path = Path()
        val rect = RectF(0f, 0f, size, size)
        when (shape) {
            IconShape.SYSTEM, IconShape.CIRCLE ->
                path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            IconShape.ROUNDED ->
                path.addRoundRect(rect, size * 0.24f, size * 0.24f, Path.Direction.CW)
            IconShape.SQUARE ->
                path.addRoundRect(rect, size * 0.08f, size * 0.08f, Path.Direction.CW)
            IconShape.TEARDROP -> {
                val r = size / 2f
                val small = size * 0.12f
                path.addRoundRect(
                    rect,
                    floatArrayOf(r, r, r, r, small, small, r, r),
                    Path.Direction.CW
                )
            }
            IconShape.SQUIRCLE -> {
                // סופר-אליפסה (n=4), כמו הצורה של אייקוני iOS/One UI
                val r = size / 2f
                val steps = 120
                for (i in 0..steps) {
                    val t = 2.0 * Math.PI * i / steps
                    val c = cos(t)
                    val s = sin(t)
                    val x = r + r * sign(c) * abs(c).pow(0.5)
                    val y = r + r * sign(s) * abs(s).pow(0.5)
                    if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
                }
                path.close()
            }
        }
        return path
    }
}
