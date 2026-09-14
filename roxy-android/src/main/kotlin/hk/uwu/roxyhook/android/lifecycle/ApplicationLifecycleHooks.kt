package hk.uwu.roxyhook.android.lifecycle

import android.app.Application
import android.content.Context

internal class ApplicationLifecycleHooks(private val hooks: LifecycleHookInstaller) {
    fun install() {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hooks.root(listOf(attach), "application.bind") { application ->
            val type = application.javaClass
            hooks.virtual(type, Application::class.java, "attachBaseContext", LifecycleKind.APPLICATION_ATTACH_BASE_CONTEXT)
            hooks.virtual(type, Application::class.java, "onCreate", LifecycleKind.APPLICATION_CREATE)
            hooks.virtual(type, Application::class.java, "onTerminate", LifecycleKind.APPLICATION_TERMINATE)
            hooks.virtual(type, Application::class.java, "onLowMemory", LifecycleKind.APPLICATION_LOW_MEMORY)
            hooks.virtual(type, Application::class.java, "onTrimMemory", LifecycleKind.APPLICATION_TRIM_MEMORY)
            hooks.virtual(type, Application::class.java, "onConfigurationChanged", LifecycleKind.APPLICATION_CONFIGURATION_CHANGED)
        }
        hooks.virtual(Application::class.java, Application::class.java, "attach", LifecycleKind.APPLICATION_ATTACH)
    }
}
