package com.meni.pixellauncher

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat

/** מה מתנגן עכשיו */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val duration: Long,
    val controller: MediaController,
    /** פעולת "אהבתי" של אפליקציית המוזיקה (למשל הוספה לשירים שאהבת בספוטיפיי), אם יש */
    val likeAction: PlaybackState.CustomAction? = null,
    /** הסמל שהאפליקציה עצמה נותנת לפעולה: לב ריק או מלא לפי המצב */
    val likeIcon: Bitmap? = null,
    /** חלופה: דירוג לב סטנדרטי. null = האפליקציה אינה תומכת */
    val heart: Boolean? = null
) {
    /** המיקום בשיר ברגע זה, מחושב מהזמן שעבר מאז העדכון האחרון */
    fun positionNow(): Long {
        val state = controller.playbackState ?: return 0L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val elapsed = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return (state.position + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0L)
    }
}

/**
 * מאזין להתראות (באישור המשתמש), בשביל:
 * 1. נקודות התראה על האייקונים: כמה התראות יש לכל אפליקציה
 * 2. "מתנגן עכשיו": המדיה שפעילה כרגע, עם שליטה
 */
class NotifService : NotificationListenerService() {

    companion object {
        /** חבילה ← מספר התראות */
        val counts = mutableStateMapOf<String, Int>()
        val nowPlaying = mutableStateOf<NowPlaying?>(null)

        private val main = Handler(Looper.getMainLooper())

        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    private var sessionManager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateNowPlaying()
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateNowPlaying()
        override fun onSessionDestroyed() = refreshSessions()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refreshSessions() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        rebuildCounts()
        sessionManager = getSystemService(MediaSessionManager::class.java)
        runCatching {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, ComponentName(this, NotifService::class.java))
        }
        refreshSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = emptyList()
        main.post {
            counts.clear()
            nowPlaying.value = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = rebuildCounts()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = rebuildCounts()

    private fun rebuildCounts() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val result = HashMap<String, Int>()
        active.forEach { sbn ->
            val n = sbn.notification
            // בלי התראות קבועות (מוזיקה, הורדות) ובלי סיכומי קבוצה
            val summary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            if (!sbn.isOngoing && !summary) result[sbn.packageName] = (result[sbn.packageName] ?: 0) + 1
        }
        main.post {
            counts.keys.retainAll(result.keys)
            counts.putAll(result)
        }
    }

    private fun refreshSessions() {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = runCatching {
            sessionManager?.getActiveSessions(ComponentName(this, NotifService::class.java))
        }.getOrNull().orEmpty()
        controllers.forEach { it.registerCallback(controllerCallback, main) }
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        // עדיפות למה שמתנגן, אחרת המדיה האחרונה שיש לה שם
        val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        val metadata: MediaMetadata? = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (controller == null || metadata == null || title.isNullOrBlank()) {
            main.post { nowPlaying.value = null }
            return
        }
        val value = NowPlaying(
            packageName = controller.packageName,
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            controller = controller,
            likeAction = findLikeAction(controller.playbackState),
            likeIcon = findLikeAction(controller.playbackState)?.let { likeIconFor(controller.packageName, it) },
            heart = heartState(controller, metadata)
        )
        main.post { nowPlaying.value = value }
    }

    /**
     * אפליקציות מוזיקה חושפות "אהבתי" כפעולה מותאמת, בשמות שונים:
     * ספוטיפיי (הוספה לאוסף), YouTube Music (אגודל למעלה), ואחרות (לב, מועדפים).
     */
    private fun findLikeAction(state: PlaybackState?): PlaybackState.CustomAction? {
        val actions = state?.customActions.orEmpty()
        val wanted = listOf(
            "like", "heart", "favorite", "favourite", "collection", "library", "save",
            "thumbs_up", "thumb_up", "thumbsup", "love", "add_to", "אהבת", "מועדפ", "שמירה"
        )
        val unwanted = listOf("dislike", "thumbs_down", "thumb_down", "shuffle", "repeat")
        return actions.firstOrNull { action ->
            val text = (action.action + " " + action.name).lowercase()
            wanted.any { it in text } && unwanted.none { it in text }
        }
    }

    private fun likeIconFor(packageName: String, action: PlaybackState.CustomAction): Bitmap? = runCatching {
        val resources = packageManager.getResourcesForApplication(packageName)
        val drawable = resources.getDrawable(action.icon, null) ?: return null
        val size = 72
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()

    private fun heartState(controller: MediaController, metadata: MediaMetadata): Boolean? {
        val canRate = (controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SET_RATING != 0L
        if (!canRate || controller.ratingType != android.media.Rating.RATING_HEART) return null
        return metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.hasHeart() == true
    }
}
