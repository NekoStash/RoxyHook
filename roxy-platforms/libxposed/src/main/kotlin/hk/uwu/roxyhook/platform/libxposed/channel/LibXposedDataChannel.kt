package hk.uwu.roxyhook.platform.libxposed.channel

import android.content.Context
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.android.channel.DataChannel
import hk.uwu.roxyhook.channel.ChannelAuthenticator
import hk.uwu.roxyhook.platform.libxposed.service.LibXposedService

/** MODULE APP: initialize once, off the UI thread. Commit is blocking; do not rotate while peers run.
 * Synchronization covers this process only: provision from one designated module-app process. */
fun LibXposedService.provisionDataChannelKey(): Unit = synchronized(ChannelPreferences) {
    val preferences = preferences(ChannelPreferences.GROUP)
    if (preferences.contains(ChannelPreferences.secret.name)) {
        ChannelAuthenticator.secretFromHex(preferences[ChannelPreferences.secret]).fill(0)
    } else {
        val key = ChannelAuthenticator.newSecret()
        try {
            check(preferences.editAndCommit { this[ChannelPreferences.secret] = ChannelAuthenticator.secretToHex(key) }) {
                "Failed to persist DataChannel key"
            }
        } finally { key.fill(0) }
    }
}
/** MODULE APP: provision first. Caller owns this receiver and must close it with the UI/service lifecycle. */
fun LibXposedService.dataChannel(context: Context): DataChannel {
    val key = ChannelAuthenticator.secretFromHex(preferences(ChannelPreferences.GROUP)[ChannelPreferences.secret])
    try { return DataChannel(context, context.packageName, key) } finally { key.fill(0) }
}
/** HOOKED APP: use after attach, never in system_server. Runtime owns the receiver for this generation. */
fun PackageScope.dataChannel(context: Context): DataChannel {
    check(!isSystemServer && context.packageName == packageName) { "DataChannel requires the matching attached app context" }
    val module = checkNotNull(modulePackageName) { "Platform did not supply the module package name" }
    val key = ChannelAuthenticator.secretFromHex(prefs(ChannelPreferences.GROUP)[ChannelPreferences.secret])
    try {
        val channel = DataChannel(context, module, key, runtime.platform.logger)
        try { return runtime.manage(channel) } catch (error: Throwable) {
            try { channel.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    } finally { key.fill(0) }
}
