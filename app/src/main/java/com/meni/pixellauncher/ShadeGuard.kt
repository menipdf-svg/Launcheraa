package com.meni.pixellauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * סוגר את מגירת ההתראות כשהיא נפתחת, כל עוד המצב הבטוח או הנעילה לזמן קצוב פעילים.
 *
 * זו החלופה ל-setStatusBarDisabled, שזמינה רק למנהל מכשיר. כאן נדרש אישור
 * נגישות מהמשתמש, שניתן מהגדרות המערכת ואינו מצריך מחשב.
 */
class ShadeGuardService : AccessibilityService() {

    private val prefs by lazy { getSharedPreferences("launcher_layout", Context.MODE_PRIVATE) }

    /** האם כרגע יש לחסום: המצב הבטוח פעיל או שהנעילה לזמן קצוב בתוקף */
    private fun shouldBlock(): Boolean {
        if (!prefs.getBoolean("blockShade", false)) return false
        val safeMode = prefs.getBoolean("safeMode", false)
        val lockUntil = prefs.getLong("lockUntil", 0L)
        return safeMode || lockUntil > System.currentTimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldBlock()) return
        if (event.packageName != "com.android.systemui") return
        // GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE נוסף באנדרואיד 12
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        /** האם שירות הנגישות מופעל בהגדרות המערכת */
        fun enabled(context: Context): Boolean {
            val expected = "${context.packageName}/${ShadeGuardService::class.java.name}"
            val running = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (TextUtils.isEmpty(running)) return false
            return running.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /** פותח את מסך הנגישות, שבו המשתמש מפעיל את השירות */
        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
