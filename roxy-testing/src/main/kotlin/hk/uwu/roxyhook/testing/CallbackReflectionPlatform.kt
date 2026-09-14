package hk.uwu.roxyhook.testing

import hk.uwu.roxyhook.platform.*
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit-dispatch simulator of a native before/after engine. NOT a legacy Xposed adapter.
 * This independently exercises the callback SPI without inventing a synchronous proceed.
 */
class CallbackReflectionPlatform : CallbackHookPlatform {
    override val info = PlatformInfo("CallbackReflection (test only)", "0.1.0", 0)
    override val capabilities = setOf(Capability.METHOD_HOOK, Capability.INVOKE_ORIGINAL)
    override val logger = RoxyLogger.NONE
    private val original = ReflectionPlatform()
    private val registrations = CopyOnWriteArrayList<Registration>()
    override fun hookCallbacks(member: Executable, options: HookOptions, callbacks: PlatformCallbacks): CallbackPlatformHook {
        require(member is Method)
        require(options.id == null) { "This test platform has no atomic replacement" }
        return Registration(member, options, callbacks).also { registrations += it }
    }
    override fun invokeOriginal(member: Executable, receiver: Any?, arguments: Array<Any?>): Any? =
        original.invokeOriginal(member, receiver, arguments)
    /** Optional externalAfter models a native callback outside RoxyHook, between its two phases. */
    fun invoke(method: Method, receiver: Any?, vararg arguments: Any?,
               externalAfter: ((CallbackCompletion) -> CallbackCompletion)? = null): Any? {
        var args = arrayOf(*arguments)
        val entered = mutableListOf<CallbackSession>()
        var outcome: CallbackOutcome? = null
        try {
            for (registration in registrations.filter { it.member == method }.sortedByDescending { it.options.priority }) {
                try {
                    val session = registration.callbacks.before(method, receiver, args)
                    entered += session
                    args = session.arguments.copyOf()
                    outcome = session.earlyOutcome
                } catch (error: Throwable) { outcome = CallbackOutcome.Thrown(error) }
                if (outcome != null) break
            }
            if (outcome == null) outcome = try { CallbackOutcome.Returned(invokeOriginal(method, receiver, args)) }
            catch (error: Throwable) { CallbackOutcome.Thrown(error) }
            if (externalAfter != null) {
                val changed = externalAfter(CallbackCompletion(checkNotNull(outcome), args))
                outcome = changed.outcome; args = changed.arguments
            }
            for (session in entered.asReversed()) {
                try {
                    val completed = session.complete(receiver, args, checkNotNull(outcome))
                    outcome = completed.outcome; args = completed.arguments
                } catch (error: Throwable) { outcome = CallbackOutcome.Thrown(error) }
            }
            return when (val value = checkNotNull(outcome)) {
                is CallbackOutcome.Returned -> value.value
                is CallbackOutcome.Thrown -> throw value.error
            }
        } finally { entered.forEach { it.close() } }
    }
    private inner class Registration(override val member: Executable, val options: HookOptions,
                                     val callbacks: PlatformCallbacks) : CallbackPlatformHook {
        override val id: String? = options.id
        override fun unhook() { registrations.remove(this) }
    }
}
