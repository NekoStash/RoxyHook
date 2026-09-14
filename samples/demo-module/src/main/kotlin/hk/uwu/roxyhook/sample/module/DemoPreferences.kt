package hk.uwu.roxyhook.sample.module

import hk.uwu.roxyhook.prefs.booleanPreference
import hk.uwu.roxyhook.prefs.stringPreference

object DemoPreferences {
    val enabled = booleanPreference("enabled", true)
    val suffix = stringPreference("suffix", "RoxyHook is active")
    const val GROUP = "demo"
}
