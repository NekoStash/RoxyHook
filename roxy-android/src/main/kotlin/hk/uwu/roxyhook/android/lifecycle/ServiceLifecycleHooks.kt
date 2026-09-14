package hk.uwu.roxyhook.android.lifecycle

import android.app.Service

internal class ServiceLifecycleHooks(private val hooks: LifecycleHookInstaller) {
    fun install() {
        hooks.root(Service::class.java.declaredMethods.filter { it.name == "attach" }, "service.bind") { service ->
            mapOf("onCreate" to LifecycleKind.SERVICE_CREATE, "onDestroy" to LifecycleKind.SERVICE_DESTROY,
                "onStartCommand" to LifecycleKind.SERVICE_START_COMMAND, "onBind" to LifecycleKind.SERVICE_BIND,
                "onUnbind" to LifecycleKind.SERVICE_UNBIND).forEach { (name, kind) ->
                hooks.virtual(service.javaClass, Service::class.java, name, kind)
            }
        }
    }
}
