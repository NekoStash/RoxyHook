package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.CallbackPlatformHook
import hk.uwu.roxyhook.platform.Capability
import hk.uwu.roxyhook.platform.HookOptions
import hk.uwu.roxyhook.platform.PlatformHook
import java.lang.reflect.Executable

class HookHandle internal constructor(
    private val runtime: RoxyRuntime,
    private var native: PlatformHook,
    private val options: HookOptions
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var removed = false
    val member: Executable = native.member
    internal fun invalidate() { removed = true }
    val isActive: Boolean get() = !removed && !runtime.isClosed
    val id: String? get() = options.id
    /** Replaces this registration atomically; priority and id cannot change. Returns this managed handle. */
    fun replace(block: HookBuilder.() -> Unit): HookHandle = synchronized(lock) {
        runtime.ensureOpen()
        check(!removed) { "Hook was removed" }
        runtime.platform.requireCapability(Capability.ATOMIC_REPLACEMENT)
        val plan = HookBuilder(runtime.config, options).apply(block).build()
        require(plan.options == options) { "Replacement must retain priority and id" }
        plan.checkMember(member)
        native =
            (native as? CallbackPlatformHook)?.replaceCallbacks(plan.callbacks(runtime.platform) { unhook() })
                ?: native.replace(plan.interceptor(runtime.platform) { unhook() })
        this
    }
    fun unhook() {
        synchronized(lock) {
            if (removed) return
            native.unhook()
            removed = true
        }
        runtime.forget(this)
    }

    /** Alias for [unhook], matching the concise handle lifecycle vocabulary. */
    fun remove() = unhook()
    override fun close() = unhook()
}
