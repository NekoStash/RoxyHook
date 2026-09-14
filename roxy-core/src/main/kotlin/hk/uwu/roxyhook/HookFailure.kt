package hk.uwu.roxyhook

import java.lang.reflect.Executable

data class HookFailure(val member: Executable, val phase: String, val cause: Throwable)
internal fun rethrowFatal(error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
}
