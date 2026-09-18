package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.HookPlatform
import hk.uwu.roxyhook.platform.PlatformInfo
import java.lang.ref.WeakReference

/** Status is weakly observed; hook operations always use an explicit runtime. */
object RoxyHook {
    const val VERSION = "0.3.0"
    private val lock = Any()
    private val runtimes = mutableListOf<WeakReference<RoxyRuntime>>()
    internal fun register(runtime: RoxyRuntime) = synchronized(lock) {
        runtimes.removeAll { it.get() == null }
        runtimes.add(WeakReference(runtime))
        Unit
    }
    internal fun unregister(runtime: RoxyRuntime) = synchronized(lock) {
        runtimes.removeAll { it.get() == null || it.get() === runtime }
        Unit
    }
    /** Latest registered open runtime bound to an injected framework; used by status and RLog routing. */
    internal fun injected(): RoxyRuntime? = synchronized(lock) {
        var latest: RoxyRuntime? = null
        val iterator = runtimes.iterator()
        while (iterator.hasNext()) {
            val runtime = iterator.next().get()
            if (runtime == null) {
                iterator.remove()
            } else if (!runtime.isClosed && runtime.platform.info.isInjected) {
                latest = runtime
            }
        }
        latest
    }
    /** Injected-process status only; use RoxyServices in the module's own UI process. */
    val isActive: Boolean get() = injected() != null
    val framework: PlatformInfo? get() = injected()?.platform?.info
    val apiVersion: Int? get() = framework?.apiVersion
    fun encase(platform: HookPlatform, context: PackageContext, config: RoxyConfig = RoxyConfig(),
               block: PackageScope.() -> Unit): RoxyRuntime {
        val runtime = RoxyRuntime(platform, config)
        try { runtime.scope(context).block(); return runtime }
        catch (error: Throwable) {
            try { runtime.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }
}
