## Bước bắt buộc sau khi clone về máy

Vì `gradlew` và `gradle-wrapper.jar` không được commit vào repo,
cần generate lại trên máy local bằng 1 trong 2 cách:

### Cách 1: Dùng Android Studio (khuyến nghị)
Mở project → Android Studio sẽ tự sync và generate gradlew.

### Cách 2: Dùng Gradle CLI (nếu đã cài Gradle)
```bash
gradle wrapper --gradle-version 8.4
```
Sau đó commit các file được tạo ra:
```bash
git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
git commit -m "Add Gradle wrapper"
git push
```

### Sau khi có gradlew, CI/CD sẽ dùng:
- GitHub Actions dùng `gradle/actions/setup-gradle@v3` nên **không cần** gradlew trong repo
- Build local thì cần gradlew hoặc chạy `gradle assembleDebug` trực tiếp
