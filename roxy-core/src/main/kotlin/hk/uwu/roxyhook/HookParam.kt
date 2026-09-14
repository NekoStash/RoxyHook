package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.*
import hk.uwu.roxyhook.reflect.validateArguments
import java.lang.reflect.Constructor
import java.lang.reflect.Executable

/** One invocation, never shared between threads or retained after the callback finishes. */
class HookParam internal constructor(
    internal val call: ScopedCall,
    private val platform: HookPlatform
) {
    internal var phase = Phase.BEFORE
    internal var outcome: Outcome = Outcome.Pending
    internal var originalCalls = 0
    internal var lastOriginalOutcome: Outcome = Outcome.Pending
    private var argumentValues = call.arguments.toTypedArray()
    val member: Executable get() { call.checkActive(); return call.member }
    val instance: Any? get() { call.checkActive(); return call.receiver }
    val thisObject: Any? get() = instance
    val args: Array<Any?> get() { call.checkActive(); return argumentValues }
    /** Values shared between before/after for this invocation only. */
    val extras: MutableMap<String, Any?> = mutableMapOf()

    var result: Any?
        get() { call.checkActive(); return (outcome as? Outcome.Returned)?.value }
        set(value) {
            call.checkActive()
            check(phase != Phase.BEFORE || member !is Constructor<*>) {
                "Skipping constructor initialization is not supported; use before for arguments and after for fields"
            }
            outcome = Outcome.Returned(value)
        }
    var throwable: Throwable?
        get() { call.checkActive(); return (outcome as? Outcome.Thrown)?.error }
        set(value) {
            call.checkActive()
            if (value != null) outcome = Outcome.Thrown(value)
            else if (outcome is Outcome.Thrown) outcome = Outcome.Returned(null)
        }
    val hasThrowable: Boolean get() = throwable != null
    fun args(index: Int) = Argument(this, index)
    inline fun <reified T> instance(): T = instance as T
    inline fun <reified T> result(): T = result as T
    fun resultNull() { result = null }

    /** Bypasses ALL hooks, unlike proceed. Does not implicitly assign result. */
    fun callOriginal(): Any? = callOriginal(*args)
    fun callOriginal(vararg arguments: Any?): Any? {
        call.checkActive()
        platform.requireCapability(Capability.INVOKE_ORIGINAL)
        val values = arrayOf(*arguments)
        validateArguments(member, values)
        originalCalls++
        return try {
            platform.invokeOriginal(member, instance, values).also { lastOriginalOutcome = Outcome.Returned(it) }
        } catch (error: Throwable) {
            lastOriginalOutcome = Outcome.Thrown(error)
            throw error
        }
    }
    internal fun restoreArguments(values: Array<Any?>) { argumentValues = values }
}
