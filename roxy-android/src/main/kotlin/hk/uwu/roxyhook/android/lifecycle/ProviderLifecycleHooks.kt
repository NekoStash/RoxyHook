package hk.uwu.roxyhook.android.lifecycle

import android.content.ContentProvider

internal class ProviderLifecycleHooks(private val hooks: LifecycleHookInstaller) {
    fun install() {
        // Only inspect the runtime class after the base constructor. Never call virtual provider code here.
        hooks.root(ContentProvider::class.java.declaredConstructors.toList(), "provider.bind", afterConstruction = true) { provider ->
            hooks.virtual(provider.javaClass, ContentProvider::class.java, "attachInfo", LifecycleKind.PROVIDER_ATTACH)
            hooks.virtual(provider.javaClass, ContentProvider::class.java, "onCreate", LifecycleKind.PROVIDER_CREATE)
            hooks.virtual(provider.javaClass, ContentProvider::class.java, "shutdown", LifecycleKind.PROVIDER_SHUTDOWN)
        }
    }
}
