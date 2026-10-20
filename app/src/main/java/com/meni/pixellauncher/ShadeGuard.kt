package com.meni.pixellauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        instance = null
        return super.onUnbind(intent)
    }

    /** האם כרגע יש לחסום: המצב הבטוח פעיל או שהנעילה לזמן קצוב בתוקף */
    private fun shouldBlock(): Boolean {
        if (!prefs.getBoolean("blockShade", false)) return false
        val safeMode = prefs.getBoolean("safeMode", false)
        val lockUntil = prefs.getLong("lockUntil", 0L)
        return safeMode || lockUntil > System.currentTimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldBlock()) return
        if (!shadeIsOpen()) return
        dismissRepeatedly()
    }

    /**
     * זיהוי המגירה לפי החלונות הפעילים: חלון מסוג TYPE_SYSTEM שאינו שורת הסטטוס
     * מעיד על מגירה פתוחה. כך אין צורך בקריאת תוכן המסך.
     */
    private fun shadeIsOpen(): Boolean = runCatching {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }
    }.getOrDefault(true) // אם לא ניתן לבדוק, מוטב לנסות לסגור

    /**
     * המערכת מתעלמת לעיתים מקריאה בודדת, בעיקר בעת האנימציה של הפתיחה,
     * ולכן הפעולה חוזרת כמה פעמים ברווחים קצרים.
     */
    private fun dismissRepeatedly() {
        repeat(4) { attempt ->
            handler.postDelayed({ if (shouldBlock()) dismissOnce() }, attempt * 120L)
        }
    }

    private fun dismissOnce() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** האם השירות מחובר כרגע ומקבל אירועים */
        @Volatile
        var connected = false
            private set

        @Volatile
        private var instance: ShadeGuardService? = null

        /**
         * מכבה את המסך. זמין רק דרך שירות נגישות, ולכן נדרש אותו אישור
         * שמשמש את חסימת מגירת ההתראות. מחזיר false אם השירות אינו פעיל.
         */
        fun lockScreen(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return runCatching {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }.getOrDefault(false)
        }

        /** האם שירות הנגישות מופעל בהגדרות המערכת */
        fun enabled(context: Context): Boolean {
            val expected = "${context.packageName}/${ShadeGuardService::class.java.name}"
            val short = "${context.packageName}/.${ShadeGuardService::class.java.simpleName}"
            val running = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (TextUtils.isEmpty(running)) return false
            return running.split(':').any {
                it.equals(expected, ignoreCase = true) || it.equals(short, ignoreCase = true)
            }
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
