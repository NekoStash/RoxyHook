package hk.uwu.roxyhook.platform.libxposed.channel

import hk.uwu.roxyhook.prefs.stringPreference

internal object ChannelPreferences {
    const val GROUP = "roxy.channel.v1"
    val secret = stringPreference("secret")
}
