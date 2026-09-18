package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.CallbackHookPlatform
import hk.uwu.roxyhook.platform.Capability
import hk.uwu.roxyhook.platform.HookCall
import hk.uwu.roxyhook.platform.HookInterceptor
import hk.uwu.roxyhook.platform.HookOptions
import hk.uwu.roxyhook.platform.HookPlatform
import hk.uwu.roxyhook.platform.PlatformHook
import java.lang.reflect.Constructor
import java.lang.reflect.Executable

/** Binds a callback's self-removal action after native registration is tracked. */
private class HookBinding {
    private var handle: HookHandle? = null
    private var removalRequested = false

    @Synchronized
    fun remove() {
        handle?.let { it.unhook() } ?: run { removalRequested = true }
    }

    @Synchronized
    fun bind(handle: HookHandle) {
        this.handle = handle
        if (removalRequested) handle.unhook()
    }
}

/** Own one runtime per module generation. No process-global platform singleton. */
class RoxyRuntime(val platform: HookPlatform, val config: RoxyConfig = RoxyConfig()) : AutoCloseable {
    private val lock = Any()
    private val handles = linkedSetOf<HookHandle>()
    private val resources = linkedSetOf<AutoCloseable>()
    private val services = mutableMapOf<RuntimeKey<*>, RuntimeServiceSlot>()
    init { RoxyHook.register(this) }

    /** A typed process-generation service slot. No platform singleton or classloader-global cache. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> service(key: RuntimeKey<T>, factory: () -> T): T {
        val slot = synchronized(lock) {
            ensureOpen()
            services.getOrPut(key) { RuntimeServiceSlot() }
        }
        // Never run extension factories while holding the global runtime lock. A lifecycle root
        // may fire concurrently and register another hook while its factory is still installing.
        val result = slot.obtain(factory) as T
        ensureOpen()
        return result
    }
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> serviceOrNull(key: RuntimeKey<T>): T? = synchronized(lock) { services[key]?.value as T? }

    /** Own subscriptions/receivers together with hooks; all are closed even if one cleanup fails. */
    fun <T : AutoCloseable> manage(resource: T): T = synchronized(lock) {
        ensureOpen()
        resources += resource
        resource
    }
    fun onClose(action: () -> Unit): AutoCloseable = manage(hk.uwu.roxyhook.prefs.Subscription.once(action))
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    val hookCount: Int get() = synchronized(lock) { handles.size }
    fun scope(context: PackageContext): PackageScope { ensureOpen(); return PackageScope(this, context) }

    fun hook(member: Executable, block: HookBuilder.() -> Unit): HookHandle =
        install(member, HookBuilder(config).apply(block).build())

    internal fun install(member: Executable, plan: HookPlan): HookHandle = synchronized(lock) {
        ensureOpen()
        platform.requireCapability(if (member is Constructor<*>) Capability.CONSTRUCTOR_HOOK else Capability.METHOD_HOOK)
        if (plan.options.id != null) platform.requireCapability(Capability.ATOMIC_REPLACEMENT)
        plan.checkMember(member)
        val self = HookBinding()
        val removeSelf = { self.remove() }
        val native = if (platform is CallbackHookPlatform)
            platform.hookCallbacks(member, plan.options, plan.callbacks(platform, removeSelf))
        else {
            platform.requireCapability(Capability.INTERCEPTOR_CHAIN)
            platform.hook(member, plan.options, plan.interceptor(platform, removeSelf))
        }
        track(native, plan.options).also { self.bind(it) }
    }
    /** Raw interceptors propagate failures. No implicit fallback or automatic call to proceed. */
    fun intercept(member: Executable, options: HookOptions = HookOptions(), block: HookCall.() -> Any?): HookHandle =
        synchronized(lock) {
            ensureOpen()
            platform.requireCapability(if (member is Constructor<*>) Capability.CONSTRUCTOR_HOOK else Capability.METHOD_HOOK)
            if (options.id != null) platform.requireCapability(Capability.ATOMIC_REPLACEMENT)
            platform.requireCapability(Capability.INTERCEPTOR_CHAIN)
            track(platform.hook(member, options, guarded(block)), options)
        }
    fun hookClassInitializer(type: Class<*>, block: HookBuilder.() -> Unit): HookHandle = synchronized(lock) {
        ensureOpen()
        platform.requireCapability(Capability.CLASS_INITIALIZER)
        platform.requireCapability(Capability.INTERCEPTOR_CHAIN)
        val plan = HookBuilder(config).apply(block).build()
        if (plan.options.id != null) platform.requireCapability(Capability.ATOMIC_REPLACEMENT)
        val self = HookBinding()
        val removeSelf = { self.remove() }
        track(
            platform.hookClassInitializer(
                type,
                plan.options,
                plan.interceptor(platform, removeSelf)
            ), plan.options
        ).also { self.bind(it) }
    }
    fun hookAll(members: Collection<Executable>, block: HookBuilder.() -> Unit): HookGroup {
        require(members.isNotEmpty()) { "No executables were selected" }
        val plan = HookBuilder(config).apply(block).build()
        require(members.size == 1 || plan.options.id == null) {
            "Named batch hooks cannot be rolled back safely; install named hooks individually"
        }
        val installed = mutableListOf<HookHandle>()
        try {
            members.distinct().forEach { installed += install(it, plan) }
        } catch (error: Throwable) {
            installed.asReversed().forEach { try { it.unhook() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) } }
            throw error
        }
        return HookGroup(installed)
    }
    private fun guarded(block: HookCall.() -> Any?) = HookInterceptor { raw ->
        val call = ScopedCall(raw)
        try { block(call) } finally { call.finish() }
    }
    private fun track(native: PlatformHook, options: HookOptions): HookHandle {
        // Same-id native registration already replaced the old token. Release our stale reference
        // without calling native unhook, which must never affect the new registration.
        if (options.id != null) {
            val stale = handles.filter { it.id == options.id && it.member == native.member }
            stale.forEach { it.invalidate(); handles.remove(it) }
        }
        return HookHandle(this, native, options).also { handles += it }
    }
    internal fun forget(handle: HookHandle) { synchronized(lock) { handles.remove(handle) } }
    internal fun ensureOpen() { check(!closed) { "RoxyRuntime is closed" } }
    override fun close() {
        val snapshot = synchronized(lock) {
            if (closed) return
            closed = true
            (handles.toList() + resources.toList()).also {
                handles.clear()
                resources.clear()
                services.clear()
            }
        }
        RoxyHook.unregister(this)
        closeEvery(snapshot.asReversed())
    }
}
