package hk.uwu.roxyhook.platform

import java.lang.reflect.Executable

interface PlatformHook {
    val member: Executable
    val id: String?
    /** Must be idempotent. An invalidated old handle must not unhook its replacement. */
    fun unhook()
    /** Native atomic replacement, not unhook-then-hook. Old handle becomes invalid. */
    fun replace(interceptor: HookInterceptor): PlatformHook =
        throw UnsupportedOperationException("Atomic replacement is unavailable")
}
