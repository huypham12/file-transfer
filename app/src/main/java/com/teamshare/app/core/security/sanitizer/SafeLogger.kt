package com.teamshare.app.core.security.sanitizer

import android.util.Log

/**
 * Bộ ghi nhật ký an ninh (SafeLogger) theo chuẩn SRS FR-DEV-02 & SDD Mục 4.2 & 5.2.
 * Tự động lọc sạch các chuỗi dữ liệu nhạy cảm (Session Token, Private Key, Passphrase)
 * trước khi ghi ra Logcat nhằm ngăn ngừa tấn công rò rỉ thông tin (OWASP MASVS-STORAGE).
 * Vô hiệu hóa toàn bộ log mức DEBUG khi ở chế độ Release build.
 */
object SafeLogger {

    interface LogSink {
        fun log(priority: Int, tag: String, message: String, throwable: Throwable? = null)
    }

    private val defaultSink = object : LogSink {
        override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
            try {
                when (priority) {
                    Log.DEBUG -> Log.d(tag, message)
                    Log.INFO -> Log.i(tag, message)
                    Log.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
                    Log.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
                    else -> Log.println(priority, tag, message)
                }
            } catch (_: Throwable) {
                // Fallback an toàn trên môi trường Unit Test thuần JVM không có mock Android Log
                val stream = if (priority >= Log.WARN) System.err else System.out
                val prefix = when (priority) {
                    Log.DEBUG -> "[DEBUG]"
                    Log.INFO -> "[INFO]"
                    Log.WARN -> "[WARN]"
                    Log.ERROR -> "[ERROR]"
                    else -> "[LOG]"
                }
                stream.println("$prefix $tag: $message")
                throwable?.printStackTrace(stream)
            }
        }
    }

    private var customSink: LogSink? = null

    /**
     * Bật/tắt chế độ ghi log debug. Mặc định đồng bộ với BuildConfig.DEBUG.
     */
    var isDebugEnabled: Boolean = try {
        val clazz = Class.forName("com.example.file_tranfer.BuildConfig")
        val field = clazz.getField("DEBUG")
        field.getBoolean(null)
    } catch (_: Throwable) {
        true
    }

    /**
     * Thiết lập Sink ghi log tùy chỉnh (phục vụ Unit Test kiểm tra dữ liệu khử độc).
     */
    fun setLogSink(sink: LogSink?) {
        customSink = sink
    }

    private val tokenRegex = Regex("(?i)(token|sessionToken|session_token|tok)=([^\n\r\t,;&\"' ]+)")
    private val secretRegex = Regex("(?i)(password|passphrase|secret|private_key|privKey)=([^\n\r\t,;&\"' ]+)")
    private val pemKeyRegex = Regex("-----BEGIN [A-Z0-9 _-]+PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 _-]+PRIVATE KEY-----")

    /**
     * Khử độc chuỗi log, thay thế mọi thông tin nhạy cảm bằng [REDACTED].
     */
    fun sanitize(message: String): String {
        if (message.isBlank()) return message

        var sanitized = message
        sanitized = pemKeyRegex.replace(sanitized, "[REDACTED_PRIVATE_KEY]")
        sanitized = tokenRegex.replace(sanitized) { matchResult ->
            "${matchResult.groupValues[1]}=[REDACTED]"
        }
        sanitized = secretRegex.replace(sanitized) { matchResult ->
            "${matchResult.groupValues[1]}=[REDACTED]"
        }
        return sanitized
    }

    /**
     * Ghi log mức DEBUG. Bị vô hiệu hóa hoàn toàn khi !isDebugEnabled (Release build).
     */
    fun d(tag: String, message: String) {
        if (!isDebugEnabled) return
        val cleanMessage = sanitize(message)
        getEffectiveSink().log(Log.DEBUG, tag, cleanMessage)
    }

    /**
     * Ghi log mức INFO.
     */
    fun i(tag: String, message: String) {
        val cleanMessage = sanitize(message)
        getEffectiveSink().log(Log.INFO, tag, cleanMessage)
    }

    /**
     * Ghi log mức WARN.
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        val cleanMessage = sanitize(message)
        getEffectiveSink().log(Log.WARN, tag, cleanMessage, throwable)
    }

    /**
     * Ghi log mức ERROR.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val cleanMessage = sanitize(message)
        getEffectiveSink().log(Log.ERROR, tag, cleanMessage, throwable)
    }

    private fun getEffectiveSink(): LogSink = customSink ?: defaultSink
}

