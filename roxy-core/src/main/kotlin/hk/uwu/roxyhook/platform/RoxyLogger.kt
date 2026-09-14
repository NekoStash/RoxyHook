package hk.uwu.roxyhook.platform

import java.lang.reflect.Executable

fun interface RoxyLogger {
    fun log(level: LogLevel, message: String, error: Throwable?)
    companion object {
        val STDERR = RoxyLogger { level, message, error ->
            System.err.println("[RoxyHook/$level] $message")
            error?.printStackTrace(System.err)
        }
        val NONE = RoxyLogger { _, _, _ -> }
    }
}
