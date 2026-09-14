package hk.uwu.roxyhook.platform.libxposed

import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import androidx.annotation.RequiresApi
import hk.uwu.roxyhook.*
import hk.uwu.roxyhook.android.ApplicationInfoSnapshot
import hk.uwu.roxyhook.android.lifecycle.LifecycleRegistry
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.*

/** API 102 no-argument entry. The processor subclasses this class, not the business module. */
abstract class RoxyXposedModule : XposedModule() {
    /** Null is supported only for hand-written native entries overriding onHook. */
    protected open val module: RoxyModule? = null
    private var currentRuntime: RoxyRuntime? = null
    private lateinit var process: ProcessContext
    protected val roxy: RoxyRuntime get() = checkNotNull(currentRuntime) { "Module is not initialized" }
    protected open fun configuration(): RoxyConfig = module?.configuration() ?: RoxyConfig()
    protected open fun PackageScope.onHook() { module?.let(::loadHooker) }
    protected open fun onRoxyModuleLoaded(param: ModuleLoadedParam) = Unit

    final override fun onModuleLoaded(param: ModuleLoadedParam) {
        initialize(param)
        guarded {
            module?.onModuleLoaded(roxy, process)
            onRoxyModuleLoaded(param)
        }
    }
    private fun initialize(param: ModuleLoadedParam) {
        check(currentRuntime == null) { "Module generation has already been initialized" }
        process = ProcessContext(param.processName, param.isSystemServer,
            moduleApplicationInfo.packageName, moduleApplicationInfo.sourceDir)
        currentRuntime = RoxyRuntime(LibXposedPlatform(this), configuration()).also { runtime ->
            module?.let { business -> runtime.onClose { business.onDispose() } }
        }
    }
    // Mirrors XposedModuleInterface.onPackageLoaded's own @RequiresApi(Q): the framework only
    // invokes this callback on API 29+, so defaultClassLoader (also Q+) is always present here.
    @RequiresApi(Build.VERSION_CODES.Q)
    final override fun onPackageLoaded(param: PackageLoadedParam) = guarded {
        val scope = packageScope(param, param.defaultClassLoader, LoadStage.PACKAGE_LOADED)
        module?.onPackageLoaded(scope)
        onRoxyPackageLoaded(scope, param)
    }
    protected open fun onRoxyPackageLoaded(scope: PackageScope, param: PackageLoadedParam) = Unit

    final override fun onPackageReady(param: PackageReadyParam) = guarded {
        // Keep the process-level system flag: system_server can also load non-android packages.
        val scope = packageScope(param, param.classLoader, LoadStage.PACKAGE_READY)
        if (!process.isSystemServer) LifecycleRegistry.get(roxy)
        with(scope) { onHook() }
    }
    final override fun onSystemServerStarting(param: SystemServerStartingParam) = guarded {
        check(process.isSystemServer) { "System-server event delivered to an app process" }
        // system_server events carry no ApplicationInfo: platformSnapshot stays null.
        val scope = roxy.scope(PackageContext("android", process.processName, param.classLoader,
            isFirstPackage = true, isSystemServer = true, stage = LoadStage.SYSTEM_SERVER_STARTING,
            mainProcessName = process.processName, userId = currentUserId(),
            modulePackageName = process.modulePackageName, moduleApkPath = process.moduleApkPath))
        with(scope) { onHook() }
    }
    private fun packageScope(param: PackageLoadedParam, loader: ClassLoader, stage: LoadStage): PackageScope =
        roxy.scope(PackageContext(param.packageName, process.processName, loader, param.isFirstPackage,
            process.isSystemServer, stage, param.applicationInfo?.processName ?: param.packageName,
            currentUserId(), process.modulePackageName, process.moduleApkPath,
            param.applicationInfo?.let(::ApplicationInfoSnapshot)))

    /**
     * Multi-user / work-profile / isolated-process user id. Verified against this machine's
     * `android-37.0/android.jar`: the SDK exposes no `UserHandle.getUserId(int)` /
     * `getIdentifier()` / `Process.getUserIdForUid(int)` (all `@hide` in AOSP).
     * The only stable, documented encoding of the numeric user id is `UserHandle`'s
     * [android.os.Parcelable] contract: it is persisted and passed across IPC as a single int
     * (see `writeToParcel`/`readFromParcel`). We round-trip through a [Parcel] to read that int
     * instead of relying on `hashCode()`, which is an implementation detail, not a contract.
     */
    private fun currentUserId(): Int {
        val parcel = Parcel.obtain()
        return try {
            UserHandle.writeToParcel(Process.myUserHandle(), parcel)
            parcel.setDataPosition(0)
            parcel.readInt()
        } finally {
            parcel.recycle()
        }
    }

    /** Opt-in is explicit. Closing managed Java resources does not clean up arbitrary JNI/native state. */
    final override fun onHotReloading(param: HotReloadingParam): Boolean {
        val participant = module as? LibXposedHotReload ?: return onRoxyHotReloading(param)
        if (!participant.prepareHotReload(roxy, param)) return false
        roxy.close()
        return true
    }
    protected open fun onRoxyHotReloading(param: HotReloadingParam): Boolean = false
    final override fun onHotReloaded(param: HotReloadedParam) {
        initialize(param)
        guarded {
            removeOldHooks(param)
            val participant = module as? LibXposedHotReload
            if (participant != null) participant.installAfterHotReload(roxy, process, param)
            else onRoxyHotReloaded(param)
        }
    }
    /** No fake onModuleLoaded/onPackageReady replay. Reinstall deliberately using the new classloader. */
    protected open fun onRoxyHotReloaded(param: HotReloadedParam) = Unit
    private fun removeOldHooks(param: HotReloadedParam) {
        var failure: Throwable? = null
        param.oldHookHandles.forEach { handle ->
            try { handle.unhook() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            // A failed entry must not leave a partially installed generation behind.
            try { currentRuntime?.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }
}
