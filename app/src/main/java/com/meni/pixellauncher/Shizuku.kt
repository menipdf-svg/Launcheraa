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

    /**
     * אפליקציות שלעולם אינן מושעות: הלאנצ'ר עצמו, Shizuku, וחיוג חירום.
     * בלעדיהן לא ניתן יהיה לבטל את החסימה.
     */
    private val NEVER_SUSPEND = setOf(
        "com.meni.pixellauncher",
        PACKAGE,
        "com.android.settings",
        "com.android.systemui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.emergency"
    )

    /**
     * משעה או משחרר אפליקציות. אפליקציה מושעית אינה נפתחת כלל:
     * המערכת חוסמת אותה ומציגה הודעה, והאייקון שלה מופיע מעומעם.
     *
     * מחזיר null בהצלחה, ואחרת הודעת שגיאה.
     */
    suspend fun suspendApps(packages: Collection<String>, suspend: Boolean): String? {
        val targets = packages.filterNot { it in NEVER_SUSPEND }.distinct()
        if (targets.isEmpty()) return null
        val verb = if (suspend) "suspend" else "unsuspend"
        // בקבוצות, כדי לא לחרוג מאורך שורת הפקודה
        val failures = mutableListOf<String>()
        targets.chunked(40).forEach { batch ->
            val result = run("pm $verb ${batch.joinToString(" ")}")
            if (!result.ok || result.output.contains("Error", ignoreCase = true)) {
                failures += result.output.ifBlank { "pm $verb נכשלה" }
            }
        }
        if (failures.isEmpty()) return null
        return failures.first().take(200)
    }

    /**
     * מה שחוסם את הגדרת מנהל המכשיר: חשבונות שיש להסיר, ופרופילים נוספים
     * (מרחב פרטי, פרופיל עבודה, משתמש שני) שיש למחוק.
     */
    data class Blockers(
        val accounts: List<String>,
        val profiles: List<String>,
        val checked: Boolean
    ) {
        val clear: Boolean get() = checked && accounts.isEmpty() && profiles.isEmpty()
    }

    private val ACCOUNT_LINE = Regex("""Account \{name=([^,]+), type=([^}]+)\}""")
    private val USER_LINE = Regex("""UserInfo\{(\d+):([^:]*):""")

    /** שמות קריאים לסוגי החשבונות הנפוצים */
    private fun accountTypeLabel(type: String): String = when {
        type.startsWith("com.google") -> "Google"
        type.contains("samsung", ignoreCase = true) -> "Samsung"
        type.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
        type.contains("telegram", ignoreCase = true) -> "Telegram"
        type.contains("exchange", ignoreCase = true) -> "Exchange"
        type.contains("microsoft", ignoreCase = true) || type.contains("outlook", ignoreCase = true) -> "Microsoft"
        else -> type.substringAfterLast('.').ifBlank { type }
    }

    /** קורא מהמכשיר אילו חשבונות ופרופילים מונעים את ההגדרה */
    suspend fun blockers(): Blockers {
        val accounts = mutableListOf<String>()
        val profiles = mutableListOf<String>()

        val dump = run("dumpsys account")
        if (dump.ok) {
            ACCOUNT_LINE.findAll(dump.output).forEach { match ->
                val (name, type) = match.destructured
                val label = "${accountTypeLabel(type.trim())} · ${name.trim()}"
                if (label !in accounts) accounts += label
            }
        }

        val users = run("pm list users")
        if (users.ok) {
            USER_LINE.findAll(users.output).forEach { match ->
                val id = match.groupValues[1]
                val name = match.groupValues[2].trim()
                // משתמש 0 הוא הבעלים, ואותו אין מוחקים
                if (id != "0") profiles += if (name.isEmpty()) "פרופיל $id" else name
            }
        }

        return Blockers(accounts, profiles, checked = dump.ok || users.ok)
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
