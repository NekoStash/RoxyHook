package hk.uwu.roxyhook.platform.libxposed

import android.content.pm.ApplicationInfo
import android.os.Bundle
import hk.uwu.roxyhook.LoadStage
import hk.uwu.roxyhook.PackageContext
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.ProcessContext
import hk.uwu.roxyhook.RLog
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.android.ApplicationInfoSnapshot
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam

/**
 * Opt-in package-scope replay for [LibXposedHotReload]. The framework does not replay package
 * events after a hot reload. Record only delivered contexts, call [prepare] from the old module,
 * then [replay] in the new module to explicitly reinstall hooks. The saved Bundle contains only
 * Android framework values; neither old module objects nor old module ClassLoaders cross generations.
 *
 * A target ClassLoader can only be recovered when old hook handles identify it unambiguously.
 * Contexts without such a handle are skipped instead of guessing a loader. [namespace] allows a
 * module to preserve its existing Bundle keys when migrating to this helper.
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
        val candidates = LinkedHashMap<ClassLoader, MutableList<Class<*>>>()
        param.oldHookHandles.forEach { handle ->
            val type =
                runCatching { handle.executable.declaringClass }.getOrNull() ?: return@forEach
            val loader = type.classLoader ?: return@forEach
            candidates.getOrPut(loader, ::mutableListOf) += type
        }
        var installed = 0
        contexts.filter { it.processName == process.processName && it.isSystemServer == process.isSystemServer }
            .forEach { context ->
                val loader = context.resolveClassLoader(candidates)
                if (loader == null) {
                    RLog.warn("Package replay cannot resolve ClassLoader for ${context.packageName}/${context.processName}")
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
        fun resolveClassLoader(candidates: Map<ClassLoader, List<Class<*>>>): ClassLoader? {
            val matches = candidates.filterValues { classes ->
                classes.any { type ->
                    if (isSystemServer) type.name.startsWith("com.android.server.")
                    else type.name == packageName || type.name.startsWith("$packageName.")
                }
            }.keys
            if (matches.size == 1) return matches.single()
            if (isSystemServer && matches.isEmpty() && candidates.size == 1) return candidates.keys.single()
            return null
        }

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
