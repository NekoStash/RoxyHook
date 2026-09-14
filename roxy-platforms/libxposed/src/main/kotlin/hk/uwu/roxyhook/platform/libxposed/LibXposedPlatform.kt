package hk.uwu.roxyhook.platform.libxposed

import android.os.ParcelFileDescriptor
import android.util.Log
import hk.uwu.roxyhook.android.AndroidPreferences
import hk.uwu.roxyhook.platform.*
import hk.uwu.roxyhook.prefs.Preferences
import io.github.libxposed.api.XposedInterface
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Direct API-102 integration. No reflection into the Xposed API and no legacy XposedBridge. */
class LibXposedPlatform(val api: XposedInterface, tag: String = "RoxyHook") : HookPlatform {
    init { require(api.apiVersion >= 102) { "RoxyHook requires LibXposed API 102 or newer" } }
    override val info: PlatformInfo get() = PlatformInfo(api.frameworkName, api.frameworkVersion, api.apiVersion, isInjected = true)
    override val capabilities: Set<Capability> get() = buildSet {
        addAll(listOf(Capability.METHOD_HOOK, Capability.CONSTRUCTOR_HOOK, Capability.INVOKE_ORIGINAL, Capability.INTERCEPTOR_CHAIN,
            Capability.ATOMIC_REPLACEMENT, Capability.DEOPTIMIZATION, Capability.CLASS_INITIALIZER))
        if ((api.frameworkProperties and XposedInterface.PROP_CAP_REMOTE) != 0L) {
            add(Capability.REMOTE_PREFERENCES); add(Capability.REMOTE_FILES)
        }
    }
    override val logger = RoxyLogger { level, message, error ->
        val priority = when (level) {
            LogLevel.DEBUG -> Log.DEBUG; LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN; LogLevel.ERROR -> Log.ERROR
        }
        api.log(priority, tag, message, error)
    }
    override fun hook(member: Executable, options: HookOptions, interceptor: HookInterceptor): PlatformHook =
        NativeHook(configure(api.hook(member), options).intercept(interceptor.toNative()))

    override fun hookClassInitializer(type: Class<*>, options: HookOptions, interceptor: HookInterceptor): PlatformHook =
        NativeHook(configure(api.hookClassInitializer(type), options).intercept(interceptor.toNative()))

    private fun configure(builder: XposedInterface.HookBuilder, options: HookOptions): XposedInterface.HookBuilder =
        builder.setPriority(options.priority).setId(options.id)
            // Core handles callback failures. Native protection would swallow explicitly assigned exceptions.
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)

    override fun invokeOriginal(member: Executable, receiver: Any?, arguments: Array<Any?>): Any? = try {
        when (member) {
            is Method -> {
                val invoker = api.getInvoker(member)
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
                if (Modifier.isStatic(member.modifiers)) invoker.invoke(null, *arguments)
                else invoker.invokeSpecial(requireNotNull(receiver), *arguments)
            }
            is Constructor<*> -> {
                val invoker = api.getInvoker(member)
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
                invoker.invokeSpecial(requireNotNull(receiver), *arguments)
            }
            else -> error("Unsupported executable: $member")
        }
    } catch (error: InvocationTargetException) { throw error.targetException }

    override fun deoptimize(member: Executable): Boolean = api.deoptimize(member)
    override fun preferences(group: String): Preferences {
        requireCapability(Capability.REMOTE_PREFERENCES)
        require(group.isNotBlank())
        return AndroidPreferences(api.getRemotePreferences(group))
    }
    override fun listRemoteFiles(): List<String> {
        requireCapability(Capability.REMOTE_FILES)
        return api.listRemoteFiles().toList()
    }
    override fun openRemoteFile(name: String): InputStream {
        requireCapability(Capability.REMOTE_FILES)
        validateRemoteName(name)
        return ParcelFileDescriptor.AutoCloseInputStream(api.openRemoteFile(name))
    }
    private class NativeHook(private val native: XposedInterface.HookHandle) : PlatformHook {
        override val member: Executable get() = native.executable
        override val id: String? get() = native.id
        override fun unhook() = native.unhook()
        override fun replace(interceptor: HookInterceptor): PlatformHook = NativeHook(native.replaceHook(interceptor.toNative()))
    }
}
private fun HookInterceptor.toNative() = XposedInterface.Hooker { chain ->
    intercept(object : HookCall {
        override val member: Executable get() = chain.executable
        override val receiver: Any? get() = chain.thisObject
        override val arguments: List<Any?> get() = chain.args
        override fun proceed(arguments: Array<Any?>): Any? = chain.proceed(arguments)
    })
}
private fun validateRemoteName(name: String) {
    require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) {
        "Remote files require a plain filename, not a path"
    }
}
