package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.*
import hk.uwu.roxyhook.prefs.Preferences
import hk.uwu.roxyhook.reflect.*
import java.lang.reflect.Executable
import com.highcapable.kavaref.resolver.MethodResolver
import com.highcapable.kavaref.resolver.ConstructorResolver

/** Remote-preferences group used by [PackageScope.prefs] and [PackageScope.prefs]. */
const val DEFAULT_PREFERENCES_GROUP = "default"

class PackageScope internal constructor(val runtime: RoxyRuntime, val context: PackageContext) {
    val packageName get() = context.packageName
    val processName get() = context.processName
    val appClassLoader get() = context.classLoader
    val isFirstPackage get() = context.isFirstPackage
    val isMainProcess get() = context.isMainProcess
    val isSystemServer get() = context.isSystemServer
    val userId get() = context.userId
    val stage get() = context.stage
    val modulePackageName get() = context.modulePackageName
    /** Declared main process name of this package; compare against [processName] via [isMainProcess]. */
    val mainProcessName get() = context.mainProcessName
    /**
     * Absolute path of the module's own APK when the platform reported it at module load;
     * null on platforms without that fact. Never derived from path guessing or package scans.
     */
    val moduleApkPath get() = context.moduleApkPath

    fun loadApp(vararg names: String, block: PackageScope.() -> Unit) {
        require(names.all { it.isNotBlank() }) { "Package names must not be blank" }
        if (!isSystemServer && (names.isEmpty() || packageName in names)) block()
    }
    fun loadApp(name: String, hooker: RoxyHooker) = loadApp(name) { loadHooker(hooker) }
    /** All APP package events delivered by the framework scope. Never system_server or zygote. */
    fun loadAll(block: PackageScope.() -> Unit) { if (!isSystemServer) block() }
    fun loadAll(hooker: RoxyHooker) = loadAll { loadHooker(hooker) }
    fun loadAllApps(block: PackageScope.() -> Unit) = loadAll(block)
    fun loadSystem(block: PackageScope.() -> Unit) {
        if (isSystemServer && stage == LoadStage.SYSTEM_SERVER_STARTING) block()
    }
    fun loadSystem(hooker: RoxyHooker) = loadSystem { loadHooker(hooker) }
    fun process(vararg names: String, block: PackageScope.() -> Unit) {
        require(names.isNotEmpty() && names.all { it.isNotBlank() })
        if (names.any { processName == if (it.startsWith(":")) packageName + it else it }) block()
    }
    fun exceptProcess(vararg names: String, block: PackageScope.() -> Unit) {
        require(names.isNotEmpty() && names.all { it.isNotBlank() })
        if (names.none { processName == if (it.startsWith(":")) packageName + it else it }) block()
    }
    fun firstPackage(block: PackageScope.() -> Unit) { if (isFirstPackage) block() }
    fun withClassLoader(loader: ClassLoader, block: PackageScope.() -> Unit) {
        runtime.scope(context.copy(classLoader = loader)).block()
    }
    fun mainProcess(block: PackageScope.() -> Unit) { if (isMainProcess) block() }
    fun loadHooker(hooker: RoxyHooker) = hooker.install(this)

    /** Does not initialize the class. Always uses this package's loader. */
    fun String.toClass(): Class<*> = resolveType(this, appClassLoader)
    fun String.toClassOrNull(): Class<*>? = try { toClass() } catch (_: ClassNotFoundException) { null }
    fun hasClass(name: String): Boolean = name.toClassOrNull() != null
    fun Class<*>.method(block: MethodQuery.() -> Unit): MemberSelection<java.lang.reflect.Method> {
        val query = MethodQuery(appClassLoader).apply(block)
        return MemberSelection(runtime, findMethods(this, query), "$name.${query.name ?: "*"}")
    }
    fun Class<*>.constructor(block: ConstructorQuery.() -> Unit = {}): MemberSelection<java.lang.reflect.Constructor<*>> {
        val query = ConstructorQuery(appClassLoader).apply(block)
        return MemberSelection(runtime, declaredConstructors.filter(query::matches).sortedBy { it.toGenericString() }, "$name.<init>")
    }
    fun Class<*>.field(block: FieldQuery.() -> Unit): FieldAccess = findField(this, FieldQuery().apply(block))
    // KavaRef is a transitive core dependency. No extra bridge artifact or kava { } wrapper is needed.
    fun MethodResolver<*>.hook(block: HookBuilder.() -> Unit): HookHandle = runtime.hook(self, block)
    fun ConstructorResolver<*>.hook(block: HookBuilder.() -> Unit): HookHandle = runtime.hook(self, block)
    @JvmName("hookMethodResolvers")
    fun List<MethodResolver<*>>.hook(block: HookBuilder.() -> Unit): HookGroup = runtime.hookAll(map { it.self }, block)
    @JvmName("hookConstructorResolvers")
    fun List<ConstructorResolver<*>>.hook(block: HookBuilder.() -> Unit): HookGroup = runtime.hookAll(map { it.self }, block)
    fun Executable.hook(block: HookBuilder.() -> Unit): HookHandle = runtime.hook(this, block)
    fun Executable.intercept(options: HookOptions = HookOptions(), block: HookCall.() -> Any?): HookHandle =
        runtime.intercept(this, options, block)
    fun Executable.deoptimize(): Boolean {
        runtime.platform.requireCapability(Capability.DEOPTIMIZATION)
        return runtime.platform.deoptimize(this)
    }
    /** Remote preferences of the [DEFAULT_PREFERENCES_GROUP] group. Fails without REMOTE_PREFERENCES. */
    val prefs: Preferences get() = prefs()
    /** Same as [prefs]; the explicit-call form of the default remote preferences group. */
    fun prefs(): Preferences = prefs(DEFAULT_PREFERENCES_GROUP)
    /** Remote preferences of an explicitly named group. Blank names are rejected. */
    fun prefs(group: String): Preferences {
        require(group.isNotBlank())
        runtime.platform.requireCapability(Capability.REMOTE_PREFERENCES)
        return runtime.platform.preferences(group)
    }
    /** Prefix the package/process pair, then dispatch through RLog so a closed runtime never sinks messages. */
    fun log(message: String, level: LogLevel = LogLevel.INFO, error: Throwable? = null) =
        RLog.log(level, "[$packageName/$processName] $message", error)
}

