-keep class com.wifimonitor.** { *; }
-keepclassmembers class android.net.wifi.WifiManager {
    public boolean isWifiApEnabled();
}
