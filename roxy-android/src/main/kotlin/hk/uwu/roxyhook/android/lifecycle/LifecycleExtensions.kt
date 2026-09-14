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
fun PackageScope.onAttach(block: LifecycleEvent.() -> Unit): Subscription = lifecycle { onAttach(block = block) }
fun PackageScope.onCreate(block: LifecycleEvent.() -> Unit): Subscription = lifecycle { onCreate(block = block) }
fun PackageScope.withAppContext(block: Context.() -> Unit): Subscription = lifecycle {
    onAttach(replay = true) { application.block() }
}
