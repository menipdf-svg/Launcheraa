package com.meni.pixellauncher

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.concurrent.ConcurrentHashMap

data class IconPack(val packageName: String, val label: String)

/**
 * תמיכה בחבילות אייקונים בפורמט הסטנדרטי (Nova, ADW, Lawnchair):
 * החבילה מכילה appfilter.xml שממפה כל אפליקציה לציור משלה.
 */
object IconPacks {
    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "org.adw.launcher.icons.ACTION_PICK_ICON"
    )

    // חבילה ← (אפליקציה/פעילות ← שם הציור)
    private val maps = ConcurrentHashMap<String, Map<String, String>>()

    fun installed(context: Context): List<IconPack> {
        val pm = context.packageManager
        return ACTIONS.flatMap { action ->
            runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList())
        }
            .map { it.activityInfo.packageName }
            .distinct()
            .map { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                IconPack(pkg, label)
            }
            .sortedWith(compareBy<IconPack, String>(AlphabeticalOrder.collator) { it.label })
    }

    /** מחזיר את האייקון מהחבילה, או null אם אין בה ציור לאפליקציה הזו */
    fun iconFor(context: Context, packName: String, appPackage: String, activity: String): Drawable? {
        val res = runCatching { context.packageManager.getResourcesForApplication(packName) }.getOrNull()
            ?: return null
        val map = maps.getOrPut(packName) { parse(res, packName) }
        val name = map["$appPackage/$activity"] ?: map["$appPackage/"] ?: return null
        val id = res.getIdentifier(name, "drawable", packName).takeIf { it != 0 } ?: return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
    }

    fun clear() = maps.clear()

    private fun parse(res: Resources, packName: String): Map<String, String> {
        val result = HashMap<String, String>()
        val parser: XmlPullParser = run {
            val id = res.getIdentifier("appfilter", "xml", packName)
            if (id != 0) {
                res.getXml(id)
            } else {
                val stream = runCatching { res.assets.open("appfilter.xml") }.getOrNull() ?: return result
                XmlPullParserFactory.newInstance().newPullParser().apply { setInput(stream, "UTF-8") }
            }
        }
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (component != null && drawable != null) {
                        normalize(component)?.let { result.putIfAbsent(it, drawable) }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** "ComponentInfo{com.app/.Main}" ← "com.app/com.app.Main" */
    private fun normalize(raw: String): String? {
        val inner = raw.substringAfter("{", "").substringBefore("}", "")
        if (!inner.contains('/')) return null
        val pkg = inner.substringBefore('/')
        var activity = inner.substringAfter('/')
        if (activity.startsWith(".")) activity = pkg + activity
        return "$pkg/$activity"
    }
}
