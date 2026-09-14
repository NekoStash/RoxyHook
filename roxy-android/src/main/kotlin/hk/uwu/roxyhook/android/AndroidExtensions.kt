package hk.uwu.roxyhook.android

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import hk.uwu.roxyhook.prefs.Subscription
import java.util.concurrent.Executor

object AndroidExecutors {
    val main: Executor by lazy {
        val handler = Handler(Looper.getMainLooper())
        Executor { action -> check(handler.post(action)) { "Main looper is shutting down" } }
    }
}
fun PackageScope.onApplicationCreate(block: (Application) -> Unit): Subscription = lifecycle {
    onCreate(replay = true) { block(application) }
}
/** system_server has no Application lifecycle. This queries the initialized ActivityThread explicitly. */
fun PackageScope.systemContext(): Context {
    check(isSystemServer) { "systemContext() is only available in system_server" }
    val type = Class.forName("android.app.ActivityThread", false, appClassLoader)
    val current = type.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
    checkNotNull(current) { "ActivityThread is not initialized" }
    return type.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(current) as Context
}
