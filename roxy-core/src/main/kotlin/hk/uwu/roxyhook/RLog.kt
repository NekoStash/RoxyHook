package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.LogLevel
import hk.uwu.roxyhook.platform.RoxyLogger

/**
 * Public logging facade for module and core code. Every call resolves the currently open
 * injected runtime through RoxyHook's weak registry and writes through that runtime's
 * platform logger, so no platform type or Android API leaks into call sites. When no
 * injected runtime is open (tests, the module's own UI process, a closed runtime) the
 * message falls back to [RoxyLogger.STDERR] instead of being silently dropped.
 */
object RLog {
    /** Sink chosen per call; the selection is a snapshot and never retained. */
    private val logger: RoxyLogger get() = RoxyHook.injected()?.platform?.logger ?: RoxyLogger.STDERR

    /** Unified dispatch point; PackageScope.log also delegates here after adding its prefix. */
    fun log(level: LogLevel, message: String, error: Throwable? = null) = logger.log(level, message, error)

    /** Write a [LogLevel.DEBUG] message through the current logger. */
    fun debug(message: String, error: Throwable? = null) = log(LogLevel.DEBUG, message, error)

    /** Write a [LogLevel.INFO] message through the current logger. */
    fun info(message: String, error: Throwable? = null) = log(LogLevel.INFO, message, error)

    /** Write a [LogLevel.WARN] message through the current logger. */
    fun warn(message: String, error: Throwable? = null) = log(LogLevel.WARN, message, error)

    /** Write a [LogLevel.ERROR] message through the current logger. */
    fun error(message: String, error: Throwable? = null) = log(LogLevel.ERROR, message, error)
}
