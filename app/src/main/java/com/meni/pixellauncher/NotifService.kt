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
    val controller: MediaController
)

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
            controller = controller
        )
        main.post { nowPlaying.value = value }
    }
}
