package hk.uwu.roxyhook.platform

import java.lang.reflect.Executable

/** Call-scoped: never retain this object or move it to another thread. */
interface HookCall {
    val member: Executable
    val receiver: Any?
    val arguments: List<Any?>
    fun proceed(arguments: Array<Any?>): Any?
}
