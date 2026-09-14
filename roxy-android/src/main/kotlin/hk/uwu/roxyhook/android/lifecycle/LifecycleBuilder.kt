package hk.uwu.roxyhook.android.lifecycle

import hk.uwu.roxyhook.RoxyDsl
import hk.uwu.roxyhook.prefs.Subscription

@RoxyDsl
class LifecycleBuilder internal constructor(private val registry: LifecycleRegistry, private val packageName: String) {
    private val subscriptions = mutableListOf<Subscription>()
    fun on(kind: LifecycleKind, phase: LifecyclePhase = LifecyclePhase.AFTER, replay: Boolean = false,
           successfulOnly: Boolean = true, block: LifecycleEvent.() -> Unit) {
        subscriptions += registry.subscribe(packageName, kind, phase, replay, successfulOnly, block)
    }
    fun onAttach(before: Boolean = false, replay: Boolean = true, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.APPLICATION_ATTACH, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, replay, block = block)
    fun attachBaseContext(isAfter: Boolean = true, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.APPLICATION_ATTACH_BASE_CONTEXT, if (isAfter) LifecyclePhase.AFTER else LifecyclePhase.BEFORE, block = block)
    fun onCreate(before: Boolean = false, replay: Boolean = true, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.APPLICATION_CREATE, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, replay, block = block)
    fun onTerminate(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.APPLICATION_TERMINATE, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onLowMemory(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.APPLICATION_LOW_MEMORY, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onTrimMemory(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.APPLICATION_TRIM_MEMORY, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onConfigurationChanged(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.APPLICATION_CONFIGURATION_CHANGED, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivityCreate(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_CREATE, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivityStart(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_START, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivityResume(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_RESUME, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivityPause(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_PAUSE, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivityStop(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_STOP, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivityDestroy(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_DESTROY, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivitySaveInstanceState(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_SAVE_INSTANCE_STATE, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivityNewIntent(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_NEW_INTENT, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onActivityResult(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.ACTIVITY_RESULT, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onServiceCreate(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.SERVICE_CREATE, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onServiceDestroy(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.SERVICE_DESTROY, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onServiceStartCommand(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.SERVICE_START_COMMAND, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onServiceBind(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.SERVICE_BIND, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onServiceUnbind(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.SERVICE_UNBIND, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onProviderAttach(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.PROVIDER_ATTACH, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onProviderCreate(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.PROVIDER_CREATE, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    fun onProviderShutdown(before: Boolean = false, block: LifecycleEvent.() -> Unit) =
        on(LifecycleKind.PROVIDER_SHUTDOWN, if (before) LifecyclePhase.BEFORE else LifecyclePhase.AFTER, block = block)
    internal fun finish(): Subscription {
        val snapshot = subscriptions.toList()
        return Subscription.once { snapshot.asReversed().forEach { it.close() } }
    }
    internal fun cancel() { subscriptions.asReversed().forEach { it.close() }; subscriptions.clear() }
}
