package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.CallbackPlatformHook
import hk.uwu.roxyhook.platform.Capability
import hk.uwu.roxyhook.platform.HookOptions
import hk.uwu.roxyhook.platform.PlatformHook
import java.lang.reflect.Executable

class HookHandle internal constructor(
    private val runtime: RoxyRuntime,
    private var native: PlatformHook,
    internal val options: HookOptions,
) : AutoCloseable {
    enum class State { ACTIVE, REMOVED, DETACHED }

    @Volatile
    var state: State = State.ACTIVE
        private set
    val member: Executable = native.member
    val id: String? get() = options.id
    val hotReloadPolicy: HotReloadPolicy get() = options.hotReloadPolicy
    val isActive: Boolean get() = state == State.ACTIVE && !runtime.isClosed
    internal fun invalidate() {
        state = State.REMOVED
    }

    internal fun detachForHotReload() {
        check(state == State.ACTIVE)
        state = State.DETACHED
    }

    /** Replaces this registration atomically; KEEP registrations are intentionally immutable. */
    fun replace(block: HookBuilder.() -> Unit): HookHandle =
        synchronized(runtime.registrationLock) {
        runtime.ensureOpen()
            check(state == State.ACTIVE) { "Hook is not active: $state" }
            check(hotReloadPolicy != HotReloadPolicy.KEEP) { "KEEP hooks cannot be replaced; remove explicitly or restart" }
        runtime.platform.requireCapability(Capability.ATOMIC_REPLACEMENT)
        val plan = HookBuilder(runtime.config, options).apply(block).build()
            require(plan.options == options) { "Replacement must retain priority, id, and hot reload policy" }
        plan.checkMember(member)
            native =
                (native as? CallbackPlatformHook)?.replaceCallbacks(plan.callbacks(runtime.platform) { unhook() })
                    ?: native.replace(plan.interceptor(runtime.platform) { unhook() })
        this
    }

    fun unhook() = synchronized(runtime.registrationLock) {
        if (state != State.ACTIVE) return@synchronized
        // Managed scopes/groups are closed during retirement too. Their KEEP registrations
        // remain tracked until all cleanup succeeds, allowing failure cleanup to unhook them.
        if (hotReloadPolicy == HotReloadPolicy.KEEP && runtime.isRetiring) return@synchronized
        native.unhook()
        state = State.REMOVED
        runtime.forget(this)
    }
    fun remove() = unhook()
    override fun close() = unhook()
}
