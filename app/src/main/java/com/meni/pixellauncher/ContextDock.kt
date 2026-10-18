package com.meni.pixellauncher

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/**
 * האם מחוברות עכשיו אוזניות (חוטיות, בלוטות', USB) או מערכת שמע של רכב.
 * מתעדכן מיד כשמחברים או מנתקים.
 */
@Composable
fun rememberHeadphonesConnected(enabled: Boolean): Boolean {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(false) }
    if (!enabled) return false

    DisposableEffect(Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        fun check() {
            val types = buildSet {
                add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
                add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
                add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                add(AudioDeviceInfo.TYPE_USB_HEADSET)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                    add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
                }
            }
            connected = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { it.type in types } == true
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = check()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = check()
        }
        audio?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        check()
        onDispose { audio?.unregisterAudioDeviceCallback(callback) }
    }
    return connected
}
