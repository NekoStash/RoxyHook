package hk.uwu.roxyhook.sample.module

import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyModule
import hk.uwu.roxyhook.annotation.RoxyEntry
import hk.uwu.roxyhook.android.systemContext
import hk.uwu.roxyhook.platform.LogLevel

/** KSP generates the native no-argument entry and java_init.list. Never edit generated outputs. */
@RoxyEntry
class MainModule : RoxyModule() {
    override fun PackageScope.onLoad() {
        loadAll { firstPackage { log("App process ready, user=$userId") } }
        loadApp("hk.uwu.roxyhook.sample.target", DemoHooks)
        // Independent of loadAll; runs only if android is in scope. systemContext is a hidden-API
        // read available only inside system_server — its failure is logged, never silently ignored.
        loadSystem {
            runCatching { log("system_server context: ${systemContext.javaClass.name}") }
                .onFailure { log("systemContext unavailable", LogLevel.WARN, it) }
        }
    }
}
