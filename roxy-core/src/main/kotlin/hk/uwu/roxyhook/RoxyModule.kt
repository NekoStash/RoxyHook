package hk.uwu.roxyhook

/** Platform-independent entry used by the generated native module. One instance per generation. */
abstract class RoxyModule : RoxyHooker() {
    open fun configuration(): RoxyConfig = RoxyConfig()
    open fun onModuleLoaded(runtime: RoxyRuntime, process: ProcessContext) = Unit
    /** Earlier than the final AppComponentFactory classloader; opt in deliberately. */
    open fun onPackageLoaded(scope: PackageScope) = Unit
    protected abstract fun PackageScope.onLoad()
    final override fun PackageScope.onHook() { onLoad() }
    open fun onDispose() = Unit
}
/**
 * Immutable facts about the process the module was loaded into, captured once at module load.
 *
 * @property processName actual process name from the module-loaded event.
 * @property isSystemServer whether the module was injected into the system_server process.
 * @property modulePackageName the module's own package name.
 * @property moduleApkPath absolute path of the module's own APK; null when the platform cannot
 *   provide it. Value comes from the load-time module info, never from path guessing.
 */
data class ProcessContext(val processName: String, val isSystemServer: Boolean,
                          val modulePackageName: String, val moduleApkPath: String? = null)
