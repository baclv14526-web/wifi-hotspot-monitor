package com.wifimonitor

import android.app.AlarmManager
import android.os.Build

/**
 * Cô lập các lời gọi API chỉ tồn tại từ Android 12 (API 31) trở lên.
 *
 * LÝ DO CẦN FILE NÀY (rất quan trọng, tránh crash trên Android 9 ColorOS):
 * Nếu gọi trực tiếp `AlarmManager.canScheduleExactAlarms()` bên trong 1 hàm
 * của class khác (dù có bọc `if (Build.VERSION.SDK_INT >= 31)`), một số ART
 * runtime cũ/OEM (đặc biệt ColorOS trên Oppo/Realme Android 9) vẫn có thể
 * ném NoSuchMethodError hoặc VerifyError NGAY KHI class chứa hàm đó được nạp
 * — vì trình verify của ART kiểm tra toàn bộ method (mọi symbol được tham
 * chiếu), không chỉ nhánh code thực sự sẽ chạy ở runtime.
 *
 * Cách khắc phục chuẩn: tách lời gọi API mới ra một object/class RIÊNG.
 * Android chỉ nạp (và verify) 1 class khi nó thực sự bị tham chiếu tới lần
 * đầu. Vì object này chỉ được gọi bên trong nhánh `SDK_INT >= S`, trên máy
 * Android 9 nó không bao giờ bị tham chiếu → không bao giờ bị nạp/verify →
 * không thể gây crash, kể cả khi chứa symbol không tồn tại trên Android 9.
 */
object Api31Compat {
    fun canScheduleExactAlarms(am: AlarmManager): Boolean {
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Throwable) {
            // Cực kỳ hiếm khi xảy ra, nhưng không bao giờ để crash vì việc này
            false
        }
    }
}
