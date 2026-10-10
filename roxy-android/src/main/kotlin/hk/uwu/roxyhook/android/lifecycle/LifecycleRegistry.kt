package hk.uwu.roxyhook.android.lifecycle

import android.app.Application
import android.content.Context
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.RuntimeKey
import hk.uwu.roxyhook.prefs.Subscription
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** One dispatcher per module generation. App context and replay state are never process-global. */
class LifecycleRegistry private constructor(private val runtime: RoxyRuntime) : AutoCloseable {
    private val lock = Any()
    private val clock = AtomicLong()
    private val listeners = CopyOnWriteArrayList<LifecycleListener>()
    private val replay = mutableMapOf<Pair<String, LifecycleKind>, LifecycleEvent>()
    @Volatile private var closed = false
    internal val hooks = LifecycleHookInstaller(runtime, this)

    internal fun publish(event: LifecycleEvent) {
        if (closed) return
        val (stamped, recipients) = synchronized(lock) {
            if (closed) return
            val current = event.copy(sequence = clock.incrementAndGet())
            if (current.phase == LifecyclePhase.AFTER && current.isSuccessful &&
                (current.kind == LifecycleKind.APPLICATION_ATTACH || current.kind == LifecycleKind.APPLICATION_CREATE)) {
                replay[current.packageName to current.kind] = current
            }
            // Snapshot recipients in the same critical section as replay state. A new subscriber
            // receives this occurrence either live OR by replay, never both.
            current to listeners.toList()
        }
        recipients.forEach { it.emit(stamped) }
    }
    fun application(packageName: String): Application? = synchronized(lock) {
        replay[packageName to LifecycleKind.APPLICATION_ATTACH]?.instance as? Application
    }

    /** Actual ApplicationContext; attach can finish before LoadedApk installs its Application. */
    fun appContext(packageName: String): Context? = application(packageName)?.applicationContext

    /**
     * Restore the attached Application of an already-running process into a new runtime generation.
     * Only the stable attach state is restored; Activity, Service, and transient callbacks are never
     * synthesized. Returns false when this package already has a newer attach event.
     */
    fun restoreApplication(application: Application): Boolean {
        val packageName = application.packageName
        if (this.application(packageName) != null) return false
        val context = runCatching { application.applicationContext }.getOrNull() ?: application
        publish(
            LifecycleEvent(
                kind = LifecycleKind.APPLICATION_ATTACH,
                phase = LifecyclePhase.AFTER,
                instance = application,
                context = context,
                arguments = emptyList(),
                packageName = packageName,
            )
        )
        return true
    }
    fun subscribe(packageName: String, kind: LifecycleKind, phase: LifecyclePhase = LifecyclePhase.AFTER,
                  replayLatest: Boolean = false, successfulOnly: Boolean = true,
                  callback: LifecycleEvent.() -> Unit): Subscription {
        require(packageName.isNotBlank())
        val listener = LifecycleListener(packageName, kind, phase, successfulOnly, runtime.platform.logger, callback)
        val previous = synchronized(lock) {
            check(!closed) { "Lifecycle registry is closed" }
            listeners += listener
            if (replayLatest && phase == LifecyclePhase.AFTER) replay[packageName to kind] else null
        }
        previous?.let { listener.emit(it, replayed = true) }
        return Subscription.once { listener.close(); listeners -= listener }
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            listeners.forEach { it.close() }
            listeners.clear()
            replay.clear()
        }
        hooks.close()
    }
    companion object {
        private val KEY = RuntimeKey<LifecycleRegistry>("android.lifecycle")
        fun get(runtime: RoxyRuntime): LifecycleRegistry = runtime.service(KEY) {
            LifecycleRegistry(runtime).also { registry ->
                try {
                    ApplicationLifecycleHooks(registry.hooks).install()
                    ActivityLifecycleHooks(registry.hooks).install()
                    ServiceLifecycleHooks(registry.hooks).install()
                    ProviderLifecycleHooks(registry.hooks).install()
                    currentApplication()?.let(registry::restoreApplication)
                    runtime.manage(registry)
                } catch (error: Throwable) {
                    try { registry.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                    throw error
                }
            }
        }

        private fun currentApplication(): Application? {
            val type = runCatching { Class.forName("android.app.ActivityThread") }.getOrNull()
                ?: return null
            runCatching {
                type.getDeclaredMethod("currentApplication")
                    .apply { isAccessible = true }
                    .invoke(null) as? Application
            }.getOrNull()?.let { return it }
            val thread = runCatching {
                type.getDeclaredMethod("currentActivityThread")
                    .apply { isAccessible = true }
                    .invoke(null)
            }.getOrNull() ?: return null
            return runCatching {
                type.getDeclaredMethod("getApplication")
                    .apply { isAccessible = true }
                    .invoke(thread) as? Application
            }.getOrNull()
        }
    }
}
