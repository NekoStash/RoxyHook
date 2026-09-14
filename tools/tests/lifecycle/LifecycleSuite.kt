package hk.uwu.roxyhook.lifecycletests

import android.app.*
import android.content.*
import android.content.pm.ProviderInfo
import android.os.Bundle
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.android.lifecycle.*
import hk.uwu.roxyhook.android.appInfo
import hk.uwu.roxyhook.android.resources.moduleResources
import hk.uwu.roxyhook.android.systemContext
import hk.uwu.roxyhook.platform.libxposed.channel.dataChannel
import hk.uwu.roxyhook.platform.libxposed.moduleAppFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val PKG = "test.lifecycle"
class TestContext : Context() {
    val res = android.content.res.Resources()
    var packageContext: Context? = null
    override fun getPackageName() = PKG
    override fun getApplicationContext(): Context? = null
    override fun getResources() = res
    override fun createPackageContext(name: String, flags: Int) =
        packageContext ?: super.createPackageContext(name, flags)
}
class TestApp : Application() {
    val res = android.content.res.Resources()
    var packageContext: Context? = null
    override fun getPackageName() = PKG
    override fun getApplicationContext(): Context? = null // Real attach can precede LoadedApk.mApplication assignment.
    override fun getResources() = res
    override fun createPackageContext(name: String, flags: Int) =
        packageContext ?: super.createPackageContext(name, flags)
    override fun attachBaseContext(context: Context) {}
    override fun onCreate() {} // Intentionally omits super.
    override fun onLowMemory() {}
}
class TestActivity : Activity() {
    override fun getPackageName() = PKG
    override fun getApplicationContext(): Context? = null
    override fun onCreate(saved: Bundle?) {} // Intentionally omits super.
    override fun onResume() {}
    override fun onPause() {}
}
class TestService : Service() {
    override fun getPackageName() = PKG
    override fun getApplicationContext(): Context? = null
    override fun onCreate() {}
}
class TestProvider : ContentProvider() {
    private val ctx = TestContext()
    override fun getContext(): Context = ctx
    override fun attachInfo(context: Context, info: ProviderInfo) {}
    override fun onCreate() = true
}
/**
 * Loader that fails the hidden-API class lookup inside the production resolver with an
 * injected [Throwable]. The JVM routes `Class.forName(name, false, loader)` through
 * `loader.loadClass(name)`, so this controls the failure mode without any test-side reflection.
 */
class DenyingLoader(parent: ClassLoader, private val failure: Throwable) : ClassLoader(parent) {
    override fun loadClass(name: String): Class<*> =
        if (name == "android.app.ActivityThread") throw failure else super.loadClass(name)
}

object LifecycleSuite {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS  lifecycle model: $name") }
        fun fixture(block: (DispatchPlatform, RoxyRuntime, LifecycleRegistry) -> Unit) {
            val platform = DispatchPlatform()
            val runtime = RoxyRuntime(platform)
            try { block(platform, runtime, LifecycleRegistry.get(runtime)) } finally { runtime.close() }
            check(platform.activeCount == 0) { "Leaked lifecycle hooks" }
        }
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        val appCreate = TestApp::class.java.getDeclaredMethod("onCreate")
        val context = TestContext()
        test("bootstrap installs actual root signatures, once per runtime") { fixture { p, r, registry ->
            check(p.contains(attach)); val count = p.activeCount
            check(LifecycleRegistry.get(r) === registry); check(p.activeCount == count)
            check(p.contains(Activity::class.java.getDeclaredMethod("attach", Context::class.java)))
            check(p.contains(Service::class.java.getDeclaredMethod("attach", Context::class.java)))
            check(p.contains(ContentProvider::class.java.getDeclaredConstructor()))
        } }
        test("attach discovers concrete override before attachBaseContext body") { fixture { p, _, registry ->
            val app = TestApp(); val events = mutableListOf<String>()
            registry.subscribe(PKG, LifecycleKind.APPLICATION_ATTACH_BASE_CONTEXT, LifecyclePhase.BEFORE) { events += "before" }
            registry.subscribe(PKG, LifecycleKind.APPLICATION_ATTACH_BASE_CONTEXT) { events += "after" }
            val base = TestApp::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            p.dispatch(attach, app, arrayOf(context)) {
                check(p.contains(base)); check(p.contains(appCreate))
                p.dispatch(base, app, arrayOf(context)) { events += "body"; null }
            }
            check(events == listOf("before", "body", "after")) { events }
        } }
        test("onCreate override without super is observed synchronously") { fixture { p, _, registry ->
            val app = TestApp(); val order = mutableListOf<String>()
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE, LifecyclePhase.BEFORE) { order += "before" }
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE) { order += "after" }
            p.dispatch(attach, app, arrayOf(context))
            p.dispatch(appCreate, app) { order += "body"; null }
            check(order == listOf("before", "body", "after")) { order }
        } }
        test("attached Application is usable even when applicationContext is null") { fixture { p, _, registry ->
            val app = TestApp(); p.dispatch(attach, app, arrayOf(context))
            check(registry.application(PKG) === app); check(registry.appContext(PKG) === app)
            var replay = 0
            registry.subscribe(PKG, LifecycleKind.APPLICATION_ATTACH, replayLatest = true) { check(application === app); replay++ }.close()
            check(replay == 1)
        } }
        test("late replay and live delivery do not duplicate a registration during dispatch") { fixture { p, _, registry ->
            val app = TestApp(); var observed = 0
            registry.subscribe(PKG, LifecycleKind.APPLICATION_ATTACH) {
                registry.subscribe(PKG, LifecycleKind.APPLICATION_ATTACH, replayLatest = true) { observed++ }
            }
            p.dispatch(attach, app, arrayOf(context)); check(observed == 1)
        } }
        test("package filtering and unsubscription suppress unrelated listeners") { fixture { p, _, registry ->
            val app = TestApp(); var called = 0
            registry.subscribe("different.package", LifecycleKind.APPLICATION_CREATE) { error("wrong package") }
            val sub = registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE) { called++ }
            p.dispatch(attach, app, arrayOf(context)); p.dispatch(appCreate, app); sub.close(); p.dispatch(appCreate, app)
            check(called == 1); check(p.errors.isEmpty())
        } }
        test("observer failures do not skip target body or later observers") { fixture { p, _, registry ->
            val app = TestApp(); var called = 0; var body = 0
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE, LifecyclePhase.BEFORE) { error("observer") }
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE, LifecyclePhase.BEFORE) { called++ }
            p.dispatch(attach, app, arrayOf(context)); p.dispatch(appCreate, app) { body++; null }
            check(called == 1 && body == 1 && p.errors.size == 1)
        } }
        test("failed original remains failed; opt-in failure observers receive throwable") { fixture { p, _, registry ->
            val app = TestApp(); var successful = 0; var failed = 0; val original = IllegalStateException("original")
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE) { successful++ }
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE, successfulOnly = false) { check(throwable === original); failed++ }
            p.dispatch(attach, app, arrayOf(context))
            check(runCatching { p.dispatch(appCreate, app) { throw original } }.exceptionOrNull() === original)
            check(successful == 0 && failed == 1)
        } }
        test("reentrant BEFORE stays before nested target body, never queued") { fixture { p, _, registry ->
            val outer = TestApp(); val inner = TestApp(); val order = mutableListOf<String>()
            p.dispatch(attach, outer, arrayOf(context)); p.dispatch(attach, inner, arrayOf(context))
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE, LifecyclePhase.BEFORE) {
                if (instance === outer) { order += "outer-before"; p.dispatch(appCreate, inner) { order += "inner-body"; null } }
                else order += "inner-before"
            }
            p.dispatch(appCreate, outer) { order += "outer-body"; null }
            check(order == listOf("outer-before", "inner-before", "inner-body", "outer-body")) { order }
        } }
        test("independent concurrent live events are both delivered") { fixture { p, _, registry ->
            val one = TestApp(); val two = TestApp(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val called = AtomicInteger(); val completed = AtomicInteger()
            p.dispatch(attach, one, arrayOf(context)); p.dispatch(attach, two, arrayOf(context))
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE, LifecyclePhase.BEFORE) {
                called.incrementAndGet(); if (instance === one) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            }
            val thread = Thread { p.dispatch(appCreate, one) { completed.incrementAndGet(); null } }; thread.start()
            try { check(entered.await(3, TimeUnit.SECONDS)); p.dispatch(appCreate, two) { completed.incrementAndGet(); null } }
            finally { release.countDown(); thread.join(4000) }
            check(!thread.isAlive); check(called.get() == 2 && completed.get() == 2); check(p.errors.isEmpty())
        } }
        test("Activity, Service and Provider roots install concrete overrides") { fixture { p, _, registry ->
            val activity = TestActivity(); val service = TestService(); val provider = TestProvider()
            val events = mutableListOf<LifecycleKind>()
            listOf(LifecycleKind.ACTIVITY_CREATE, LifecycleKind.SERVICE_CREATE, LifecycleKind.PROVIDER_CREATE).forEach { kind ->
                registry.subscribe(PKG, kind) { events += kind }
            }
            p.dispatch(Activity::class.java.getDeclaredMethod("attach", Context::class.java), activity, arrayOf(context))
            p.dispatch(TestActivity::class.java.getDeclaredMethod("onCreate", Bundle::class.java), activity, arrayOf(null))
            p.dispatch(Service::class.java.getDeclaredMethod("attach", Context::class.java), service, arrayOf(context))
            p.dispatch(TestService::class.java.getDeclaredMethod("onCreate"), service)
            // Constructor dispatch is explicitly modeled with an existing object. This is not ART constructor execution.
            p.dispatch(ContentProvider::class.java.getDeclaredConstructor(), provider)
            check(p.contains(TestProvider::class.java.getDeclaredMethod("attachInfo", Context::class.java, ProviderInfo::class.java)))
            p.dispatch(TestProvider::class.java.getDeclaredMethod("onCreate"), provider) { true }
            check(events == listOf(LifecycleKind.ACTIVITY_CREATE, LifecycleKind.SERVICE_CREATE, LifecycleKind.PROVIDER_CREATE))
        } }
        test("failed bootstrap rolls back and permits retry") {
            val p = DispatchPlatform(); val r = RoxyRuntime(p)
            try {
                p.failOnName = "attach"
                check(runCatching { LifecycleRegistry.get(r) }.isFailure); check(p.activeCount == 0)
                p.failOnName = null; LifecycleRegistry.get(r); check(p.activeCount > 0)
            } finally { r.close() }
            check(p.activeCount == 0)
        }
        test("scope appContext/appResources surface the attached application") { fixture { p, r, registry ->
            val app = TestApp(); p.dispatch(attach, app, arrayOf(context))
            val scope = r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader))
            check(scope.appContext === app)
            check(scope.appResources === app.resources)
        } }
        test("appInfo reads the platform snapshot only when supplied") { fixture { p, r, _ ->
            val info = android.content.pm.ApplicationInfo().apply { packageName = PKG; processName = PKG }
            val ctx = hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader,
                platformSnapshot = hk.uwu.roxyhook.android.ApplicationInfoSnapshot(info))
            val scope = r.scope(ctx)
            check(scope.appInfo !== info) // defensive copy, not the framework's instance
            check(scope.appInfo!!.packageName == PKG && scope.appInfo!!.processName == PKG)
            check(r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader)).appInfo == null)
        } }
        test("systemContext is rejected outside system_server") { fixture { _, r, _ ->
            val appScope = r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader))
            val failure = runCatching { appScope.systemContext }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message!!.contains("system_server"))
        } }
        test("systemContext resolves via the injected resolver seam") { fixture { _, r, _ ->
            val systemScope = r.scope(hk.uwu.roxyhook.PackageContext("android", "system", javaClass.classLoader,
                isSystemServer = true, stage = hk.uwu.roxyhook.LoadStage.SYSTEM_SERVER_STARTING))
            val resolved = TestContext()
            hk.uwu.roxyhook.android.SystemContextResolver.installForTest(r,
                hk.uwu.roxyhook.android.SystemContextResolver { resolved })
            check(systemScope.systemContext === resolved)
        } }
        test("systemContext surfaces resolver failure with the cause attached") { fixture { _, r, _ ->
            val systemScope = r.scope(hk.uwu.roxyhook.PackageContext("android", "system", javaClass.classLoader,
                isSystemServer = true, stage = hk.uwu.roxyhook.LoadStage.SYSTEM_SERVER_STARTING))
            val cause = NoSuchMethodException("hidden")
            hk.uwu.roxyhook.android.SystemContextResolver.installForTest(r,
                hk.uwu.roxyhook.android.SystemContextResolver { throw IllegalStateException("restricted", cause) })
            val failure = runCatching { systemScope.systemContext }.exceptionOrNull()
            check(failure is IllegalStateException && failure.cause === cause)
        } }
        test("production hidden-API resolver wraps a RuntimeException rejection with its cause") { fixture { _, r, _ ->
            val security = SecurityException("hidden API enforcement")
            val systemScope = r.scope(hk.uwu.roxyhook.PackageContext("android", "system",
                DenyingLoader(javaClass.classLoader, security),
                isSystemServer = true, stage = hk.uwu.roxyhook.LoadStage.SYSTEM_SERVER_STARTING))
            val failure = runCatching {
                hk.uwu.roxyhook.android.HiddenApiSystemContextResolver.resolve(systemScope)
            }.exceptionOrNull()
            check(failure is IllegalStateException && failure.cause === security) { "$failure" }
        } }
        test("no-arg moduleResources resolves against attached appContext") { fixture { p, r, registry ->
            val app = TestApp(); p.dispatch(attach, app, arrayOf(context))
            val moduleCtx = TestContext()
            app.packageContext = moduleCtx
            val scope = r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader,
                modulePackageName = "mod.pkg"))
            val res = scope.moduleResources
            check(res.context === moduleCtx && res.packageName == "mod.pkg")
        } }
        test("no-arg moduleResources fails before attach and without module package") { fixture { _, r, _ ->
            val scope = r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader,
                modulePackageName = "mod.pkg"))
            check(runCatching { scope.moduleResources }.exceptionOrNull() is IllegalStateException)
            val noModule = r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader))
            check(runCatching { noModule.moduleResources }.exceptionOrNull() is IllegalStateException)
        } }
        test("moduleAppFile surfaces the module APK path and fails without it") { fixture { _, r, _ ->
            val withPath = r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader,
                moduleApkPath = "/data/app/hk.uwu.roxyhook.sample.module/base.apk"))
            check(withPath.moduleAppFile.name == "base.apk")
            val noPath = r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader))
            check(runCatching { noPath.moduleAppFile }.exceptionOrNull() is IllegalStateException)
        } }
        test("ambient dataChannel fails in system_server and before attach") { fixture { _, r, _ ->
            val system = r.scope(hk.uwu.roxyhook.PackageContext("android", "system", javaClass.classLoader,
                isSystemServer = true, stage = hk.uwu.roxyhook.LoadStage.SYSTEM_SERVER_STARTING))
            check(runCatching { system.dataChannel }.exceptionOrNull() is IllegalStateException)
            val app = r.scope(hk.uwu.roxyhook.PackageContext(PKG, PKG, javaClass.classLoader,
                modulePackageName = "mod.pkg"))
            check(runCatching { app.dataChannel }.exceptionOrNull() is IllegalStateException)
        } }
        test("close releases replay state, subscriptions and every installed hook") { fixture { p, r, registry ->
            val app = TestApp(); p.dispatch(attach, app, arrayOf(context)); var called = 0
            registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE) { called++ }
            r.close(); check(registry.application(PKG) == null); check(p.activeCount == 0)
            check(runCatching { registry.subscribe(PKG, LifecycleKind.APPLICATION_CREATE) {} }.isFailure)
            p.dispatch(appCreate, app); check(called == 0)
        } }
        println("$passed/$passed Android lifecycle dispatch-model contracts passed. NOT a device/ART test.")
    }
}
