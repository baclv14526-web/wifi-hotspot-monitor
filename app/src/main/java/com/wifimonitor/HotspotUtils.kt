package com.wifimonitor

import android.content.Context
import android.net.wifi.WifiManager
import java.lang.reflect.Method

/**
 * Utility để kiểm tra trạng thái Hotspot/Tethering
 * Hỗ trợ Android 9+ bao gồm Oppo/Realme ColorOS
 */
object HotspotUtils {

    /**
     * Kiểm tra hotspot có đang bật không
     * Android 9+ không cho phép đọc trực tiếp, dùng reflection
     */
    fun isHotspotEnabled(context: Context): Boolean {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Method 1: Dùng reflection - hoạt động trên hầu hết ROM
            val method: Method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            // Method 2: Fallback - kiểm tra qua WifiManager state
            isHotspotEnabledFallback(context)
        }
    }

    private fun isHotspotEnabledFallback(context: Context): Boolean {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Thử lấy AP state qua getDeclaredField
            val apStateField = wifiManager.javaClass.getDeclaredField("mWifiApState")
            apStateField.isAccessible = true
            val apState = apStateField.getInt(wifiManager)
            // WIFI_AP_STATE_ENABLED = 13
            apState == 13
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Lấy WifiApState dạng số
     * 10 = DISABLING, 11 = DISABLED, 12 = ENABLING, 13 = ENABLED, 14 = FAILED
     */
    fun getHotspotState(context: Context): Int {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method: Method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
            method.isAccessible = true
            method.invoke(wifiManager) as Int
        } catch (_: Exception) {
            WIFI_AP_STATE_UNKNOWN
        }
    }

    const val WIFI_AP_STATE_DISABLING = 10
    const val WIFI_AP_STATE_DISABLED  = 11
    const val WIFI_AP_STATE_ENABLING  = 12
    const val WIFI_AP_STATE_ENABLED   = 13
    const val WIFI_AP_STATE_FAILED    = 14
    const val WIFI_AP_STATE_UNKNOWN   = -1
}
