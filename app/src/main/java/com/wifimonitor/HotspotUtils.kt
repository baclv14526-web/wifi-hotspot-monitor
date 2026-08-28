package com.wifimonitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotUtils {

    /**
     * Kiểm tra xem WiFi Hotspot (Điểm phát sóng WiFi) có đang bật hay không.
     * Sử dụng phương pháp kiểm tra đa tầng (multi-tier fallback) để hoạt động
     * ổn định trên tất cả các dòng máy Android từ Android 9 đến Android 14+.
     */
    fun isEnabled(context: Context): Boolean {
        // Cách 1: Kiểm tra qua getWifiApState reflection (chuẩn của WifiManager)
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("getWifiApState")
            method.isAccessible = true
            val state = method.invoke(wm) as Int
            // WIFI_AP_STATE_ENABLING = 12, WIFI_AP_STATE_ENABLED = 13
            if (state == 13 || state == 12) {
                return true
            } else if (state in 10..14) {
                // Đã đọc được state hợp lệ từ WifiManager (10 = Disabling, 11 = Disabled, 14 = Failed)
                return false
            }
        } catch (e: Exception) {
            // Tiếp tục fallback sang cách 2
        }

        // Cách 2: Kiểm tra qua isWifiApEnabled reflection
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            val isApEnabled = method.invoke(wm) as? Boolean
            if (isApEnabled != null) {
                return isApEnabled
            }
        } catch (e: Exception) {
            // Tiếp tục fallback sang cách 3
        }

        // Cách 3: Kiểm tra trực tiếp các NetworkInterface phần cứng
        // Khi Hotspot bật, hệ thống tạo interface mạng AP (ap0, wlan0, wlan1, softap0, swlan0, rndis0...)
        // đang hoạt động (isUp) và có gán địa chỉ IP (thường là 192.168.43.1 hoặc tương đương).
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val isHotspotInterface = name.startsWith("ap") ||
                        name.startsWith("softap") ||
                        name.startsWith("swlan") ||
                        name.contains("tether") ||
                        name.startsWith("rndis") ||
                        name.startsWith("wlan")

                if (isHotspotInterface) {
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: ""
                            // 192.168.43.x là dải IP hotspot chuẩn của Android
                            if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.") || name.startsWith("ap") || name.startsWith("softap")) {
                                return true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Tiếp tục fallback
        }

        // Cách 4: Kiểm tra qua ConnectivityManager getTetheredIfaces
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val method = cm.javaClass.getDeclaredMethod("getTetheredIfaces")
            method.isAccessible = true
            val tethered = method.invoke(cm) as? Array<*>
            if (tethered != null && tethered.isNotEmpty()) {
                return true
            }
        } catch (e: Exception) {
            // Fallback cuối cùng
        }

        return false
    }
}
