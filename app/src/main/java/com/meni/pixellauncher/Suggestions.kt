package com.meni.pixellauncher

import android.content.Context
import org.json.JSONArray
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * הצעות חכמות: הלאנצ'ר זוכר מתי פתחת כל אפליקציה (רק במכשיר),
 * ומנחש מה תרצה לפתוח עכשיו לפי השעה, סוג היום והאם זה לאחרונה.
 */
object Suggestions {
    private const val PREFS = "usage"
    private const val MAX_EVENTS = 600

    private data class Launch(val key: String, val time: Long)

    private fun load(context: Context): MutableList<Launch> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", null)
            ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(json)
            MutableList(array.length()) { i ->
                val item = array.getJSONArray(i)
                Launch(item.getString(0), item.getLong(1))
            }
        }.getOrDefault(mutableListOf())
    }

    fun record(context: Context, key: String) {
        val log = load(context)
        log += Launch(key, System.currentTimeMillis())
        val trimmed = log.takeLast(MAX_EVENTS)
        val array = JSONArray()
        trimmed.forEach { array.put(JSONArray().put(it.key).put(it.time)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("log", array.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** האפליקציות הכי מתאימות עכשיו, מתוך allowed בלבד */
    fun suggest(context: Context, allowed: Set<String>, count: Int): List<String> {
        val now = Calendar.getInstance()
        val nowHour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f
        val nowWeekend = isWeekend(now)
        val nowMillis = now.timeInMillis
        val cal = Calendar.getInstance()

        val scores = HashMap<String, Double>()
        load(context).forEach { launch ->
            if (launch.key !in allowed) return@forEach
            cal.timeInMillis = launch.time
            val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
            // מרחק מעגלי בין השעות (23:00 קרוב ל-01:00)
            val diff = abs(hour - nowHour).let { min(it, 24 - it) }
            val timeMatch = exp(-(diff * diff) / 4.0)          // אותה שעה בערך
            val dayMatch = if (isWeekend(cal) == nowWeekend) 1.0 else 0.5
            val ageDays = (nowMillis - launch.time) / 86_400_000.0
            val recency = exp(-ageDays / 14.0)                 // השבועיים האחרונים חשובים יותר
            scores[launch.key] = (scores[launch.key] ?: 0.0) + recency * (0.3 + timeMatch) * dayMatch
        }
        return scores.entries.sortedByDescending { it.value }.take(count).map { it.key }
    }

    // סוף שבוע בישראל: שישי ושבת
    private fun isWeekend(cal: Calendar): Boolean {
        val day = cal.get(Calendar.DAY_OF_WEEK)
        return day == Calendar.FRIDAY || day == Calendar.SATURDAY
    }
}
