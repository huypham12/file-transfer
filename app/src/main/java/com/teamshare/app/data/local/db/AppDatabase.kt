package com.teamshare.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.teamshare.app.core.security.keystore.IKeyManager
import com.teamshare.app.data.local.db.converter.Converters
import com.teamshare.app.data.local.db.dao.AuditDao
import com.teamshare.app.data.local.db.dao.DocumentDao
import com.teamshare.app.data.local.db.dao.FolderDao
import com.teamshare.app.data.local.db.dao.MemberDao
import com.teamshare.app.data.local.db.dao.RevocationDao
import com.teamshare.app.data.local.db.dao.TeamDao
import com.teamshare.app.data.local.db.dao.TransferSessionDao
import com.teamshare.app.data.local.db.entity.AuditEntity
import com.teamshare.app.data.local.db.entity.DocumentEntity
import com.teamshare.app.data.local.db.entity.MemberEntity
import com.teamshare.app.data.local.db.entity.RevocationEntity
import com.teamshare.app.data.local.db.entity.SessionEntity
import com.teamshare.app.data.local.db.entity.TeamEntity
import com.teamshare.app.data.local.db.entity.TeamFolderEntity
import net.sqlcipher.database.SupportFactory

/**
 * Cơ sở dữ liệu Room Database chính của ứng dụng TeamShare.
 * Tích hợp mã hóa toàn bộ trang 256-bit AES qua SQLCipher (Zetetic).
 */
@Database(
    entities = [
        TeamEntity::class,
        MemberEntity::class,
        DocumentEntity::class,
        SessionEntity::class,
        AuditEntity::class,
        RevocationEntity::class,
        TeamFolderEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun teamDao(): TeamDao
    abstract fun memberDao(): MemberDao
    abstract fun documentDao(): DocumentDao
    abstract fun transferSessionDao(): TransferSessionDao
    abstract fun auditDao(): AuditDao
    abstract fun revocationDao(): RevocationDao
    abstract fun folderDao(): FolderDao

    companion object {
        const val DATABASE_NAME = "teamshare_encrypted.db"

        /**
         * Bản cập nhật CSDL từ phiên bản 1 lên 2:
         * - Bảng team_folders và chỉ mục cha-con.
         * - Bảng revocation_records (Signed CRL & Tombstone).
         * - Cột folder_id trong bảng shared_documents.
         * - Cột actor_name và signature trong bảng security_audit_logs.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS team_folders (
                        folder_id TEXT PRIMARY KEY NOT NULL,
                        team_id TEXT NOT NULL,
                        parent_folder_id TEXT,
                        folder_name TEXT NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        is_deleted INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(team_id) REFERENCES teams(team_id) ON DELETE CASCADE,
                        FOREIGN KEY(parent_folder_id) REFERENCES team_folders(folder_id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_team_parent ON team_folders(team_id, parent_folder_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_parent ON team_folders(parent_folder_id)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS revocation_records (
                        record_id TEXT PRIMARY KEY NOT NULL,
                        team_id TEXT NOT NULL,
                        revoked_member_id TEXT NOT NULL,
                        revoked_fingerprint TEXT NOT NULL,
                        revocation_type TEXT NOT NULL,
                        revoked_at INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        owner_signature TEXT NOT NULL,
                        FOREIGN KEY(team_id) REFERENCES teams(team_id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_revocation_team_member ON revocation_records(team_id, revoked_member_id)")

                db.execSQL("ALTER TABLE shared_documents ADD COLUMN folder_id TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_documents_folder ON shared_documents(folder_id)")

                db.execSQL("ALTER TABLE security_audit_logs ADD COLUMN actor_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE security_audit_logs ADD COLUMN signature TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Callback kích hoạt ràng buộc khóa ngoại (Foreign Key Constraints) và Cascade Delete trên SQLite.
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
                .addMigrations(MIGRATION_1_2)
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
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

