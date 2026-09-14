package hk.uwu.roxyhook.platform

import java.lang.reflect.Executable

data class HookOptions(val priority: Int = 50, val id: String? = null) {
    init { require(id == null || id.isNotBlank()) { "Hook id must not be blank" } }
}
