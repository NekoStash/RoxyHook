package hk.uwu.roxyhook.platform.libxposed

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.kavaref.extension.makeAccessible
import com.highcapable.kavaref.extension.toClass
import hk.uwu.roxyhook.LoadStage
import hk.uwu.roxyhook.PackageContext
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.ProcessContext
import hk.uwu.roxyhook.RLog
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.android.ApplicationInfoSnapshot
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam

/**
 * Opt-in package-scope replay for [LibXposedHotReload]. The framework does not replay package
 * events after a hot reload. Record only delivered contexts, call [prepare] from the old module,
 * then [replay] in the new module to explicitly reinstall hooks. The saved Bundle contains only
 * Android framework values; neither old module objects nor old module ClassLoaders cross generations.
 *
 * A target ClassLoader is recovered from the live Application of the same package, or proven
 * against old hook handles. Unproven contexts are skipped instead of guessing a loader.
 * [namespace] allows a module to preserve its existing Bundle keys when migrating to this helper.
 */
class LibXposedPackageReplay(private val namespace: String = "roxyhook.reload.") {
    init {
        require(namespace.isNotBlank() && namespace.endsWith('.'))
    }

    private val lock = Any()
    private val delivered = linkedSetOf<PackageContext>()

    fun record(context: PackageContext) {
        synchronized(lock) { delivered += context }
    }

    fun record(scope: PackageScope) = record(scope.context)

    /** Return false on serialization failure so the old generation remains installed. */
    fun prepare(param: HotReloadingParam): Boolean = try {
        val contexts = synchronized(lock) { delivered.toList() }
        require(contexts.size <= MAX_CONTEXTS) { "Too many delivered package contexts" }
        val state = Bundle().apply {
            putInt(key("version"), STATE_VERSION)
            putInt(key("count"), contexts.size)
            contexts.forEachIndexed { index, context ->
                putBundle(key("context.$index"), context.toBundle())
            }
        }
        param.setSavedInstanceState(state)
        synchronized(lock) { delivered.clear() }
        true
    } catch (error: Throwable) {
        RLog.error("Package replay preparation failed", error)
        false
    }

    /** Reinstall only events from this process. Returns the number of successfully replayed scopes. */
    fun replay(
        runtime: RoxyRuntime, process: ProcessContext, param: HotReloadedParam,
        install: (PackageScope) -> Unit
    ): Int {
        val contexts = decode(param.savedInstanceState)
        if (contexts == null) {
            RLog.warn("Package replay has no compatible saved state")
            return 0
        }
        val application = if (process.isSystemServer) null else currentApplication()
        var installed = 0
        contexts.filter { it.processName == process.processName && it.isSystemServer == process.isSystemServer }
            .forEach { context ->
                val descriptor = context.reloadTargetDescriptor()
                val loader = resolveReloadClassLoader(descriptor, param.oldHookHandles, application)
                if (loader == null) {
                    RLog.warn("Package replay cannot resolve ClassLoader for ${descriptor.targetId}")
                    return@forEach
                }
                try {
                    val restored = context.toPackageContext(process, loader)
                    record(restored)
                    install(runtime.scope(restored))
                    installed++
                } catch (error: Throwable) {
                    RLog.error(
                        "Package replay failed for ${context.packageName}/${context.processName}",
                        error
                    )
                }
            }
        return installed
    }

    private fun key(name: String) = namespace + name

    private fun currentApplication(): Application? {
        val type = runCatching { "android.app.ActivityThread".toClass() }.getOrNull() ?: return null
        runCatching {
            type.getDeclaredMethod("currentApplication").apply { makeAccessible() }
                .invoke(null) as? Application
        }.getOrNull()?.let { return it }
        val thread = runCatching {
            type.getDeclaredMethod("currentActivityThread").apply { makeAccessible() }
                .invoke(null)
        }.getOrNull() ?: return null
        return runCatching {
            type.getDeclaredMethod("getApplication").apply { makeAccessible() }
                .invoke(thread) as? Application
        }.getOrNull()
    }

    private fun resolveReloadClassLoader(
        descriptor: ReloadTargetDescriptor,
        oldHandles: List<XposedInterface.HookHandle>,
        application: Application?,
    ): ClassLoader? {
        if (!descriptor.isSystemServer &&
            application?.packageName == descriptor.applicationPackageName
        ) {
            application?.let { host ->
                runCatching { host.classLoader }.getOrNull()?.let { return it }
            }
        }

        val declarationsByLoader = LinkedHashMap<ClassLoader, MutableSet<String>>()
        oldHandles.forEach { handle ->
            runCatching { handle.executable.declaringClass }
                .onFailure {
                    RLog.error(
                        "Unable to inspect old HookHandle executable: " +
                            "target=${descriptor.targetId} id=${safeHookId(handle)}",
                        it,
                    )
                }
                .getOrNull()
                ?.let { declaringClass ->
                    val loader = declaringClass.classLoader ?: return@let
                    declarationsByLoader.getOrPut(loader, ::linkedSetOf) += declaringClass.name
                }
        }
        if (declarationsByLoader.isEmpty()) return null

        if (descriptor.isSystemServer) {
            if (declarationsByLoader.size != 1) {
                RLog.error(
                    "Hot reload system_server ClassLoader ownership is ambiguous: " +
                        "target=${descriptor.targetId} candidates=${declarationsByLoader.size}",
                )
                return null
            }
            // The only candidate comes from this target's old handle declaration classes. Never
            // fall back to the module thread context or the system ClassLoader.
            return declarationsByLoader.keys.single()
        }

        val applicationClassName = normalizeApplicationClassName(
            descriptor.applicationPackageName,
            descriptor.applicationClassName,
        )
        val provenCandidates = if (applicationClassName != null) {
            declarationsByLoader.keys.filter { candidate ->
                runCatching { candidate.loadClass(applicationClassName) }
                    .map { applicationClass ->
                        applicationClass.name == applicationClassName &&
                            applicationClass.classLoader === candidate &&
                            applicationClass isSubclassOf classOf<Application>()
                    }
                    .getOrDefault(false)
            }
        } else {
            declarationsByLoader.filterValues { declaringClassNames ->
                declaringClassNames.any { className ->
                    className == descriptor.packageName ||
                        className.startsWith("${descriptor.packageName}.")
                }
            }.keys.toList()
        }
        if (provenCandidates.size != 1) {
            RLog.error(
                "Hot reload application ClassLoader ownership not proven: " +
                    "target=${descriptor.targetId} " +
                    "applicationClass=${applicationClassName ?: "<none>"} " +
                    "candidates=${declarationsByLoader.size} proven=${provenCandidates.size}",
            )
            return null
        }
        return provenCandidates.single()
    }

    private fun safeHookId(handle: XposedInterface.HookHandle): String =
        runCatching { handle.id }.getOrNull()?.takeIf { it.isNotBlank() } ?: "<unknown>"

    private fun normalizeApplicationClassName(
        applicationPackageName: String?, applicationClassName: String?,
    ): String? {
        val packageName = applicationPackageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val className = applicationClassName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            className.startsWith('.') -> packageName + className
            '.' !in className -> "$packageName.$className"
            else -> className
        }
    }

    private fun decode(value: Any?): List<SavedContext>? {
        val state = value as? Bundle ?: return null
        if (state.getInt(key("version"), -1) != STATE_VERSION) return null
        val count = state.getInt(key("count"), -1)
        if (count !in 0..MAX_CONTEXTS) return null
        return buildList(count) {
            repeat(count) { index ->
                val saved = state.getBundle(key("context.$index")) ?: return null
                add(SavedContext.from(saved) ?: return null)
            }
        }
    }

    private data class SavedContext(
        val packageName: String,
        val processName: String,
        val isFirstPackage: Boolean,
        val isSystemServer: Boolean,
        val stage: String,
        val mainProcessName: String,
        val userId: Int,
        val modulePackageName: String?,
        val moduleApkPath: String?,
        val applicationInfo: ApplicationInfo?
    ) {
        fun reloadTargetDescriptor() = ReloadTargetDescriptor(
            targetId = "$packageName/$processName",
            isSystemServer = isSystemServer,
            applicationPackageName = applicationInfo?.packageName ?: packageName,
            applicationClassName = applicationInfo?.className,
            packageName = packageName,
        )

        fun toPackageContext(process: ProcessContext, loader: ClassLoader) = PackageContext(
            packageName = packageName, processName = processName, classLoader = loader,
            isFirstPackage = isFirstPackage, isSystemServer = isSystemServer,
            stage = runCatching { LoadStage.valueOf(stage) }.getOrDefault(
                if (isSystemServer) LoadStage.SYSTEM_SERVER_STARTING else LoadStage.PACKAGE_READY
            ),
            mainProcessName = mainProcessName, userId = userId,
            modulePackageName = modulePackageName ?: process.modulePackageName,
            moduleApkPath = moduleApkPath ?: process.moduleApkPath,
            platformSnapshot = applicationInfo?.let(::ApplicationInfoSnapshot),
        )

        companion object {
            fun from(state: Bundle): SavedContext? {
                val packageName =
                    state.getString("package")?.takeIf { it.isNotBlank() } ?: return null
                val processName =
                    state.getString("process")?.takeIf { it.isNotBlank() } ?: return null

                @Suppress("DEPRECATION")
                val info = state.getParcelable("appInfo") as? ApplicationInfo
                if (info != null && info.packageName != packageName) return null
                return SavedContext(
                    packageName,
                    processName,
                    state.getBoolean("firstPackage", true),
                    state.getBoolean("systemServer", false),
                    state.getString("stage") ?: return null,
                    state.getString("mainProcess") ?: packageName,
                    state.getInt("userId", 0),
                    state.getString("modulePackage"),
                    state.getString("moduleApkPath"),
                    info,
                )
            }
        }
    }

    private data class ReloadTargetDescriptor(
        val targetId: String,
        val isSystemServer: Boolean,
        val applicationPackageName: String?,
        val applicationClassName: String?,
        val packageName: String,
    )

    private fun PackageContext.toBundle() = Bundle().apply {
        putString("package", packageName)
        putString("process", processName)
        putBoolean("firstPackage", isFirstPackage)
        putBoolean("systemServer", isSystemServer)
        putString("stage", stage.name)
        putString("mainProcess", mainProcessName)
        putInt("userId", userId)
        putString("modulePackage", modulePackageName)
        putString("moduleApkPath", moduleApkPath)
        (platformSnapshot as? ApplicationInfoSnapshot)?.applicationInfo?.let {
            putParcelable(
                "appInfo",
                it
            )
        }
    }

    private companion object {
        const val STATE_VERSION = 1;
        const val MAX_CONTEXTS = 64
    }
}
