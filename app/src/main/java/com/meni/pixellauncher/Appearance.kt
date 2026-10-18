package com.meni.pixellauncher

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

/** צורות אייקונים */
enum class IconShape(val label: String) {
    SYSTEM("מערכת"),
    CIRCLE("עיגול"),
    SQUIRCLE("סקווירקל"),
    ROUNDED("ריבוע מעוגל"),
    SQUARE("ריבוע"),
    TEARDROP("טיפה")
}

/** אותה צורה, לשימוש ב-Compose (תיקיות ותצוגה מקדימה) */
fun IconShape.toComposeShape(): Shape =
    if (this == IconShape.SYSTEM) CircleShape
    else GenericShape { size, _ ->
        addPath(IconRenderer.shapePath(this@toComposeShape, size.minDimension).asComposePath())
    }

/** אפקט מעבר בין מסכי הבית */
enum class PageTransition(val label: String) {
    SLIDE("רגיל"),
    CUBE("קובייה"),
    FADE("דהייה"),
    ZOOM("זום"),
    ROTATE("סיבוב")
}

/**
 * מפעיל את אפקט המעבר על דף של ה-Pager.
 * offset: 0 = הדף במרכז, 1 = יצא שמאלה, -1 = נמצא מימין.
 */
fun Modifier.pageTransition(
    transition: PageTransition,
    pagerState: PagerState,
    page: Int
): Modifier = if (transition == PageTransition.SLIDE) this else graphicsLayer {
    val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
        .coerceIn(-1f, 1f)
    val visible = 1f - abs(offset)
    when (transition) {
        PageTransition.CUBE -> {
            cameraDistance = 12f * density
            transformOrigin = TransformOrigin(if (offset > 0f) 1f else 0f, 0.5f)
            rotationY = -90f * offset
        }
        PageTransition.FADE -> {
            alpha = visible
            val scale = 0.9f + 0.1f * visible
            scaleX = scale
            scaleY = scale
        }
        PageTransition.ZOOM -> {
            val scale = 0.8f + 0.2f * visible
            scaleX = scale
            scaleY = scale
            alpha = 0.4f + 0.6f * visible
        }
        PageTransition.ROTATE -> {
            transformOrigin = TransformOrigin(0.5f, 1.2f)
            rotationZ = -18f * offset
        }
        PageTransition.SLIDE -> Unit
    }
}
