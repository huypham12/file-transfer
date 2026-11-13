# =========================================================================
# QUY TẮC BẢO VỆ VÀ XÁO TRỘN MÃ NGUỒN PROGUARD / R8 (STAGE 8 RELEASE)
# HỆ THỐNG CỘNG TÁC NHÓM VÀ CHIA SẺ TÀI LIỆU AN TOÀN filetransfer
# =========================================================================

# 1. Bảo vệ Toàn vẹn CSDL Room & Thư viện Native SQLCipher
-keep class androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class * extends androidx.room.RoomOpenHelper
-keep class com.filetransfer.app.data.local.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# 2. Bảo vệ Google Tink & AndroidX Security Crypto (MasterKey & EncryptedSharedPreferences)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# 3. Bảo vệ Presentation ViewModels & Dependency Injection Factory
-keep class com.filetransfer.app.presentation.viewmodel.** { *; }
-keep class com.filetransfer.app.presentation.di.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

# 4. Bảo vệ Giao thức Khung tin Nhị phân & Serialization
-keep class com.filetransfer.app.core.protocol.** { *; }
-keepclassmembers class com.filetransfer.app.core.protocol.** { *; }
-keep class org.json.** { *; }

# 5. Bảo vệ Thư viện Mạng OkHttp & WebSockets
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# 6. Bảo vệ Kotlin Coroutines & Flow
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# 7. Bảo vệ CameraX & Google ML Kit Barcode Scanning
-keep class androidx.camera.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**

# 8. Bảo vệ Thư viện Sinh mã QR ZXing Core
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# 9. Bảo vệ Domain Models & Presentation Navigation
-keep class com.filetransfer.app.domain.model.** { *; }
-keep class com.filetransfer.app.presentation.navigation.** { *; }

# 10. Tối ưu hóa Chung, Giữ dòng mã phục vụ báo cáo sự cố (Stacktrace)
-dontusemixedcaseclassnames
-keepattributes SourceFile,LineNumberTable
