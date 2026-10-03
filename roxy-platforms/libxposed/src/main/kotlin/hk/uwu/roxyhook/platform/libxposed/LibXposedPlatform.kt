package hk.uwu.roxyhook.platform.libxposed

import android.os.ParcelFileDescriptor
import android.util.Log
import com.highcapable.kavaref.extension.isStatic
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.android.AndroidPreferences
import hk.uwu.roxyhook.platform.Capability
import hk.uwu.roxyhook.platform.HookCall
import hk.uwu.roxyhook.platform.HookInterceptor
import hk.uwu.roxyhook.platform.HookOptions
import hk.uwu.roxyhook.platform.HookPlatform
import hk.uwu.roxyhook.platform.LogLevel
import hk.uwu.roxyhook.platform.PlatformHook
import hk.uwu.roxyhook.platform.PlatformInfo
import hk.uwu.roxyhook.platform.RetainedHook
import hk.uwu.roxyhook.platform.RoxyLogger
import hk.uwu.roxyhook.prefs.Preferences
import io.github.libxposed.api.XposedInterface
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Direct API-102 integration. No reflection into the Xposed API and no legacy XposedBridge. */
class LibXposedPlatform(val api: XposedInterface, tag: String = "RoxyHook") : HookPlatform {
    init { require(api.apiVersion >= 102) { "RoxyHook requires LibXposed API 102 or newer" } }
    override val info: PlatformInfo get() = PlatformInfo(api.frameworkName, api.frameworkVersion, api.apiVersion, isInjected = true)
    override val capabilities: Set<Capability> get() = buildSet {
        addAll(listOf(Capability.METHOD_HOOK, Capability.CONSTRUCTOR_HOOK, Capability.INVOKE_ORIGINAL, Capability.INTERCEPTOR_CHAIN,
            Capability.ATOMIC_REPLACEMENT,
            Capability.DEOPTIMIZATION,
            Capability.CLASS_INITIALIZER,
            Capability.HOT_RELOAD_KEEP
        )
        )
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
    override fun hook(
        member: Executable,
        options: HookOptions,
        interceptor: HookInterceptor
    ): PlatformHook {
        val nativeId = nativeId(options) // Validate before touching the native builder.
        return NativeHook(
            configure(
                api.hook(member),
                options,
                nativeId
            ).intercept(interceptor.toNative()), options
        )
    }

    override fun hookClassInitializer(
        type: Class<*>,
        options: HookOptions,
        interceptor: HookInterceptor
    ): PlatformHook {
        require(options.hotReloadPolicy != HotReloadPolicy.KEEP) { "KEEP class initializers are not supported" }
        val nativeId = nativeId(options)
        return NativeHook(
            configure(api.hookClassInitializer(type), options, nativeId).intercept(
                interceptor.toNative()
            ), options
        )
    }

    override fun validateHookOptions(options: HookOptions) {
        nativeId(options)
    }

    private fun nativeId(options: HookOptions): String? {
        require(options.id?.startsWith(KeepHookId.RESERVED_PREFIX) != true) { "Hook id uses the reserved KEEP prefix" }
        return if (options.hotReloadPolicy == HotReloadPolicy.KEEP) KeepHookId.encode(
            options.priority,
            options.id
        )
        else options.id
    }

    private fun configure(
        builder: XposedInterface.HookBuilder,
        options: HookOptions,
        nativeId: String?
    ): XposedInterface.HookBuilder =
        builder.setPriority(options.priority).setId(nativeId)
            // Core handles callback failures. Native protection would swallow explicitly assigned exceptions.
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)

    override fun invokeOriginal(member: Executable, receiver: Any?, arguments: Array<Any?>): Any? = try {
        when (member) {
            is Method -> {
                val invoker = api.getInvoker(member)
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN)
                if (member.isStatic) invoker.invoke(null, *arguments)
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
    private class NativeHook(
        private val native: XposedInterface.HookHandle,
        private val options: HookOptions,
    ) : PlatformHook {
        override val member: Executable get() = native.executable
        override val id: String? get() = options.id
        override fun unhook() = native.unhook()
        override fun replace(interceptor: HookInterceptor): PlatformHook {
            check(options.hotReloadPolicy != HotReloadPolicy.KEEP) { "KEEP hooks cannot be replaced" }
            return NativeHook(native.replaceHook(interceptor.toNative()), options)
        }
    }

    /** Parse the complete list before ownership changes. The original list stays available to replay. */
    internal fun adoptOldHooks(
        runtime: RoxyRuntime,
        handles: List<XposedInterface.HookHandle>,
        install: () -> Unit
    ) {
        try {
            val retained = ArrayList<RetainedHook>()
            val ordinary = ArrayList<XposedInterface.HookHandle>()
            handles.forEach { handle ->
                val identity = KeepHookId.decode(handle.id)
                if (identity == null) ordinary += handle else {
                    val options =
                        HookOptions(identity.priority, identity.userId, HotReloadPolicy.KEEP)
                    retained += RetainedHook(NativeHook(handle, options), options)
                }
            }
            runtime.adoptKeptHooks(retained)
            removeNativeHooks(ordinary)
            install()
        } catch (error: Throwable) {
            try {
                runtime.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            removeNativeHooks(handles, error)
            throw error
        }
    }

}

internal fun removeNativeHooks(
    handles: Iterable<XposedInterface.HookHandle>,
    cause: Throwable? = null
) {
    var failure = cause
    handles.forEach { handle ->
        try {
            handle.unhook()
        } catch (error: Throwable) {
            if (failure == null) failure =
                error else if (failure !== error) failure.addSuppressed(error)
        }
    }
    if (cause == null) failure?.let { throw it }
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
