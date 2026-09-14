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
data class ProcessContext(val processName: String, val isSystemServer: Boolean, val modulePackageName: String)
