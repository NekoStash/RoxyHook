package hk.uwu.roxyhook.android.lifecycle

import android.app.Application
import android.content.Context
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.prefs.Subscription

private fun PackageScope.registry(): LifecycleRegistry {
    check(!isSystemServer) { "Application lifecycle is not available in system_server; use systemContext()" }
    return LifecycleRegistry.get(runtime)
}
fun PackageScope.lifecycle(block: LifecycleBuilder.() -> Unit): Subscription {
    val builder = LifecycleBuilder(registry(), packageName)
    try { builder.block(); return builder.finish() }
    catch (error: Throwable) { builder.cancel(); throw error }
}
fun PackageScope.onAppLifecycle(block: LifecycleBuilder.() -> Unit): Subscription = lifecycle(block)
val PackageScope.application: Application? get() = registry().application(packageName)
val PackageScope.appContext: Context? get() = registry().appContext(packageName)
/**
 * Host application's [Resources], a read-only convenience for `appContext?.resources`.
 * Same lifecycle semantics as [appContext]: null until its real application context is ready, and reading
 * it in system_server fails just like [appContext]. The host's resources are never mutated.
 */
val PackageScope.appResources: android.content.res.Resources? get() = appContext?.resources
fun PackageScope.onAttach(block: LifecycleEvent.() -> Unit): Subscription = lifecycle { onAttach(block = block) }
fun PackageScope.onCreate(block: LifecycleEvent.() -> Unit): Subscription = lifecycle { onCreate(block = block) }

/** Runs once with a real application context: onCreate on cold start, attach replay on reload. */
fun PackageScope.withAppContext(block: Context.() -> Unit): Subscription {
    val lock = Any()
    var delivered = false
    var closed = false
    fun deliver(application: Application) = synchronized(lock) {
        if (closed || delivered || !runtime.isActive) return@synchronized
        val context = application.applicationContext ?: return@synchronized
        delivered = true
        context.block()
    }

    val subscription = lifecycle {
        onAttach(replay = true) { deliver(application) }
        onCreate(replay = true) { deliver(application) }
    }
    return Subscription.once {
        synchronized(lock) { closed = true }
        subscription.close()
    }
}
