package hk.uwu.roxyhook.platform

import java.lang.reflect.Executable

data class PlatformInfo(val name: String, val version: String, val apiVersion: Int, val isInjected: Boolean = false)
