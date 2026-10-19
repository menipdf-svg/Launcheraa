package com.meni.pixellauncher

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * עבודה מול Shizuku: שירות שמעניק לאפליקציה הרשאות ברמת ADB, מהטלפון עצמו,
 * בלי רוט ובלי חיבור למחשב. כך אפשר להגדיר נעילה מלאה (מנהל מכשיר) ישירות מכאן.
 *
 * Shizuku עצמו הוא אפליקציה נפרדת שיש להתקין ולהפעיל פעם אחת.
 */
object ShizukuBridge {

    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 7311

    /** מצב החיבור ל-Shizuku, לפי הסדר שבו המשתמש נתקל בו */
    enum class State {
        NOT_INSTALLED,  // האפליקציה אינה מותקנת
        NOT_RUNNING,    // מותקנת, אך השירות אינו פעיל
        NO_PERMISSION,  // השירות פעיל, אך טרם ניתנה הרשאה ללאנצ'ר
        READY           // אפשר להריץ פקודות
    }

    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun state(context: Context): State {
        if (!installed(context)) return State.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return State.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) State.READY else State.NO_PERMISSION
    }

    /** בקשת הרשאה מהמשתמש. התשובה מגיעה ל-listener שנרשם ב-MainActivity */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** תוצאת הרצה של פקודה: קוד יציאה והפלט המלא */
    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /**
     * מריץ פקודת מעטפת בהרשאות ADB.
     * newProcess אינה חלק מה-API הציבורי של Shizuku, ולכן היא נקראת ברפלקשן.
     */
    suspend fun run(command: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null)
                ?: return@runCatching Result(-1, "Shizuku לא החזיר תהליך")

            val cls = process.javaClass
            val out = (cls.getMethod("getInputStream").invoke(process) as java.io.InputStream)
                .bufferedReader().readText()
            val err = (cls.getMethod("getErrorStream").invoke(process) as java.io.InputStream)
                .bufferedReader().readText()
            val exit = cls.getMethod("waitFor").invoke(process) as Int
            Result(exit, (out + err).trim())
        }.getOrElse { Result(-1, it.message ?: "שגיאה לא ידועה") }
    }

    /** הפקודה שהופכת את הלאנצ'ר למנהל מכשיר */
    const val DEVICE_OWNER_COMMAND =
        "dpm set-device-owner com.meni.pixellauncher/.AdminReceiver"

    /**
     * מגדיר את הלאנצ'ר כמנהל מכשיר דרך Shizuku.
     * מחזיר null בהצלחה, ואחרת הודעת שגיאה בעברית.
     */
    suspend fun setDeviceOwner(): String? {
        val result = run(DEVICE_OWNER_COMMAND)
        if (result.ok && !result.output.contains("Error", ignoreCase = true)) return null
        val raw = result.output
        return when {
            raw.contains("already several users") ->
                "קיימים במכשיר פרופילים נוספים. יש למחוק את המרחב הפרטי או פרופיל עבודה, ולנסות שוב."
            raw.contains("accounts on the device") || raw.contains("Not allowed to set the device owner") ->
                "יש להסיר זמנית את כל החשבונות מהמכשיר (הגדרות ← חשבונות), ולנסות שוב."
            raw.contains("already set") || raw.contains("already provisioned") ->
                "המכשיר כבר הוגדר. ייתכן שיש לאתחל את הטלפון כדי שהשינוי ייכנס לתוקף."
            raw.isBlank() -> "הפקודה נכשלה ללא הודעה. יש לוודא ש-Shizuku פעיל."
            else -> raw
        }
    }
}
