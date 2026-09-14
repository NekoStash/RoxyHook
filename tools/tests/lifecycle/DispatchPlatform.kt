package hk.uwu.roxyhook.lifecycletests

import hk.uwu.roxyhook.platform.*
import java.lang.reflect.Executable
import java.util.concurrent.CopyOnWriteArrayList

/** Explicit dispatch MODEL, not JVM instrumentation or an Android emulator. */
class DispatchPlatform : HookPlatform {
    override val info = PlatformInfo("lifecycle-dispatch-model", "1", 102)
    override val capabilities = setOf(Capability.METHOD_HOOK, Capability.CONSTRUCTOR_HOOK, Capability.INTERCEPTOR_CHAIN, Capability.INVOKE_ORIGINAL)
    val errors = CopyOnWriteArrayList<Throwable>()
    override val logger = RoxyLogger { _, _, error -> if (error != null) errors += error }
    private class Installed(val member: Executable, val options: HookOptions, val interceptor: HookInterceptor) { @Volatile var active = true }
    private val installed = CopyOnWriteArrayList<Installed>()
    var failOnName: String? = null
    val activeCount: Int get() = installed.count { it.active }
    fun contains(member: Executable) = installed.any { it.active && it.member == member }
    override fun hook(member: Executable, options: HookOptions, interceptor: HookInterceptor): PlatformHook {
        if (member.name == failOnName) error("injected registration failure")
        val value = Installed(member, options, interceptor)
        installed += value
        return object : PlatformHook {
            override val member = value.member
            override val id = options.id
            override fun unhook() { value.active = false }
        }
    }
    override fun invokeOriginal(member: Executable, receiver: Any?, arguments: Array<Any?>): Any? =
        error("Model requires an explicit original-body lambda; no Android fixture implementation is invoked")

    fun dispatch(member: Executable, owner: Any, args: Array<Any?> = emptyArray(), body: () -> Any? = { null }): Any? {
        val chain = installed.filter { it.active && it.member == member }.sortedByDescending { it.options.priority }
        fun next(index: Int, values: Array<Any?>): Any? {
            if (index == chain.size) return body()
            return chain[index].interceptor.intercept(object : HookCall {
                override val member = member
                override val receiver = owner
                override val arguments = values.toList()
                override fun proceed(arguments: Array<Any?>): Any? = next(index + 1, arguments)
            })
        }
        return next(0, args)
    }
}
