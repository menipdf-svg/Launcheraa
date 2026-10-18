package com.meni.pixellauncher

import android.app.admin.DeviceAdminReceiver

/**
 * מאפשר ללאנצ'ר להיות "מנהל מכשיר" (Device Owner), וכך לנעול את הטלפון
 * רק לאפליקציות שאושרו (Lock Task). מופעל פעם אחת דרך adb:
 * adb shell dpm set-device-owner com.meni.pixellauncher/.AdminReceiver
 */
class AdminReceiver : DeviceAdminReceiver()
