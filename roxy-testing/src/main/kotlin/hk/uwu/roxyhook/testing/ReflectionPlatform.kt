package hk.uwu.roxyhook.testing

import hk.uwu.roxyhook.platform.*
import java.lang.invoke.MethodHandles
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Explicit-dispatch JVM simulator, NOT an ART hooking implementation.
 * Only calls through invoke(...) are intercepted. It does not instrument normal method calls.
 */
class ReflectionPlatform(override val logger: RoxyLogger = RoxyLogger.NONE) : HookPlatform {
    override val info = PlatformInfo("Roxy JVM test platform", "0.1.0", 1)
    override val capabilities = setOf(Capability.METHOD_HOOK, Capability.INVOKE_ORIGINAL, Capability.INTERCEPTOR_CHAIN, Capability.ATOMIC_REPLACEMENT)
    private val lock = Any()
    private var nextOrder = 0L
    private val registrations = mutableListOf<Registration>()
    /** Useful for testing failure rollback. */
    var reject: ((Executable) -> Boolean)? = null
    val registrationCount: Int get() = synchronized(lock) { registrations.size }

    private data class Registration(val token: Any, val order: Long, val member: Executable,
                                    val options: HookOptions, val interceptor: HookInterceptor)
    override fun hook(member: Executable, options: HookOptions, interceptor: HookInterceptor): PlatformHook = synchronized(lock) {
        require(member is Method) { "The JVM simulator does not emulate constructor initialization" }
        check(reject?.invoke(member) != true) { "Test-requested registration failure: $member" }
        if (options.id != null) registrations.removeAll { it.member == member && it.options.id == options.id }
        val registration = Registration(Any(), nextOrder++, member, options, interceptor)
        registrations += registration
        Handle(registration)
    }
    private inner class Handle(private val registration: Registration) : PlatformHook {
        override val member get() = registration.member
        override val id get() = registration.options.id
        override fun unhook() { synchronized(lock) { registrations.removeAll { it.token === registration.token } } }
        override fun replace(interceptor: HookInterceptor): PlatformHook = synchronized(lock) {
            val index = registrations.indexOfFirst { it.token === registration.token }
            check(index >= 0) { "Native handle is no longer valid" }
            val updated = registration.copy(token = Any(), interceptor = interceptor)
            registrations[index] = updated
            Handle(updated)
        }
    }
    fun invoke(member: Method, receiver: Any?, vararg arguments: Any?): Any? {
        val snapshot = synchronized(lock) {
            registrations.filter { it.member == member }.sortedWith(
                compareByDescending<Registration> { it.options.priority }.thenBy { it.order })
        }
        fun next(index: Int, values: Array<Any?>): Any? {
            if (index == snapshot.size) return invokeOriginal(member, receiver, values)
            return snapshot[index].interceptor.intercept(object : HookCall {
                override val member: Executable = member
                override val receiver: Any? = receiver
                override val arguments: List<Any?> = values.toList()
                override fun proceed(arguments: Array<Any?>): Any? = next(index + 1, arguments)
            })
        }
        return next(0, arrayOf(*arguments))
    }
    override fun invokeOriginal(member: Executable, receiver: Any?, arguments: Array<Any?>): Any? {
        require(member is Method)
        try {
            if (Modifier.isStatic(member.modifiers)) {
                member.isAccessible = true
                return member.invoke(null, *arguments)
            }
            // Reflection Method.invoke is virtual; unreflectSpecial preserves the exact-executable contract.
            val lookup = MethodHandles.privateLookupIn(member.declaringClass, MethodHandles.lookup())
            return lookup.unreflectSpecial(member, member.declaringClass)
                .bindTo(requireNotNull(receiver)).invokeWithArguments(arguments.toList())
        } catch (error: InvocationTargetException) { throw error.targetException }
    }
}
