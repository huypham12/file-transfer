# =========================================================================
# QUY TẮC BẢO VỆ VÀ XÁO TRỘN MÃ NGUỒN PROGUARD / R8 (STAGE 8 RELEASE)
# HỆ THỐNG CỘNG TÁC NHÓM VÀ CHIA SẺ TÀI LIỆU AN TOÀN TEAMSHARE
# =========================================================================

# 1. Bảo vệ Toàn vẹn CSDL Room & Thư viện Native SQLCipher
-keep class androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomOpenHelper
-keep class com.teamshare.app.data.local.db.AppDatabase { *; }
-keep class com.teamshare.app.data.local.db.entity.** { *; }
-keep class com.teamshare.app.data.local.db.dao.** { *; }
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# 2. Bảo vệ Giao thức Khung tin Nhị phân & Serialization
-keep class com.teamshare.app.core.protocol.** { *; }
-keepclassmembers class com.teamshare.app.core.protocol.** { *; }
-keep class org.json.** { *; }

# 3. Bảo vệ Thư viện Mạng OkHttp & WebSockets
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# 4. Bảo vệ Kotlin Coroutines & Flow
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# 5. Bảo vệ CameraX & Google ML Kit Barcode Scanning
-keep class androidx.camera.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**

# 6. Bảo vệ Thư viện Sinh mã QR ZXing Core
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# 7. Bảo vệ Domain Models & Presentation Navigation
-keep class com.teamshare.app.domain.model.** { *; }
-keep class com.teamshare.app.presentation.navigation.** { *; }

# 8. Tối ưu hóa Chung và Ẩn Thông tin Dòng mã
-repackageclasses 'com.teamshare.app.obf'
-allowaccessmodification
-dontusemixedcaseclassnames
-keepattributes SourceFile,LineNumberTable
