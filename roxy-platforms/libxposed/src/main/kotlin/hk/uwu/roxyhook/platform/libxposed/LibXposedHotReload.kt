package hk.uwu.roxyhook.platform.libxposed

import hk.uwu.roxyhook.ProcessContext
import hk.uwu.roxyhook.RoxyRuntime
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam

/** Implement on the @RoxyEntry module to opt in. Rejected by default; there is no lifecycle replay. */
interface LibXposedHotReload {
    /** Stop unmanaged threads/native callbacks here. False leaves this generation running. */
    fun prepareHotReload(runtime: RoxyRuntime, param: HotReloadingParam): Boolean

    /**
     * Ordinary old handles have been removed; KEEP handles are owned by this runtime without
     * replacing their original callbacks. Explicitly replay/install hooks; KEEP declarations reuse
     * those handles. The original parameter and saved state remain business-owned and unchanged.
     * Never reuse old module objects. Continuous KEEP needs framework enumeration of all surviving
     * earlier-generation hooks; API 102 alone does not certify that multi-generation behavior.
     */
    fun installAfterHotReload(runtime: RoxyRuntime, process: ProcessContext, param: HotReloadedParam)
}
