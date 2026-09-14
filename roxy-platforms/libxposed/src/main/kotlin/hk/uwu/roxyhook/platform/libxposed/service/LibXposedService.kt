package hk.uwu.roxyhook.platform.libxposed.service

import android.os.Bundle
import android.os.ParcelFileDescriptor
import hk.uwu.roxyhook.android.AndroidExecutors
import hk.uwu.roxyhook.android.AndroidMutablePreferences
import hk.uwu.roxyhook.platform.PlatformInfo
import hk.uwu.roxyhook.prefs.MutablePreferences
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import java.util.concurrent.Executor

/** Module-app API, packaged inside roxy-platforms/libxposed. Do not call from injected processes. */
class LibXposedService internal constructor(val native: XposedService) {
    val info get() = PlatformInfo(native.frameworkName, native.frameworkVersion, native.apiVersion)
    val supportsRemoteData get() = (native.frameworkProperties and XposedService.PROP_CAP_REMOTE) != 0L
    val supportsApi102 get() = native.apiVersion >= 102
    fun scope(): List<String> = native.scope.toList()
    fun preferences(group: String = "default"): MutablePreferences {
        check(supportsRemoteData) { "Framework does not provide remote preferences" }
        require(group.isNotBlank())
        return AndroidMutablePreferences(native.getRemotePreferences(group))
    }
    fun requestScope(packages: List<String>, executor: Executor = AndroidExecutors.main,
                     callback: (Result<List<String>>) -> Unit) {
        require(packages.isNotEmpty() && packages.all { it.isNotBlank() })
        native.requestScope(packages.distinct(), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                executor.execute { callback(Result.success(approved.toList())) }
            }
            override fun onScopeRequestFailed(message: String) {
                executor.execute { callback(Result.failure(ScopeRequestException(message))) }
            }
        })
    }
    fun removeScope(packages: List<String>) = native.removeScope(packages)
    fun runningTargets(): List<HookedTarget> {
        check(supportsApi102) { "Running targets require service API 102" }
        return native.runningTargets.toList()
    }
    /** Use for loading new code, not for propagating a changed preference. */
    fun hotReload(target: HookedTarget, extras: Bundle? = null, executor: Executor = AndroidExecutors.main,
                  callback: (HookedTarget, HotReloadResult) -> Unit) {
        check(supportsApi102) { "Hot reload requires service API 102" }
        native.hotReloadModule(target, extras) { requested, result ->
            executor.execute { callback(requested, result) }
        }
    }
    fun listRemoteFiles(): List<String> = native.listRemoteFiles().toList()
    /** Caller owns and must close the returned descriptor. */
    fun openRemoteFile(name: String): ParcelFileDescriptor {
        requireFileName(name)
        return native.openRemoteFile(name)
    }
    fun deleteRemoteFile(name: String): Boolean { requireFileName(name); return native.deleteRemoteFile(name) }
    fun deletePreferences(group: String) { require(group.isNotBlank()); native.deleteRemotePreferences(group) }
}
class ScopeRequestException(message: String) : RuntimeException(message)
private fun requireFileName(name: String) {
    require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) {
        "Remote files require a plain filename"
    }
}
