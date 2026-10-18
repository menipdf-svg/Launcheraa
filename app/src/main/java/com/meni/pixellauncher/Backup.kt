package com.meni.pixellauncher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * גיבוי ושחזור של הסידור וההגדרות לקובץ JSON אחד.
 * לא נשמרים: קודים, נעילת טביעת אצבע ומצב בטוח (כדי ששחזור לא יגרום להינעל בחוץ),
 * ווידג'טים (המספרים שלהם לא עוברים בין התקנות).
 */
object Backup {
    private val FILES = listOf("launcher_layout", "category_overrides", "custom_folders", "custom_labels", "usage")
    private val SECRET_KEYS = setOf(
        "pinHash", "pinSalt", "emergencyHash", "emergencySalt", "biometricLock", "safeMode", "safeApps",
        "lockUntil", "lockStrict", "lockApps"
    )

    fun export(context: Context, uri: Uri): Boolean = runCatching {
        val root = JSONObject().put("app", "PixelLauncher").put("version", 1)
        val files = JSONObject()
        FILES.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (key in SECRET_KEYS) return@forEach
                val entry = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                    else -> null
                }
                if (entry != null) values.put(key, entry)
            }
            files.put(name, values)
        }
        root.put("prefs", files)
        context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) }
            ?: error("no stream")
        true
    }.getOrDefault(false)

    fun import(context: Context, uri: Uri): Boolean = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("no stream")
        val root = JSONObject(text)
        require(root.optString("app") == "PixelLauncher")
        val files = root.getJSONObject("prefs")
        FILES.forEach { name ->
            val values = files.optJSONObject(name) ?: return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            // מנקים הכל חוץ מהמפתחות הסודיים, שנשארים כמו שהם במכשיר
            prefs.all.keys.filter { it !in SECRET_KEYS }.forEach { edit.remove(it) }
            values.keys().forEach { key ->
                if (key in SECRET_KEYS) return@forEach
                val entry = values.getJSONObject(key)
                when (entry.getString("t")) {
                    "b" -> edit.putBoolean(key, entry.getBoolean("v"))
                    "i" -> edit.putInt(key, entry.getInt("v"))
                    "l" -> edit.putLong(key, entry.getLong("v"))
                    "f" -> edit.putFloat(key, entry.getDouble("v").toFloat())
                    "s" -> edit.putString(key, entry.getString("v"))
                    "set" -> {
                        val array = entry.getJSONArray("v")
                        edit.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                    }
                }
            }
            edit.commit()
        }
        true
    }.getOrDefault(false)
}
