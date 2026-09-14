package hk.uwu.roxyhook.android.lifecycle

import hk.uwu.roxyhook.platform.LogLevel
import hk.uwu.roxyhook.platform.RoxyLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Delivery is synchronous on the intercepted thread. Never queue a BEFORE event past its method body. */
internal class LifecycleListener(private val packageName: String, private val kind: LifecycleKind,
                                 private val phase: LifecyclePhase, private val successfulOnly: Boolean,
                                 private val logger: RoxyLogger, private val callback: LifecycleEvent.() -> Unit) {
    private val active = AtomicBoolean(true)
    private val newestLive = AtomicLong()
    fun emit(event: LifecycleEvent, replayed: Boolean = false) {
        if (!active.get() || event.packageName != packageName || event.kind != kind || event.phase != phase ||
            (successfulOnly && !event.isSuccessful)) return
        if (replayed && newestLive.get() >= event.sequence) return
        if (!replayed) newestLive.accumulateAndGet(event.sequence, ::maxOf)
        // No library lock is held around user code. Reentrant callbacks keep their BEFORE/AFTER timing.
        // Replay runs on the registering thread; different threads have no promised total order.
        try { callback(event) } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            try { logger.log(LogLevel.ERROR, "Lifecycle callback failed: ${event.kind}/${event.phase}", error) }
            catch (logging: Throwable) { if (logging is VirtualMachineError || logging is ThreadDeath) throw logging }
        }
    }
    fun close() { active.set(false) }
}
