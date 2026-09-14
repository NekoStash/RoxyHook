package hk.uwu.roxyhook.sample.module

import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.appInfo
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import hk.uwu.roxyhook.android.resources.moduleResources
import hk.uwu.roxyhook.platform.Capability
import hk.uwu.roxyhook.platform.LogLevel
import hk.uwu.roxyhook.platform.libxposed.channel.dataChannel
import hk.uwu.roxyhook.platform.libxposed.moduleAppFile

object DemoHooks : RoxyHooker() {
    override fun PackageScope.onHook() {
        val hasRemote = Capability.REMOTE_PREFERENCES in runtime.platform.capabilities
        // Default remote-preferences group; use prefs(group) for a named group.
        val defaults = if (hasRemote) prefs else null
        val remote = if (hasRemote) prefs(DemoPreferences.GROUP) else null
        log("module=${moduleAppFile.name} main=$mainProcessName proc=$processName enabled=${defaults?.get(DemoPreferences.enabled)}")
        val greeting = "hk.uwu.roxyhook.sample.target.Greeting".toClass()
        // KavaRef is the default/transitive resolver. No extra artifact and no kava { } wrapper.
        greeting.resolve().firstMethod { name = "message"; parameters(String::class.java) }.hook {
            id = "demo.greeting"
            onFailure { log("Greeting hook failed at ${it.phase}", LogLevel.ERROR, it.cause) }
            before {
                val enabled = remote?.get(DemoPreferences.enabled) ?: true
                extras["enabled"] = enabled
                if (enabled) args(0).set("RoxyHook")
            }
            after {
                if (extras["enabled"] == true && !hasThrowable)
                    result = "$result — ${remote?.get(DemoPreferences.suffix) ?: DemoPreferences.suffix.default}"
            }
        }
        greeting.resolve().firstMethod { name = "engineLabel"; parameters() }.hook {
            id = "demo.engine-label"
            replace {
                if (remote?.get(DemoPreferences.enabled) != false) "KavaRef + LibXposed API 102" else callOriginal()
            }
        }
        lifecycle {
            attachBaseContext(isAfter = false) { log("Before attachBaseContext: ${context.packageName}") }
            onAttach {
                log("Application attached: ${application.javaClass.name} appInfo.uid=${appInfo?.uid}")
                // Ambient module resources use the attached application context automatically.
                runCatching { log("Module label: ${moduleResources.context.packageName}") }
                try {
                    dataChannel.on("ping") { message -> message.reply("pong from $packageName/$processName") }
                    log("Authenticated DataChannel is listening")
                } catch (error: Exception) {
                    log("DataChannel not provisioned yet. Use Initialize channel in the module app, then restart the target.", LogLevel.INFO)
                }
            }
            onCreate { log("Application.onCreate finished") }
            onActivityResume { log("Activity resumed: ${activity.javaClass.name}") }
        }
        // Deoptimize a known caller, not the hooked callee.
        val caller = "hk.uwu.roxyhook.sample.target.MainActivity".toClass().resolve()
            .firstMethod { name = "render"; parameters() }.self
        if (Capability.DEOPTIMIZATION in runtime.platform.capabilities) caller.deoptimize()
    }
}
