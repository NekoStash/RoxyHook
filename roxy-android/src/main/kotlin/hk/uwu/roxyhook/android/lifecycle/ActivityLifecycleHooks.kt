package hk.uwu.roxyhook.android.lifecycle

import android.app.Activity

internal class ActivityLifecycleHooks(private val hooks: LifecycleHookInstaller) {
    fun install() {
        hooks.root(Activity::class.java.declaredMethods.filter { it.name == "attach" }, "activity.bind") { activity ->
            val events = mapOf(
                "onCreate" to LifecycleKind.ACTIVITY_CREATE, "onStart" to LifecycleKind.ACTIVITY_START,
                "onResume" to LifecycleKind.ACTIVITY_RESUME, "onPause" to LifecycleKind.ACTIVITY_PAUSE,
                "onStop" to LifecycleKind.ACTIVITY_STOP, "onDestroy" to LifecycleKind.ACTIVITY_DESTROY,
                "onSaveInstanceState" to LifecycleKind.ACTIVITY_SAVE_INSTANCE_STATE,
                "onNewIntent" to LifecycleKind.ACTIVITY_NEW_INTENT, "onActivityResult" to LifecycleKind.ACTIVITY_RESULT
            )
            events.forEach { (name, kind) -> hooks.virtual(activity.javaClass, Activity::class.java, name, kind) }
        }
    }
}
