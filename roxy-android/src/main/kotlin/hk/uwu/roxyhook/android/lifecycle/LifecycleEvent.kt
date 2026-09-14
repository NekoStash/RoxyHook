package hk.uwu.roxyhook.android.lifecycle

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ContentProvider
import android.content.Context
import android.os.Bundle

enum class LifecyclePhase { BEFORE, AFTER }
enum class LifecycleKind {
    APPLICATION_ATTACH, APPLICATION_ATTACH_BASE_CONTEXT, APPLICATION_CREATE,
    APPLICATION_TERMINATE, APPLICATION_LOW_MEMORY, APPLICATION_TRIM_MEMORY, APPLICATION_CONFIGURATION_CHANGED,
    ACTIVITY_CREATE, ACTIVITY_START, ACTIVITY_RESUME, ACTIVITY_PAUSE, ACTIVITY_STOP, ACTIVITY_DESTROY,
    ACTIVITY_SAVE_INSTANCE_STATE, ACTIVITY_NEW_INTENT, ACTIVITY_RESULT,
    SERVICE_CREATE, SERVICE_DESTROY, SERVICE_START_COMMAND, SERVICE_BIND, SERVICE_UNBIND,
    PROVIDER_ATTACH, PROVIDER_CREATE, PROVIDER_SHUTDOWN
}
/** Invocation snapshot; nested Android objects may be mutable. Do not retain Activity/Service events. */
data class LifecycleEvent(
    val kind: LifecycleKind,
    val phase: LifecyclePhase,
    val instance: Any,
    val context: Context,
    val arguments: List<Any?>,
    val throwable: Throwable? = null,
    val sequence: Long = 0,
    val packageName: String = context.packageName
) {
    val application: Application get() = instance as Application
    val activity: Activity get() = instance as Activity
    val service: Service get() = instance as Service
    val provider: ContentProvider get() = instance as ContentProvider
    val savedInstanceState: Bundle? get() = arguments.firstOrNull() as? Bundle
    val isSuccessful: Boolean get() = throwable == null
}
