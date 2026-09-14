package hk.uwu.roxyhook.platform

import hk.uwu.roxyhook.prefs.Preferences
import java.io.InputStream
import java.lang.reflect.Executable

/** The only interface an executable-hooking platform needs to implement. No Android types. */
interface HookPlatform {
    val info: PlatformInfo
    val capabilities: Set<Capability>
    val logger: RoxyLogger

    fun hook(member: Executable, options: HookOptions, interceptor: HookInterceptor): PlatformHook
    /** Invoke this exact executable, bypassing every hook. Unwrap InvocationTargetException. */
    fun invokeOriginal(member: Executable, receiver: Any?, arguments: Array<Any?>): Any?

    fun deoptimize(member: Executable): Boolean = unsupported(Capability.DEOPTIMIZATION)
    fun hookClassInitializer(type: Class<*>, options: HookOptions, interceptor: HookInterceptor): PlatformHook =
        unsupported(Capability.CLASS_INITIALIZER)
    fun preferences(group: String): Preferences = unsupported(Capability.REMOTE_PREFERENCES)
    fun listRemoteFiles(): List<String> = unsupported(Capability.REMOTE_FILES)
    fun openRemoteFile(name: String): InputStream = unsupported(Capability.REMOTE_FILES)

    fun requireCapability(capability: Capability) {
        if (capability !in capabilities) unsupported(capability)
    }
    fun unsupported(capability: Capability): Nothing = throw UnsupportedCapabilityException(info.name, capability)
}
