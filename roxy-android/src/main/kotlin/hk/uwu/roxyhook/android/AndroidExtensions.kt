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
/**
 * system_server's own [Context]. Only meaningful in system_server; reading it in an app process
 * fails immediately. Resolution goes through the runtime-scoped [SystemContextResolver]
 * (production: [HiddenApiSystemContextResolver]); a resolver failure or wrong-typed result throws
 * an [IllegalStateException] carrying the cause — there is no silent fallback.
 */
val PackageScope.systemContext: Context
    get() {
        check(isSystemServer) { "systemContext is only available in system_server" }
        return SystemContextResolver.of(this).resolve(this)
    }
