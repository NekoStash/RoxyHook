package hk.uwu.roxyhook.platform.libxposed.service

import android.util.Log
import hk.uwu.roxyhook.android.AndroidExecutors
import hk.uwu.roxyhook.prefs.Subscription
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One native listener per process and many observers. Close subscriptions to release UI callbacks. */
object RoxyServices {
    private val startLock = Any()
    private val stateLock = Any()
    private var started = false
    private val connected = IdentityHashMap<XposedService, LibXposedService>()
    private var revision = 0L
    private val observers = CopyOnWriteArrayList<Observer>()

    fun start() = synchronized(startLock) {
        if (started) return@synchronized
        // The native helper may synchronously deliver cached binders during registration.
        started = true
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) = change(service, true)
                override fun onServiceDied(service: XposedService) = change(service, false)
            })
        } catch (error: Throwable) { started = false; throw error }
    }
    fun current(): List<LibXposedService> = synchronized(stateLock) { connected.values.toList() }
    /** Defaults to the main thread. A custom executor should be serial when UI ordering matters. */
    fun observe(executor: Executor = AndroidExecutors.main,
                listener: (List<LibXposedService>) -> Unit): Subscription {
        val observer = Observer(executor, listener)
        observers += observer
        try {
            start()
            val snapshot = synchronized(stateLock) { revision to connected.values.toList() }
            observer.emit(snapshot.first, snapshot.second)
        } catch (error: Throwable) { observer.active.set(false); observers -= observer; throw error }
        return Subscription.once { observer.active.set(false); observers -= observer }
    }
    private fun change(service: XposedService, bind: Boolean) {
        val snapshot = synchronized(stateLock) {
            if (bind) connected[service] = connected[service] ?: LibXposedService(service)
            else connected.remove(service)
            ++revision to connected.values.toList()
        }
        observers.forEach { it.emit(snapshot.first, snapshot.second) }
    }
    private class Observer(val executor: Executor, val callback: (List<LibXposedService>) -> Unit) {
        val active = AtomicBoolean(true)
        private val delivered = AtomicLong(-1)
        fun emit(version: Long, snapshot: List<LibXposedService>) {
            try {
                executor.execute {
                    if (!active.get()) return@execute
                    while (true) {
                        val previous = delivered.get()
                        if (version <= previous) return@execute
                        if (delivered.compareAndSet(previous, version)) break
                    }
                    if (active.get()) {
                        try { callback(snapshot) }
                        catch (error: Exception) { Log.e("RoxyHook", "Service observer failed", error) }
                    }
                }
            } catch (error: Exception) { Log.e("RoxyHook", "Service observer executor rejected callback", error) }
        }
    }
}
