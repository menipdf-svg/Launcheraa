package com.meni.pixellauncher

import android.content.Context

/** צבעים מוכנים לאייקונים: רקע בהיר וסמל בגוון עמוק, כמו בסמלים הצבעוניים של פיקסל */
enum class IconColor(val label: String, val background: Int, val foreground: Int) {
    BLUE("כחול", 0xFFD3E3FD.toInt(), 0xFF0B57D0.toInt()),
    SKY("תכלת", 0xFFC2E7FF.toInt(), 0xFF00639B.toInt()),
    TEAL("טורקיז", 0xFFB8F0EC.toInt(), 0xFF00696B.toInt()),
    GREEN("ירוק", 0xFFC4EED0.toInt(), 0xFF146C2E.toInt()),
    YELLOW("צהוב", 0xFFFFE08D.toInt(), 0xFF6D5000.toInt()),
    ORANGE("כתום", 0xFFFFDBCB.toInt(), 0xFF8B3A0E.toInt()),
    RED("אדום", 0xFFFFDAD6.toInt(), 0xFFB3261E.toInt()),
    PINK("ורוד", 0xFFFFD8E4.toInt(), 0xFF8C1D51.toInt()),
    PURPLE("סגול", 0xFFEADDFF.toInt(), 0xFF6750A4.toInt()),
    GRAY("אפור", 0xFFE3E3E3.toInt(), 0xFF444746.toInt()),
    WHITE("לבן", 0xFFFFFFFF.toInt(), 0xFF1F1F1F.toInt()),
    BLACK("שחור", 0xFF1F1F1F.toInt(), 0xFFE8E8E8.toInt());

    val theme: IconTheme get() = IconTheme(background, foreground)
}

/**
 * מצב הצבע של אייקון: מקורי, לפי הטפט, או שם של אחד הצבעים ב-IconColor.
 * נשמר כמחרוזת כדי שהגדרה כללית והגדרה לאייקון בודד ישתמשו באותו פורמט.
 */
object IconColorMode {
    const val ORIGINAL = "original"
    const val WALLPAPER = "wallpaper"

    fun theme(mode: String?, wallpaper: IconTheme): IconTheme? = when (mode) {
        null, ORIGINAL -> null
        WALLPAPER -> wallpaper
        else -> runCatching { IconColor.valueOf(mode).theme }.getOrNull()
    }

    fun label(mode: String?): String = when (mode) {
        null -> "כמו כל האייקונים"
        ORIGINAL -> "צבעים מקוריים"
        WALLPAPER -> "לפי הטפט"
        else -> runCatching { IconColor.valueOf(mode).label }.getOrDefault("")
    }
}

/** צבע שנבחר לאייקון מסוים, וגובר על הצבע הכללי */
object IconColors {
    private const val PREFS = "icon_colors"

    fun all(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .toMap()

    /** null = חזרה לצבע הכללי */
    fun set(context: Context, appKey: String, mode: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (mode == null) edit.remove(appKey) else edit.putString(appKey, mode)
        edit.apply()
    }
}
