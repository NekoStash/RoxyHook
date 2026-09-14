package hk.uwu.roxyhook

/**
 * Immutable facts about the currently dispatched package event. One instance describes exactly one
 * framework callback; `PackageScope` re-exposes every field. Package events do not imply one
 * application per process, and system-server is a process property rather than a package one.
 *
 * @property packageName package the current event belongs to (`"android"` for system_server).
 * @property processName actual process the event is running in; may differ from [mainProcessName].
 * @property classLoader loader that resolves the target's classes for this event.
 * @property isFirstPackage whether this is the first package event in the current process.
 * @property isSystemServer whether the event was delivered inside the system_server process.
 * @property stage dispatch stage: earlier `PACKAGE_LOADED`, final `PACKAGE_READY` or
 *   `SYSTEM_SERVER_STARTING`. Platforms may only provide a subset.
 * @property mainProcessName the package's declared main process name; basis of [isMainProcess].
 * @property userId numeric Android user id of the current process (multi-user/work profiles).
 * @property modulePackageName the injected module's own package name; null when the platform
 *   cannot report it.
 * @property moduleApkPath absolute path of the module's own APK, reported once by the platform at
 *   module load; null when the platform cannot provide it. Never guessed from package scans.
 * @property platformSnapshot opaque per-event payload owned by the platform adapter. Core never
 *   reads it; the Android layer casts it back to its own [PlatformSnapshot] subclass to recover
 *   event data such as the host `ApplicationInfo`. It carries one event's values only and is not
 *   a cache for arbitrary mutable objects. Module code must not set or interpret it.
 */
data class PackageContext(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val isFirstPackage: Boolean = true,
    val isSystemServer: Boolean = false,
    val stage: LoadStage = if (isSystemServer) LoadStage.SYSTEM_SERVER_STARTING else LoadStage.PACKAGE_READY,
    val mainProcessName: String = packageName,
    val userId: Int = 0,
    val modulePackageName: String? = null,
    val moduleApkPath: String? = null,
    val platformSnapshot: PlatformSnapshot? = null
) {
    /** Whether the current process equals the package's declared main process. */
    val isMainProcess: Boolean get() = processName == mainProcessName
}

/**
 * Type-erased carrier for platform data attached to a single [PackageContext] event. Defined in
 * core so `roxy-core` never imports Android types; platform/extension layers provide concrete
 * subclasses and are the only intended readers. Implementations must be immutable and scoped to
 * one event: adapters snapshot the callback's data instead of retaining mutable framework objects
 * for later events. Module code must not implement, set or interpret this type.
 */
abstract class PlatformSnapshot protected constructor()

/** Dispatch stage of a package event, ordered from earliest to latest availability. */
enum class LoadStage { PACKAGE_LOADED, PACKAGE_READY, SYSTEM_SERVER_STARTING }
