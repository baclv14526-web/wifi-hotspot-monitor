package com.wifimonitor

import android.content.Context
import android.net.wifi.WifiManager

object HotspotUtils {

    fun isEnabled(context: Context): Boolean {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wm) as Boolean
        } catch (e: Exception) {
            false
        }
    }
}
