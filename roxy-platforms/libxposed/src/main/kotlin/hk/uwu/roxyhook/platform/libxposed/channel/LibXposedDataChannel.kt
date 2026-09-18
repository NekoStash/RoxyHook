package hk.uwu.roxyhook.platform.libxposed.channel

import android.content.Context
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.android.channel.DataChannel
import hk.uwu.roxyhook.android.lifecycle.appContext
import hk.uwu.roxyhook.platform.libxposed.service.LibXposedService

/** MODULE APP: caller owns this receiver and must close it with the UI/service lifecycle. */
fun LibXposedService.dataChannel(context: Context): DataChannel {
    return DataChannel(context, context.packageName)
}
/** HOOKED APP: use after attach, never in system_server. Runtime owns the receiver for this generation. */
fun PackageScope.dataChannel(context: Context): DataChannel {
    check(!isSystemServer && context.packageName == packageName) { "DataChannel requires the matching attached app context" }
    val module = checkNotNull(modulePackageName) { "Platform did not supply the module package name" }
    val channel = DataChannel(context, module, runtime.platform.logger)
    try {
        return runtime.manage(channel)
    } catch (error: Throwable) {
        try {
            channel.close()
        } catch (cleanup: Throwable) {
            if (cleanup !== error) error.addSuppressed(cleanup)
        }
        throw error
    }
}
/**
 * HOOKED APP: ambient [DataChannel] resolved against this scope's attached [appContext].
 * The app must have attached (use inside `onAttach`/`onCreate` or later); in system_server or
 * before attach this fails fast instead of falling back to another context. The runtime still
 * owns the returned receiver. Use `dataChannel(context)` when an explicit host context is needed.
 */
val PackageScope.dataChannel: DataChannel
    get() = dataChannel(checkNotNull(appContext) {
        "DataChannel needs the attached application context; read it inside a lifecycle callback"
    })
