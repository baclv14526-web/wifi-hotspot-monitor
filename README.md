# 📶 WiFi Hotspot Monitor

Ứng dụng Android chạy nền, tự động kiểm tra trạng thái Hotspot WiFi và gửi thông báo nhắc nhở khi bị tắt. Tối ưu cho **Oppo / Realme (ColorOS)** từ Android 9+.

## ⬇️ Tải APK

Vào tab **[Releases](../../releases)** để tải file APK mới nhất về cài đặt.

> **Lưu ý:** Cần bật "Cài đặt từ nguồn không xác định" trong Settings → Bảo mật.

---

## ✨ Tính năng

| Tính năng | Mô tả |
|-----------|-------|
| 🔄 Kiểm tra tự động | Polling theo chu kỳ 1–60 phút |
| 🔔 Thông báo tức thì | Alert ngay khi Hotspot bị tắt |
| ⚙️ Nút tắt nhanh | Mở thẳng màn hình cài đặt Hotspot |
| 🔁 Tự khởi động | Chạy lại sau khi reboot máy |
| 🔋 Tối ưu pin | Hướng dẫn tắt battery optimization |

---

## 📋 Yêu cầu

- Android **9.0 (Pie)** trở lên
- Hỗ trợ tốt nhất: **Oppo, Realme** (ColorOS 6+)
- Cũng hoạt động trên: Samsung, Xiaomi, stock Android

---

## 🚀 Hướng dẫn sử dụng

1. Cài APK từ tab Releases
2. Mở app → nhấn **"Bắt đầu giám sát"**
3. Cấp quyền **Thông báo** khi được hỏi
4. Nhấn **"Tắt tối ưu pin"** để app không bị kill (quan trọng trên Oppo/Realme)
5. Chỉnh chu kỳ kiểm tra theo nhu cầu (mặc định: 5 phút)

### ⚡ Oppo/Realme: Bước bắt buộc

ColorOS có cơ chế kill app aggressively. Làm thêm các bước sau:

1. **Settings → Battery → Battery Optimization** → Tìm "WiFi Hotspot Monitor" → Chọn "Don't optimize"
2. **Settings → App Management** → Tìm app → **Auto Launch** → Bật ON
3. **Settings → App Management** → **Run in Background** → Bật ON

---

## 🏗️ Build từ source

```bash
git clone https://github.com/YOUR_USERNAME/wifi-hotspot-monitor.git
cd wifi-hotspot-monitor
./gradlew assembleDebug
# APK ở: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions

Mỗi lần push lên `main`, GitHub Actions tự động build APK.  
Để tạo Release có APK đính kèm, tạo tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

---

## 🔧 Kiến trúc

```
app/
├── HotspotMonitorService.kt   # Foreground Service - polling loop
├── HotspotUtils.kt            # Reflection API để đọc Hotspot state
├── HotspotStateReceiver.kt    # BroadcastReceiver - WIFI_AP_STATE_CHANGED
├── BootReceiver.kt            # Tự khởi động sau reboot
└── MainActivity.kt            # UI chính
```

**Cơ chế phát hiện Hotspot** (Android 9+ không public API):
- Dùng **Java Reflection** gọi `WifiManager.isWifiApEnabled()`
- Fallback: lắng nghe broadcast `android.net.wifi.WIFI_AP_STATE_CHANGED`

---

## 📄 License

MIT License
