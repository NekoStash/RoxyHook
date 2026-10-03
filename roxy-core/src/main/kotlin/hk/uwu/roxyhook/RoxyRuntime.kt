package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.CallbackHookPlatform
import hk.uwu.roxyhook.platform.Capability
import hk.uwu.roxyhook.platform.HookCall
import hk.uwu.roxyhook.platform.HookInterceptor
import hk.uwu.roxyhook.platform.HookOptions
import hk.uwu.roxyhook.platform.HookPlatform
import hk.uwu.roxyhook.platform.LogLevel
import hk.uwu.roxyhook.platform.PlatformHook
import hk.uwu.roxyhook.platform.RetainedHook
import java.lang.reflect.Constructor
import java.lang.reflect.Executable

/** Binds a callback's self-removal action after native registration is tracked. */
private class HookBinding {
    private var handle: HookHandle? = null
    private var removalRequested = false

    fun remove() {
        val current = synchronized(this) {
            handle.also { if (it == null) removalRequested = true }
        }
        current?.unhook()
    }

    fun bind(handle: HookHandle) {
        val remove = synchronized(this) {
            this.handle = handle
            removalRequested
        }
        if (remove) handle.unhook()
    }
}

/** A KEEP callback must not capture a binding, handle, or runtime merely for self-removal. */
private fun rejectKeptSelfRemoval(): Nothing =
    error("KEEP hooks cannot removeSelf; remove the current-generation handle explicitly")

/** Own one runtime per module generation. No process-global platform singleton. */
class RoxyRuntime(val platform: HookPlatform, val config: RoxyConfig = RoxyConfig()) : AutoCloseable {
    private enum class State { ACTIVE, QUIESCING, CLOSED }

    internal val registrationLock = Any()
    private val lock get() = registrationLock

    @Volatile
    internal var isRetiring = false
        private set
    private val handles = linkedSetOf<HookHandle>()

    private data class KeepKey(val member: Executable, val id: String?)

    private val keepSlots = mutableMapOf<KeepKey, HookHandle>()
    private val resources = linkedSetOf<AutoCloseable>()
    private val hookers = mutableListOf<RoxyHooker>()
    private val services = mutableMapOf<RuntimeKey<*>, RuntimeServiceSlot>()
    @Volatile
    private var state = State.ACTIVE
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
    val isActive: Boolean get() = state == State.ACTIVE
    val isClosed: Boolean get() = state != State.ACTIVE
    val hookCount: Int get() = synchronized(lock) { handles.size }
    fun scope(context: PackageContext): PackageScope { ensureOpen(); return PackageScope(this, context) }

    internal fun registerHooker(hooker: RoxyHooker) = synchronized(lock) {
        ensureOpen()
        if (hookers.none { it === hooker }) hookers += hooker
    }

    /** Validate hot reload without mutating generation-owned state. */
    fun preflightHotReload(): Boolean {
        val snapshot = synchronized(lock) {
            ensureOpen()
            hookers.toList()
        }
        snapshot.forEach { hooker ->
            val accepted = runCatching { hooker.onHotReloadPreflight() }
                .onFailure { error ->
                    platform.logger.log(
                        LogLevel.ERROR,
                        "Hot reload preflight failed for ${hooker.javaClass.name}",
                        error,
                    )
                }
                .getOrDefault(false)
            if (!accepted) return false
        }
        return true
    }

    /**
     * Mark this generation unavailable and stop hooker-owned work in reverse installation order.
     * Cleanup continues after failures and returns their count.
     */
    fun quiesceForHotReload(): Int {
        val snapshot = synchronized(lock) {
            when (state) {
                State.ACTIVE -> state = State.QUIESCING
                State.QUIESCING -> return 0
                State.CLOSED -> return 0
            }
            hookers.toList().asReversed()
        }
        var failures = 0
        snapshot.forEach { hooker ->
            runCatching { hooker.onHotReloadQuiesce() }
                .onFailure { error ->
                    failures++
                    platform.logger.log(
                        LogLevel.ERROR,
                        "Hot reload quiesce failed for ${hooker.javaClass.name}",
                        error,
                    )
                }
        }
        return failures
    }

    fun hook(member: Executable, block: HookBuilder.() -> Unit): HookHandle =
        install(member, HookBuilder(config).apply(block).build())

    internal fun install(member: Executable, plan: HookPlan): HookHandle = synchronized(lock) {
        ensureOpen()
        validateOptions(member, plan.options)
        plan.checkMember(member)
        reusable(member, plan.options)?.let { return@synchronized it }
        val self = if (plan.options.hotReloadPolicy == HotReloadPolicy.KEEP) null else HookBinding()
        val removeSelf: () -> Unit = if (self == null) ::rejectKeptSelfRemoval else self::remove
        val native = if (platform is CallbackHookPlatform)
            platform.hookCallbacks(member, plan.options, plan.callbacks(platform, removeSelf))
        else {
            platform.requireCapability(Capability.INTERCEPTOR_CHAIN)
            platform.hook(member, plan.options, plan.interceptor(platform, removeSelf))
        }
        track(native, plan.options).also { self?.bind(it) }
    }
    /** Raw interceptors propagate failures. No implicit fallback or automatic call to proceed. */
    fun intercept(member: Executable, options: HookOptions = HookOptions(), block: HookCall.() -> Any?): HookHandle =
        synchronized(lock) {
            ensureOpen()
            validateOptions(member, options)
            platform.requireCapability(Capability.INTERCEPTOR_CHAIN)
            reusable(member, options)?.let { return@synchronized it }
            track(platform.hook(member, options, guarded(block)), options)
        }
    fun hookClassInitializer(type: Class<*>, block: HookBuilder.() -> Unit): HookHandle = synchronized(lock) {
        ensureOpen()
        platform.requireCapability(Capability.CLASS_INITIALIZER)
        platform.requireCapability(Capability.INTERCEPTOR_CHAIN)
        val plan = HookBuilder(config).apply(block).build()
        require(plan.options.hotReloadPolicy != HotReloadPolicy.KEEP) { "KEEP is not supported for class initializers" }
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
        val distinct = members.distinct()
        require(plan.options.hotReloadPolicy == HotReloadPolicy.KEEP || members.size == 1 || plan.options.id == null) {
            "Named batch hooks cannot be rolled back safely; install named hooks individually"
        }
        return synchronized(lock) {
            ensureOpen()
            if (platform !is CallbackHookPlatform) platform.requireCapability(Capability.INTERCEPTOR_CHAIN)
            // Validate the complete selection before the first native mutation.
            distinct.forEach {
                validateOptions(it, plan.options)
                plan.checkMember(it)
                reusable(it, plan.options)
            }
            val before = handles.toSet()
            val installed = mutableListOf<HookHandle>()
            var failedMember: Executable? = null
            try {
                distinct.forEach {
                    failedMember = it
                    installed += install(it, plan)
                }
            } catch (error: Throwable) {
                error.addSuppressed(IllegalStateException("Batch hook installation failed at $failedMember"))
                installed.asReversed().filter { it !in before }.forEach {
                    try {
                        it.unhook()
                    } catch (cleanup: Throwable) {
                        if (cleanup !== error) error.addSuppressed(cleanup)
                    }
                }
                throw error
            }
            HookGroup(installed)
        }
    }

    /**
     * Platform SPI: adopt current-generation wrappers without installing/replacing callbacks.
     * Validates the whole batch first. On rejection ownership remains with the caller, which
     * must clean up every supplied native handle; duplicate slots are never silently selected.
     */
    fun adoptKeptHooks(hooks: Collection<RetainedHook>): List<HookHandle> = synchronized(lock) {
        ensureOpen()
        platform.requireCapability(Capability.HOT_RELOAD_KEEP)
        val incoming = hooks.toList()
        val keys = mutableSetOf<KeepKey>()
        incoming.forEach {
            require(it.options.hotReloadPolicy == HotReloadPolicy.KEEP) { "Only KEEP hooks may be adopted" }
            validateOptions(it.hook.member, it.options)
            val key = KeepKey(it.hook.member, it.options.id)
            require(keys.add(key) && key !in keepSlots) { "Duplicate KEEP registrations cannot be adopted: ${it.hook.member}" }
            reusable(it.hook.member, it.options)
        }
        incoming.map { track(it.hook, it.options) }
    }

    /** Platform SPI: retire a generation without unregistering its KEEP callbacks. */
    fun retireForHotReload() {
        synchronized(lock) {
            if (state == State.CLOSED || isRetiring) return
            // Hookers and managed groups may close their handles during cleanup.
            isRetiring = true
        }
        var failure: Throwable? = null
        fun record(error: Throwable) {
            if (failure == null) failure =
                error else if (failure !== error) failure.addSuppressed(error)
        }
        try {
            val count = quiesceForHotReload()
            if (count > 0) platform.logger.log(
                LogLevel.ERROR,
                "Hot reload continues after quiesce failures: count=$count", null
            )
        } catch (error: Throwable) {
            record(error)
        }
        val (ownedHooks, ownedResources) = synchronized(lock) {
            state = State.CLOSED
            (handles.toList() to resources.toList()).also {
                handles.clear()
                keepSlots.clear()
                resources.clear()
                hookers.clear()
                services.clear()
            }
        }
        RoxyHook.unregister(this)
        ownedResources.asReversed().forEach { resource ->
            try {
                resource.close()
            } catch (error: Throwable) {
                record(error)
            }
        }
        ownedHooks.asReversed().filter { it.hotReloadPolicy != HotReloadPolicy.KEEP }
            .forEach { handle ->
                try {
                    handle.unhook()
                } catch (error: Throwable) {
                    record(error)
                }
            }
        synchronized(lock) {
            if (failure == null) {
                ownedHooks.filter { it.hotReloadPolicy == HotReloadPolicy.KEEP }
                    .forEach { it.detachForHotReload() }
            }
            isRetiring = false
        }
        if (failure != null) {
            // No successful handoff: attempt every native removal, even after earlier failures.
            ownedHooks.asReversed().forEach { handle ->
                try {
                    handle.unhook()
                } catch (error: Throwable) {
                    record(error)
                }
            }
            synchronized(lock) {
                // Keep failed removals reachable so a later close() can retry them.
                handles.addAll(ownedHooks.filter { it.state == HookHandle.State.ACTIVE })
            }
            throw failure
        }
    }

    private fun validateOptions(member: Executable, options: HookOptions) {
        platform.validateHookOptions(options)
        platform.requireCapability(if (member is Constructor<*>) Capability.CONSTRUCTOR_HOOK else Capability.METHOD_HOOK)
        if (options.id != null && options.hotReloadPolicy == HotReloadPolicy.REINSTALL) {
            platform.requireCapability(Capability.ATOMIC_REPLACEMENT)
        }
        if (options.hotReloadPolicy == HotReloadPolicy.KEEP) {
            require(member !is Constructor<*> || platform.capabilities.contains(Capability.CONSTRUCTOR_HOOK))
            platform.requireCapability(Capability.HOT_RELOAD_KEEP)
        }
    }

    private fun reusable(member: Executable, options: HookOptions): HookHandle? {
        if (options.id != null) {
            require(handles.none {
                it.member == member && it.id == options.id && it.hotReloadPolicy != options.hotReloadPolicy
            }) { "Hook hot reload policy differs for $member and id=${options.id}; remove explicitly or restart" }
        }
        if (options.hotReloadPolicy != HotReloadPolicy.KEEP) return null
        val existing = keepSlots[KeepKey(member, options.id)] ?: return null
        require(existing.options.priority == options.priority) {
            "KEEP hook priority differs for $member; remove explicitly or restart"
        }
        platform.logger.log(
            LogLevel.DEBUG,
            "Reusing KEEP hook for $member; original callbacks and error policy remain installed",
            null
        )
        return existing
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
        return HookHandle(this, native, options).also {
            handles += it
            if (options.hotReloadPolicy == HotReloadPolicy.KEEP) {
                val key = KeepKey(native.member, options.id)
                check(
                    keepSlots.putIfAbsent(
                        key,
                        it
                    ) == null
                ) { "Duplicate KEEP registration for ${native.member}" }
            }
        }
    }

    internal fun forget(handle: HookHandle) {
        synchronized(lock) {
            handles.remove(handle)
            if (handle.hotReloadPolicy == HotReloadPolicy.KEEP) {
                keepSlots.remove(KeepKey(handle.member, handle.id), handle)
            }
        }
    }
    internal fun ensureOpen() {
        check(state == State.ACTIVE) { "RoxyRuntime is not active: $state" }
    }
    override fun close() {
        val (ownedHooks, ownedResources) = synchronized(lock) {
            // Retirement owns cleanup until the handoff completes, including nested close calls.
            if (isRetiring || (state == State.CLOSED && handles.isEmpty())) return
            state = State.CLOSED
            (handles.toList() to resources.toList()).also {
                handles.clear()
                keepSlots.clear()
                resources.clear()
                hookers.clear()
                services.clear()
            }
        }
        RoxyHook.unregister(this)
        try {
            closeEvery((ownedHooks + ownedResources).asReversed())
        } finally {
            synchronized(lock) {
                // A native removal that throws has not relinquished ownership; permit retry.
                handles.addAll(ownedHooks.filter { it.state == HookHandle.State.ACTIVE })
            }
        }
    }
}
