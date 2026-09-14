package hk.uwu.roxyhook.platform

import java.lang.reflect.Executable

/** Optional SPI for native before/after engines such as legacy Xposed.
 * A native adapter must NOT implement proceed by eagerly invoking the original: doing so
 * would bypass other native callbacks. Use this two-phase SPI for the normal hook DSL,
 * and do not declare INTERCEPTOR_CHAIN when a true chain cannot be provided.
 */
interface CallbackHookPlatform : HookPlatform {
    fun hookCallbacks(member: Executable, options: HookOptions, callbacks: PlatformCallbacks): CallbackPlatformHook
    override fun hook(member: Executable, options: HookOptions, interceptor: HookInterceptor): PlatformHook =
        unsupported(Capability.INTERCEPTOR_CHAIN)
}

sealed interface CallbackOutcome {
    data class Returned(val value: Any?) : CallbackOutcome
    data class Thrown(val error: Throwable) : CallbackOutcome
}
data class CallbackCompletion(val outcome: CallbackOutcome, val arguments: Array<Any?>)
fun interface PlatformCallbacks {
    /** Store the returned session in native per-invocation extras, never in a shared field. */
    fun before(member: Executable, receiver: Any?, arguments: Array<Any?>): CallbackSession
}
interface CallbackSession : AutoCloseable {
    /** Copy back to the native parameter after before() returns. */
    val arguments: Array<Any?>
    /** null means continue; Returned(null) means explicitly skip and return null. */
    val earlyOutcome: CallbackOutcome?
    /** Called once in native after, on the SAME thread. Pass current native arguments/receiver
     * and outcome, including any changes made by intervening native callbacks. This closes
     * the session and returns the final native outcome and argument vector to apply.
     */
    fun complete(receiver: Any?, arguments: Array<Any?>, outcome: CallbackOutcome): CallbackCompletion
    /** Cleanup if native after will not run. Must not execute callbacks or original code. */
    override fun close()
}
interface CallbackPlatformHook : PlatformHook {
    fun replaceCallbacks(callbacks: PlatformCallbacks): CallbackPlatformHook =
        throw UnsupportedOperationException("Atomic callback replacement is unavailable")
}
