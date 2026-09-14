package hk.uwu.roxyhook.platform.libxposed

import hk.uwu.roxyhook.ProcessContext
import hk.uwu.roxyhook.RoxyRuntime
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam

/** Implement on the @RoxyEntry module to opt in. Rejected by default; there is no lifecycle replay. */
interface LibXposedHotReload {
    /** Stop unmanaged threads/native callbacks here. False leaves this generation running. */
    fun prepareHotReload(runtime: RoxyRuntime, param: HotReloadingParam): Boolean
    /** Old handles have been removed. Explicitly install new hooks; never reuse old module objects. */
    fun installAfterHotReload(runtime: RoxyRuntime, process: ProcessContext, param: HotReloadedParam)
}
