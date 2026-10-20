package com.meni.pixellauncher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** מה שמסך הלאנצ'ר צריך מה-Activity */
interface LauncherHost {
    val store: LauncherStore
    fun widgetView(widgetId: Int): AppWidgetHostView?
    fun isWidgetValid(widgetId: Int): Boolean
    fun requestAddWidget(info: AppWidgetProviderInfo, onAdded: (Int) -> Unit)
    fun deleteWidget(widgetId: Int)

    /** מצב טביעת האצבע במכשיר (BiometricManager.BIOMETRIC_SUCCESS וכו') */
    fun biometricStatus(): Int
    /**
     * טביעת אצבע. fallbackLabel/onFallback: כפתור חלופי (למשל קוד חירום),
     * שמופעל גם כשהחיישן נעול או לא זמין.
     */
    fun authenticate(
        title: String,
        subtitle: String,
        fallbackLabel: String? = null,
        onFallback: (() -> Unit)? = null,
        onSuccess: () -> Unit
    )

    // ---------- נעילה מלאה (מצב קיוסק) ----------
    fun isDeviceOwner(): Boolean
    /** נכנס/יוצא מנעילה מלאה. packages = האפליקציות שמותר לפתוח */
    fun setKiosk(enabled: Boolean, packages: List<String>)
    fun removeDeviceOwner()

    // ---------- גיבוי וטשטוש ----------
    fun exportBackup()
    fun importBackup()
    /** טשטוש הטפט שמאחורי הלאנצ'ר (אנדרואיד 12 ומעלה). 0 = בלי */
    fun setBackgroundBlur(radiusPx: Int)
    /** אייקונים כהים בשורת הסטטוס כשמוצג מעליה משטח בהיר (מגירה, הגדרות) */
    fun setLightSystemBars(light: Boolean)
    /** האם מסך הבית מסתובב עם המכשיר, או נשאר לאורך */
    fun setRotationAllowed(allowed: Boolean)
}

// FragmentActivity נדרש בשביל חלון טביעת האצבע של המערכת
class MainActivity : FragmentActivity(), LauncherHost {

    companion object {
        private const val WIDGET_HOST_ID = 1024
        private const val REQUEST_CONFIGURE_WIDGET = 2001
    }

    override val store: LauncherStore by lazy { LauncherStore(this) }

    private lateinit var widgetManager: AppWidgetManager
    private lateinit var widgetHost: LauncherWidgetHost
    private val widgetViews = mutableMapOf<Int, AppWidgetHostView>()

    // ווידג'ט שנמצא באמצע הוספה (אישור הרשאה / מסך הגדרות)
    private var pendingWidgetId = -1
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null
    private var pendingOnAdded: ((Int) -> Unit)? = null

    // ---------- גיבוי ושחזור ----------

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                val ok = Backup.export(this, uri)
                android.widget.Toast.makeText(this, if (ok) "הגיבוי נשמר" else "שמירת הגיבוי נכשלה", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                if (Backup.import(this, uri)) {
                    android.widget.Toast.makeText(this, "השחזור הושלם", android.widget.Toast.LENGTH_SHORT).show()
                    recreate() // טוען מחדש את כל ההגדרות
                } else {
                    android.widget.Toast.makeText(this, "הקובץ אינו תקין", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun exportBackup() {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        runCatching { exportLauncher.launch("PixelLauncher-$date.json") }
    }

    override fun importBackup() {
        runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
    }

    override fun setLightSystemBars(light: Boolean) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    override fun setRotationAllowed(allowed: Boolean) {
        requestedOrientation = if (allowed) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun setBackgroundBlur(radiusPx: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val wm = getSystemService(android.view.WindowManager::class.java)
        if (wm?.isCrossWindowBlurEnabled != true) return
        val attrs = window.attributes
        if (attrs.blurBehindRadius == radiusPx) return
        if (radiusPx > 0) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        attrs.blurBehindRadius = radiusPx
        window.attributes = attrs
    }

    // אפליקציה הותקנה, הוסרה או התעדכנה: טוענים מחדש
    private val packageCallback = object : android.content.pm.LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: android.os.UserHandle?) { refreshKey++ }
        override fun onPackageAdded(packageName: String?, user: android.os.UserHandle?) { refreshKey++ }
        override fun onPackageChanged(packageName: String?, user: android.os.UserHandle?) { refreshKey++ }
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: android.os.UserHandle?, replacing: Boolean) { refreshKey++ }
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: android.os.UserHandle?, replacing: Boolean) { refreshKey++ }
    }

    override fun onDestroy() {
        runCatching { getSystemService(android.content.pm.LauncherApps::class.java)?.unregisterCallback(packageCallback) }
        super.onDestroy()
    }

    private val bindWidgetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val info = pendingWidgetInfo
            if (result.resultCode == RESULT_OK && info != null) {
                configureOrFinish(pendingWidgetId, info)
            } else {
                cancelPendingWidget()
            }
        }

    // עולה בכל חזרה ללאנצ'ר כדי לרענן את רשימת האפליקציות
    private var refreshKey by mutableIntStateOf(0)

    // עולה כשלוחצים Home כשהלאנצ'ר כבר פתוח
    private var homePressed by mutableIntStateOf(0)

    // עולה בכל פעם שהלאנצ'ר יורד לרקע (מסך כבוי, אפליקציה אחרת)
    private var stopCount by mutableIntStateOf(0)

    // עולה בכל לחיצה כפולה על ווליום עליון
    private var volumeDoublePress by mutableIntStateOf(0)
    private val keyHandler = Handler(Looper.getMainLooper())
    private var volumePending = false
    private val singleVolumePress = Runnable {
        // לא הגיעה לחיצה שנייה: מגבירים את הווליום כרגיל
        volumePending = false
        raiseVolume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // הכיוון נקבע עוד לפני הציור הראשון, כדי שלא יהיה הבזק של פריסה מסובבת
        setRotationAllowed(
            getSharedPreferences("launcher_layout", Context.MODE_PRIVATE)
                .getBoolean("rotateHome", false)
        )
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = LauncherWidgetHost(applicationContext, WIDGET_HOST_ID)
        runCatching {
            getSystemService(android.content.pm.LauncherApps::class.java)
                ?.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(colorScheme = launcherColors()) {
                LauncherScreen(
                    host = this@MainActivity,
                    refreshKey = refreshKey,
                    homePressed = homePressed,
                    volumeDoublePress = volumeDoublePress,
                    stopCount = stopCount
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        super.onStop()
        stopCount++
        try {
            widgetHost.stopListening()
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
        // אם הנעילה המלאה צריכה להיות פעילה (למשל אחרי הפעלה מחדש), מחזירים אותה
        if (kioskWanted) applyKiosk()
    }

    // ---------- נעילה מלאה (מצב קיוסק) ----------

    private var kioskWanted = false
    private var kioskPackages: List<String> = emptyList()

    private val devicePolicy by lazy { getSystemService(android.app.admin.DevicePolicyManager::class.java) }
    private val adminComponent by lazy { android.content.ComponentName(this, AdminReceiver::class.java) }

    override fun isDeviceOwner(): Boolean =
        devicePolicy?.isDeviceOwnerApp(packageName) == true

    override fun setKiosk(enabled: Boolean, packages: List<String>) {
        kioskWanted = enabled && isDeviceOwner()
        kioskPackages = packages
        if (kioskWanted) {
            applyKiosk()
        } else if (isInLockTask()) {
            runCatching { stopLockTask() }
        }
    }

    private fun applyKiosk() {
        val dpm = devicePolicy ?: return
        if (!isDeviceOwner()) return
        try {
            dpm.setLockTaskPackages(adminComponent, (kioskPackages + packageName).distinct().toTypedArray())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                // מותר: כפתור הבית, תפריט כיבוי, שעון וסוללה, ומסך הנעילה של המכשיר.
                // חסום: התראות, הגדרות מהירות ואפליקציות אחרונות.
                dpm.setLockTaskFeatures(
                    adminComponent,
                    android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                        android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
                )
            }
            if (!isInLockTask()) startLockTask()
        } catch (e: Exception) {
            android.util.Log.w("PixelLauncher", "lock task failed", e)
        }
    }

    private fun isInLockTask(): Boolean =
        getSystemService(android.app.ActivityManager::class.java)?.lockTaskModeState !=
            android.app.ActivityManager.LOCK_TASK_MODE_NONE

    override fun removeDeviceOwner() {
        kioskWanted = false
        if (isInLockTask()) runCatching { stopLockTask() }
        runCatching {
            devicePolicy?.setLockTaskPackages(adminComponent, emptyArray())
            @Suppress("DEPRECATION")
            devicePolicy?.clearDeviceOwnerApp(packageName)
        }
    }

    /**
     * לחיצה כפולה על ווליום עליון (רק כשהלאנצ'ר פתוח).
     * לחיצה אחת מגבירה את הווליום כרגיל, אחרי השהיה קצרה.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val enabled = store.gestureActions[LauncherGesture.VOLUME_UP_TWICE] != GestureAction.NONE
        if (!enabled || event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            when {
                // החזקה ארוכה: מגבירים כרגיל
                event.repeatCount > 0 -> {
                    keyHandler.removeCallbacks(singleVolumePress)
                    volumePending = false
                    raiseVolume()
                }
                volumePending -> {
                    keyHandler.removeCallbacks(singleVolumePress)
                    volumePending = false
                    volumeDoublePress++
                }
                else -> {
                    volumePending = true
                    keyHandler.postDelayed(singleVolumePress, 350)
                }
            }
        }
        return true
    }

    // ---------- טביעת אצבע ----------

    override fun biometricStatus(): Int =
        BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

    override fun authenticate(
        title: String,
        subtitle: String,
        fallbackLabel: String?,
        onFallback: (() -> Unit)?,
        onSuccess: () -> Unit
    ) {
        // החיישן לא זמין בכלל: עוברים ישר לחלופה, אם יש
        if (onFallback != null && biometricStatus() != BiometricManager.BIOMETRIC_SUCCESS) {
            onFallback()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // כפתור החלופה, או חיישן נעול אחרי ניסיונות: עוברים לקוד החירום
                    if (onFallback != null && errorCode in listOf(
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE,
                            BiometricPrompt.ERROR_NO_BIOMETRICS
                        )
                    ) {
                        onFallback()
                        return
                    }
                    // ביטול על ידי המשתמש לא מציג הודעה
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        android.widget.Toast.makeText(this@MainActivity, errString, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(if (onFallback != null) fallbackLabel ?: "קוד חירום" else "ביטול")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info)
    }

    private fun raiseVolume() {
        getSystemService(AudioManager::class.java)?.adjustSuggestedStreamVolume(
            AudioManager.ADJUST_RAISE,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePressed++
    }

    // ---------- ווידג'טים ----------

    override fun widgetView(widgetId: Int): AppWidgetHostView? {
        widgetViews[widgetId]?.let { return it }
        val info = widgetManager.getAppWidgetInfo(widgetId) ?: return null
        return widgetHost.createView(this, widgetId, info).also { widgetViews[widgetId] = it }
    }

    override fun isWidgetValid(widgetId: Int): Boolean =
        widgetManager.getAppWidgetInfo(widgetId) != null

    override fun requestAddWidget(info: AppWidgetProviderInfo, onAdded: (Int) -> Unit) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetInfo = info
        pendingOnAdded = onAdded

        val allowed = try {
            widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (_: Exception) {
            false
        }
        if (allowed) {
            configureOrFinish(id, info)
        } else {
            // מבקשים מהמשתמש אישור לאפשר ללאנצ'ר להציג ווידג'טים
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            }
            try {
                bindWidgetLauncher.launch(intent)
            } catch (_: Exception) {
                cancelPendingWidget()
            }
        }
    }

    override fun deleteWidget(widgetId: Int) {
        widgetViews.remove(widgetId)
        try {
            widgetHost.deleteAppWidgetId(widgetId)
        } catch (_: Exception) {
        }
    }

    private fun configureOrFinish(id: Int, info: AppWidgetProviderInfo) {
        val configurationOptional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure != null && !configurationOptional) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(
                    this, id, 0, REQUEST_CONFIGURE_WIDGET, null
                )
                return
            } catch (_: Exception) {
            }
        }
        finishPendingWidget()
    }

    private fun finishPendingWidget() {
        val id = pendingWidgetId
        val callback = pendingOnAdded
        clearPending()
        if (id != -1) callback?.invoke(id)
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) {
            try {
                widgetHost.deleteAppWidgetId(pendingWidgetId)
            } catch (_: Exception) {
            }
        }
        clearPending()
    }

    private fun clearPending() {
        pendingWidgetId = -1
        pendingWidgetInfo = null
        pendingOnAdded = null
    }

    @Deprecated("Needed for the widget configure screen result")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIGURE_WIDGET) {
            if (resultCode == RESULT_OK) finishPendingWidget() else cancelPendingWidget()
        }
    }
}

@Composable
private fun launcherColors(): ColorScheme {
    val context = LocalContext.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context) // צבעי Material You לפי הטפט
    } else {
        lightColorScheme()
    }
}
