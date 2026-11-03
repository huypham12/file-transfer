package com.teamshare.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Kiểm thử đảm bảo callback onOpen của AppDatabase luôn kích hoạt PRAGMA foreign_keys = ON;
 */
class AppDatabaseCallbackTest {

    @Test
    fun testForeignKeysCallbackExecutesPragmaOnOpen() {
        val executedStatements = mutableListOf<String>()

        val proxyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedStatements.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.foreignKeysCallback.onOpen(proxyDb)

        assertTrue(
            "Phải thực thi PRAGMA foreign_keys = ON; khi mở CSDL",
            executedStatements.contains("PRAGMA foreign_keys = ON;")
        )
    }
}

