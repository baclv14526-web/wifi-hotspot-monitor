# Keep reflection calls to WifiManager (core hotspot detection)
-keepclassmembers class android.net.wifi.WifiManager {
    public boolean isWifiApEnabled();
    public int getWifiApState();
}

# Keep app classes
-keep class com.wifimonitor.** { *; }
