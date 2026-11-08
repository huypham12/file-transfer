package com.teamshare.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.data.local.db.converter.Converters
import com.teamshare.app.data.local.db.dao.AuditLogDao
import com.teamshare.app.data.local.db.dao.TransferSessionDao
import com.teamshare.app.data.local.db.entity.SecurityAuditLogEntity
import com.teamshare.app.data.local.db.entity.TransferSessionEntity
import net.sqlcipher.database.SupportFactory

/**
 * Cơ sở dữ liệu Room Database chính của ứng dụng TeamShare theo SDD Chương 5.
 * Tinh gọn quản lý 2 thực thể cốt lõi: TransferSessionEntity và SecurityAuditLogEntity.
 * Tích hợp mã hóa toàn bộ trang 256-bit AES qua SQLCipher (Zetetic).
 */
@Database(
    entities = [
        TransferSessionEntity::class,
        SecurityAuditLogEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transferSessionDao(): TransferSessionDao
    abstract fun auditLogDao(): AuditLogDao

    /**
     * Tương thích ngược với các thành phần cũ gọi auditDao().
     */
    fun auditDao(): AuditLogDao = auditLogDao()

    companion object {
        const val DATABASE_NAME = "teamshare_encrypted.db"

        /**
         * Callback kích hoạt ràng buộc khóa ngoại (Foreign Key Constraints) trên SQLite.
         */
        internal val foreignKeysCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON;")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Lấy phiên bản cơ sở dữ liệu sản phẩm với mã hóa SQLCipher 256-bit.
         * Khóa passphrase lấy an toàn từ KeyManager (bảo vệ bởi Keystore TEE).
         */
        fun getInstance(context: Context, keyManager: IKeyManager): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildEncryptedDatabase(context.applicationContext, keyManager).also {
                    INSTANCE = it
                }
            }
        }

        /**
         * Khởi tạo CSDL với passphrase cụ thể.
         */
        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildEncryptedDatabaseWithPassphrase(context.applicationContext, passphrase).also {
                    INSTANCE = it
                }
            }
        }

        private fun buildEncryptedDatabase(context: Context, keyManager: IKeyManager): AppDatabase {
            val passphraseBytes = keyManager.getOrCreateDatabasePassphrase()
            return buildEncryptedDatabaseWithPassphrase(context, passphraseBytes)
        }

        private fun buildEncryptedDatabaseWithPassphrase(context: Context, passphraseBytes: ByteArray): AppDatabase {
            val factory = SupportFactory(passphraseBytes)
            return Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
                .openHelperFactory(factory)
                .addCallback(foreignKeysCallback)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Tạo CSDL thuần trong bộ nhớ phục vụ chạy Unit Test trên máy tính JVM
         * mà không phụ thuộc vào native library (.so) của SQLCipher.
         */
        fun createInMemory(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .addCallback(foreignKeysCallback)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
