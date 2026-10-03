package hk.uwu.roxyhook.platform

import hk.uwu.roxyhook.HotReloadPolicy

/** KEEP with a null id uses the per-executable AUTO slot; non-null ids use NAMED slots. */
data class HookOptions @JvmOverloads constructor(
    val priority: Int = 50,
    val id: String? = null,
    val hotReloadPolicy: HotReloadPolicy = HotReloadPolicy.REINSTALL,
) {
    init {
        require(id == null || id.isNotBlank()) { "Hook id must not be blank" }
        require(id == null || !id.startsWith("roxy.keep.")) { "Hook id uses the reserved roxy.keep. namespace" }
    }
}
