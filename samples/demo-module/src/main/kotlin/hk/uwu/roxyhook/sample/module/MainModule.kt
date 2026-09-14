package hk.uwu.roxyhook.sample.module

import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyModule
import hk.uwu.roxyhook.annotation.RoxyEntry

/** KSP generates the native no-argument entry and java_init.list. Never edit generated outputs. */
@RoxyEntry
class MainModule : RoxyModule() {
    override fun PackageScope.onLoad() {
        loadAll { firstPackage { log("App process ready, user=$userId") } }
        loadApp("hk.uwu.roxyhook.sample.target", DemoHooks)
        // This is independent of loadAll; it runs only if android is explicitly in the framework scope.
        loadSystem { log("system_server is starting; no Application lifecycle is registered here") }
    }
}
