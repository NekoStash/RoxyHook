package hk.uwu.roxyhook.testing

import hk.uwu.roxyhook.CallbackErrorPolicy
import hk.uwu.roxyhook.HookHandle
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageContext
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.platform.Capability
import hk.uwu.roxyhook.platform.HookInterceptor
import hk.uwu.roxyhook.platform.HookOptions
import hk.uwu.roxyhook.platform.HookPlatform
import hk.uwu.roxyhook.platform.PlatformHook
import hk.uwu.roxyhook.platform.RetainedHook
import hk.uwu.roxyhook.platform.UnsupportedCapabilityException
import java.lang.reflect.Executable
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** JVM ownership simulation only: real framework multi-generation enumeration remains unverified. */
object KeepHotReloadSuite {
    private var tests = 0
    private fun test(name: String, block: () -> Unit) {
        block(); tests++; println("PASS  $name")
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.java.name}, got $error" }; return error
        }
        error("Expected ${T::class.java.name}")
    }

    private val first = KeepFixture::class.java.getDeclaredMethod("first")
    private val second = KeepFixture::class.java.getDeclaredMethod("second")
    private val third = KeepFixture::class.java.getDeclaredMethod("third")
    private val fourth = KeepFixture::class.java.getDeclaredMethod("fourth")
    private val fixture = KeepFixture()
    private fun RoxyRuntime.keep(
        member: Executable = first,
        name: String? = null,
        value: String = "A"
    ) =
        hook(member) {
            hotReloadPolicy = HotReloadPolicy.KEEP; id = name; before {
            result = value
        }
        }

    private fun RoxyRuntime.keepAll(vararg members: Executable, name: String? = null) =
        hookAll(members.toList()) { hotReloadPolicy = HotReloadPolicy.KEEP; id = name; before { } }

    private fun scope(runtime: RoxyRuntime) = runtime.scope(
        PackageContext("keep.test", "keep.test", KeepHotReloadSuite::class.java.classLoader)
    )

    @JvmStatic
    fun main(args: Array<String>) {
        test("AUTO, NAMED, and ordinary anonymous hooks coexist without conflating slots") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val auto = runtime.keep()
                check(auto.id == null && auto.hotReloadPolicy == HotReloadPolicy.KEEP)
                check(runtime.keep(value = "ignored") === auto)
                val named = runtime.keep(name = "AUTO")
                check(named !== auto && named.id == "AUTO")
                check(runtime.keep(name = "AUTO") === named)
                runtime.hook(first) { before { } }
                runtime.hook(first) { before { } }
                check(runtime.hookCount == 4 && platform.registrationCount == 4 && platform.hookCalls == 4L)
                check(platform.invoke(first, fixture) == "A")
            }
            check(platform.registrationCount == 0)
        }
        test("AUTO replay executes configuration but keeps first callbacks and rejects priority drift") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                var configurations = 0
                val firstHandle = runtime.hook(first) {
                    configurations++; hotReloadPolicy = HotReloadPolicy.KEEP; before {
                    result = "A"
                }
                }
                val replayed = runtime.hook(first) {
                    configurations++; hotReloadPolicy = HotReloadPolicy.KEEP; before {
                    result = "B"
                }
                }
                check(configurations == 2 && firstHandle === replayed)
                expect<IllegalArgumentException> {
                    runtime.hook(first) {
                        hotReloadPolicy = HotReloadPolicy.KEEP; priority++; before { }
                    }
                }
                check(platform.invoke(first, fixture) == "A" && platform.hookCalls == 1L)
            }
        }
        test("ordinary named replacement remains atomic and KEEP conflicts are rejected") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val old = runtime.hook(first) { id = "ordinary"; before { result = "old" } }
                val current = runtime.hook(first) { id = "ordinary"; before { result = "new" } }
                check(!old.isActive && current.isActive && platform.registrationCount == 1)
                expect<IllegalArgumentException> { runtime.keep(name = "ordinary") }
                val kept = runtime.keep(second, "kept")
                expect<IllegalArgumentException> {
                    runtime.hook(second) {
                        id = "kept"; before { }
                    }
                }
                expect<IllegalArgumentException> {
                    runtime.hook(second) {
                        id = "kept"; priority = 90; hotReloadPolicy =
                        HotReloadPolicy.KEEP; before { }
                    }
                }
                check(kept.isActive && platform.registrationCount == 2)
            }
        }
        test("hookAll duplicates, reorder, overlaps, growth and shrink share per-member AUTO") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val single = runtime.keep()
                val group = runtime.keepAll(first, second, first)
                val reordered = runtime.keepAll(second, first)
                check(group.handles.size == 2 && group.handles[0] === single)
                check(reordered.handles == group.handles.reversed())
                val overlap = runtime.keepAll(second, third)
                check(overlap.handles[0] === group.handles[1])
                check(runtime.keepAll(first).handles.single() === single)
                check(platform.registrationCount == 3 && platform.hookCalls == 3L)
                group.close()
                check(!single.isActive && !overlap.handles[0].isActive && overlap.handles[1].isActive)
                check(platform.registrationCount == 1)
                overlap.close(); reordered.close()
                check(platform.registrationCount == 0)
            }
        }
        test("NAMED batches reuse per-target slots and ordinary named batches stay restricted") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val named = runtime.keepAll(first, second, name = "shared")
                check(named.handles.all { it.id == "shared" })
                check(runtime.keep(second, "shared") === named.handles[1])
                runtime.keepAll(first, second)
                check(platform.registrationCount == 4)
                expect<IllegalArgumentException> {
                    runtime.hookAll(listOf(first, second)) {
                        id = "ordinary"; before { }
                    }
                }
                check(platform.registrationCount == 4)
            }
        }
        test("batch failure rolls back new handles only, including adopted reuse") {
            val platform = ReflectionPlatform()
            val old = RoxyRuntime(platform)
            old.keep(); old.retireForHotReload()
            RoxyRuntime(platform).use { runtime ->
                val adopted = runtime.adoptKeptHooks(platform.retainedHooks()).single()
                platform.reject = { it == third }
                val error = expect<IllegalStateException> { runtime.keepAll(first, second, third) }
                check(error.message?.contains("third") == true)
                check(adopted.isActive && runtime.hookCount == 1 && platform.registrationCount == 1)
                check(platform.invoke(first, fixture) == "A")
            }
            old.close()
            check(platform.registrationCount == 0)
        }
        test("rollback unhook failures are aggregated and remain explicitly removable") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val reused = runtime.keep()
                platform.reject = { it == third }
                platform.rejectUnhook = { it == second }
                val error = expect<IllegalStateException> { runtime.keepAll(first, second, third) }
                check(error.suppressed.isNotEmpty())
                check(reused.isActive && platform.registrationCount == 2)
                platform.rejectUnhook = null
            }
            check(platform.registrationCount == 0)
        }
        test("all-member prevalidation avoids partial installation for unavailable constructors") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                expect<UnsupportedCapabilityException> {
                    runtime.keepAll(first, KeepFixture::class.java.getDeclaredConstructor())
                }
                check(platform.registrationCount == 0 && platform.hookCalls == 0L)
            }
        }
        test("simulated A to B to C retains A callback once while ordinary callbacks change") {
            val platform = ReflectionPlatform()
            val seen = mutableListOf<String>()
            val a = RoxyRuntime(platform)
            val original =
                a.hook(first) { hotReloadPolicy = HotReloadPolicy.KEEP; before { seen += "A" } }
            a.hook(first) { before { seen += "R-A" } }
            platform.invoke(first, fixture); check(seen == listOf("A", "R-A")); seen.clear()
            a.retireForHotReload()
            check(!original.isActive && original.state == HookHandle.State.DETACHED && a.hookCount == 0)
            val b = RoxyRuntime(platform)
            val bHandle = b.adoptKeptHooks(platform.retainedHooks()).single()
            check(b.keep(value = "B") === bHandle)
            b.hook(first) { before { seen += "R-B" } }
            original.remove(); original.close(); a.close()
            check(platform.registrationCount == 2)
            platform.invoke(first, fixture); check(seen == listOf("A", "R-B")); seen.clear()
            b.retireForHotReload()
            val c = RoxyRuntime(platform)
            val cHandle = c.adoptKeptHooks(platform.retainedHooks()).single()
            check(c.keepAll(first).handles.single() === cHandle)
            c.hook(first) { before { seen += "R-C" } }
            bHandle.unhook(); b.close()
            platform.invoke(first, fixture); check(seen == listOf("A", "R-C"))
            check(c.hookCount == 2 && platform.hookCalls == 4L)
            c.close(); check(platform.registrationCount == 0)
        }
        test("adopted raw intercept callback is not replaced and explicit removal permits fresh KEEP") {
            val platform = ReflectionPlatform()
            val a = RoxyRuntime(platform)
            a.intercept(first, HookOptions(hotReloadPolicy = HotReloadPolicy.KEEP)) { "raw-A" }
            a.retireForHotReload()
            RoxyRuntime(platform).use { b ->
                val adopted = b.adoptKeptHooks(platform.retainedHooks()).single()
                check(
                    b.intercept(
                        first,
                        HookOptions(hotReloadPolicy = HotReloadPolicy.KEEP)
                    ) { "raw-B" } === adopted)
                check(platform.invoke(first, fixture) == "raw-A")
                adopted.remove(); check(platform.registrationCount == 0)
                b.keep(value = "fresh"); check(platform.invoke(first, fixture) == "fresh")
            }
        }
        test("KEEP replace and callback removeSelf are rejected without removing native registration") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                var failure: Throwable? = null
                val handle = runtime.hook(first) {
                    hotReloadPolicy = HotReloadPolicy.KEEP; errorPolicy =
                    CallbackErrorPolicy.PROPAGATE
                    onFailure { failure = it.cause }; before { removeSelf() }
                }
                expect<IllegalStateException> { handle.replace { before { } } }
                expect<IllegalStateException> { platform.invoke(first, fixture) }
                check(failure is IllegalStateException && handle.isActive && platform.registrationCount == 1)
            }
        }
        test("KEEP callback error policy remains that of the first installation") {
            val platform = ReflectionPlatform()
            val a = RoxyRuntime(platform)
            a.hook(first) {
                hotReloadPolicy = HotReloadPolicy.KEEP; errorPolicy =
                CallbackErrorPolicy.PROPAGATE; before { error("original-A") }
            }
            a.retireForHotReload()
            RoxyRuntime(platform).use { b ->
                b.adoptKeptHooks(platform.retainedHooks())
                b.keep(value = "B")
                check(expect<IllegalStateException> {
                    platform.invoke(
                        first,
                        fixture
                    )
                }.message == "original-A")
            }
        }
        test("missing KEEP capability and reserved namespace fail before native registration") {
            val delegate = ReflectionPlatform()
            val unsupported = object : HookPlatform by delegate {
                override val capabilities = delegate.capabilities - Capability.HOT_RELOAD_KEEP
                override fun requireCapability(capability: Capability) {
                    if (capability !in capabilities) throw UnsupportedCapabilityException(
                        info.name,
                        capability
                    )
                }
            }
            RoxyRuntime(unsupported).use { runtime ->
                expect<UnsupportedCapabilityException> { runtime.keep() }
                check(delegate.hookCalls == 0L)
            }
            RoxyRuntime(delegate).use { runtime ->
                expect<IllegalArgumentException> { runtime.keep(name = "roxy.keep.v1:a:50") }
                expect<IllegalArgumentException> {
                    runtime.hook(first) {
                        id = "roxy.keep.v9:bad"; before { }
                    }
                }
                check(delegate.hookCalls == 0L)
            }
        }
        test("class initializer KEEP is refused even if platform advertises initializer hooks") {
            val delegate = ReflectionPlatform()
            var calls = 0
            val platform = object : HookPlatform by delegate {
                override val capabilities = delegate.capabilities + Capability.CLASS_INITIALIZER
                override fun requireCapability(capability: Capability) {
                    if (capability !in capabilities) throw UnsupportedCapabilityException(
                        info.name,
                        capability
                    )
                }

                override fun hookClassInitializer(
                    type: Class<*>,
                    options: HookOptions,
                    interceptor: HookInterceptor
                ): PlatformHook {
                    calls++; error("Must not reach platform")
                }
            }
            RoxyRuntime(platform).use { runtime ->
                expect<IllegalArgumentException> {
                    runtime.hookClassInitializer(KeepFixture::class.java) {
                        hotReloadPolicy = HotReloadPolicy.KEEP; before { }
                    }
                }
                check(calls == 0)
            }
        }
        test("concurrent AUTO and NAMED replay installs exactly once per slot") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val executor = Executors.newFixedThreadPool(8)
                val start = CountDownLatch(1)
                try {
                    val results = (0 until 64).map { index ->
                        executor.submit(Callable {
                            check(
                                start.await(
                                    5,
                                    TimeUnit.SECONDS
                                )
                            ); runtime.keep(name = if (index % 2 == 0) null else "named")
                        })
                    }
                    start.countDown()
                    val handles = results.map { it.get(10, TimeUnit.SECONDS) }
                    check(handles.toSet().size == 2 && runtime.hookCount == 2 && platform.hookCalls == 2L)
                } finally {
                    start.countDown(); executor.shutdownNow()
                }
            }
        }
        test("preflight rejection and exceptions leave KEEP and ordinary hooks untouched") {
            for (throws in listOf(false, true)) {
                val platform = ReflectionPlatform()
                RoxyRuntime(platform).use { runtime ->
                    runtime.keep(); runtime.hook(second) { before { } }
                    var quiesced = false
                    scope(runtime).loadHooker(object : RoxyHooker() {
                        override fun PackageScope.onHook() = Unit
                        override fun onHotReloadPreflight(): Boolean {
                            if (throws) error("preflight"); return false
                        }

                        override fun onHotReloadQuiesce() {
                            quiesced = true
                        }
                    })
                    check(!runtime.preflightHotReload())
                    check(runtime.isActive && !quiesced && platform.registrationCount == 2)
                }
            }
        }
        test("quiesce failure is reported and retirement still detaches KEEP and removes ordinary") {
            val platform = ReflectionPlatform()
            val runtime = RoxyRuntime(platform)
            val kept = runtime.keep(); runtime.hook(second) { before { } }
            var calls = 0
            scope(runtime).loadHooker(object : RoxyHooker() {
                override fun PackageScope.onHook() = Unit
                override fun onHotReloadQuiesce() {
                    calls++; error("quiesce")
                }
            })
            check(runtime.preflightHotReload())
            runtime.retireForHotReload()
            check(calls == 1 && kept.state == HookHandle.State.DETACHED && platform.registrationCount == 1)
            expect<IllegalStateException> { runtime.keep() }
            RoxyRuntime(platform).use { it.adoptKeptHooks(platform.retainedHooks()) }
            check(platform.registrationCount == 0)
        }
        test("retirement resource failure cleans KEEP too rather than claiming successful retention") {
            val platform = ReflectionPlatform()
            val runtime = RoxyRuntime(platform)
            runtime.keep(); runtime.hook(second) { before { } }
            runtime.onClose { error("resource-close") }
            expect<IllegalStateException> { runtime.retireForHotReload() }
            check(platform.registrationCount == 0 && platform.retainedHooks().isEmpty())
        }
        test("native unhook failure never pretends registration was removed; retry succeeds") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val handle = runtime.keep()
                platform.rejectUnhook = { true }
                expect<IllegalStateException> { handle.remove() }
                check(handle.isActive && platform.registrationCount == 1 && runtime.hookCount == 1)
                platform.rejectUnhook = null
                handle.remove(); check(!handle.isActive && platform.registrationCount == 0)
            }
        }
        test("batch rollback attempts every new handle in reverse order and preserves reused hooks") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                runtime.keep(first)
                val removed = mutableListOf<Executable>()
                platform.reject = { it == fourth }
                platform.rejectUnhook = { removed += it; true }
                val error = expect<IllegalStateException> {
                    runtime.keepAll(first, second, third, fourth)
                }
                check(removed == listOf(third, second))
                check(error.suppressed.size >= 2 && platform.registrationCount == 3)
                platform.rejectUnhook = null
            }
            check(platform.registrationCount == 0)
        }
        test("platform option validation rejects an entire batch before native installation") {
            val delegate = ReflectionPlatform()
            val platform = object : HookPlatform by delegate {
                override fun validateHookOptions(options: HookOptions) {
                    require(options.id != "invalid-metadata") { "Invalid simulated native metadata" }
                }
            }
            RoxyRuntime(platform).use { runtime ->
                expect<IllegalArgumentException> {
                    runtime.keepAll(
                        first,
                        second,
                        name = "invalid-metadata"
                    )
                }
                check(delegate.hookCalls == 0L && runtime.hookCount == 0)
            }
        }
        test("lifecycle policy cannot change through ordinary handle replacement") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val ordinary = runtime.hook(first) { before { } }
                check(ordinary.hotReloadPolicy == HotReloadPolicy.REINSTALL)
                expect<IllegalArgumentException> {
                    ordinary.replace { hotReloadPolicy = HotReloadPolicy.KEEP; before { } }
                }
                check(ordinary.isActive && platform.registrationCount == 1)
            }
        }
        test("fallback policy reports forbidden removeSelf but preserves original call and KEEP") {
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                var errors = 0
                val handle = runtime.hook(first) {
                    hotReloadPolicy = HotReloadPolicy.KEEP; errorPolicy =
                    CallbackErrorPolicy.LOG_AND_CONTINUE
                    onFailure { check(it.cause is IllegalStateException); errors++ }
                    before { removeSelf() }
                }
                check(platform.invoke(first, fixture) == "first" && errors == 1 && handle.isActive)
            }
        }
        test("same binary class name loaded twice has independent executable identity") {
            val name = KeepFixture::class.java.name
            val bytes =
                checkNotNull(KeepFixture::class.java.getResourceAsStream("KeepFixture.class")).use { it.readBytes() }

            fun isolated() = object : ClassLoader(KeepFixture::class.java.classLoader) {
                override fun loadClass(className: String, resolve: Boolean): Class<*> {
                    if (className != name) return super.loadClass(className, resolve)
                    synchronized(getClassLoadingLock(className)) {
                        val type = findLoadedClass(className) ?: defineClass(
                            className,
                            bytes,
                            0,
                            bytes.size
                        )
                        if (resolve) resolveClass(type)
                        return type
                    }
                }
            }.loadClass(name)

            val one = isolated()
            val two = isolated()
            check(one !== two && one.name == two.name)
            val platform = ReflectionPlatform()
            RoxyRuntime(platform).use { runtime ->
                val h1 = runtime.keep(one.getDeclaredMethod("first"), "same")
                val h2 = runtime.keep(two.getDeclaredMethod("first"), "same")
                check(h1 !== h2 && platform.registrationCount == 2)
                h1.remove(); check(h2.isActive && platform.registrationCount == 1)
            }
        }
        test("constructor registration requires capability and can be adopted without simulating initialization") {
            // Registration ledger only; this does not emulate ART constructor invocation semantics.
            val delegate = ReflectionPlatform()
            val live = mutableListOf<RetainedHook>()
            var calls = 0
            val platform = object : HookPlatform by delegate {
                override val capabilities = delegate.capabilities + Capability.CONSTRUCTOR_HOOK
                override fun requireCapability(capability: Capability) {
                    if (capability !in capabilities) throw UnsupportedCapabilityException(
                        info.name,
                        capability
                    )
                }

                override fun hook(
                    member: Executable,
                    options: HookOptions,
                    interceptor: HookInterceptor
                ): PlatformHook {
                    calls++
                    val native = object : PlatformHook {
                        override val member = member
                        override val id = options.id
                        override fun unhook() {
                            live.removeAll { it.hook === this }
                        }
                    }
                    live += RetainedHook(native, options)
                    return native
                }
            }
            val member = KeepFixture::class.java.getDeclaredConstructor()
            val a = RoxyRuntime(platform)
            val original = a.hook(member) { hotReloadPolicy = HotReloadPolicy.KEEP; before { } }
            check(a.hook(member) {
                hotReloadPolicy = HotReloadPolicy.KEEP; before { }
            } === original)
            a.retireForHotReload()
            RoxyRuntime(platform).use { b ->
                val adopted = b.adoptKeptHooks(live.toList()).single()
                check(b.hook(member) {
                    hotReloadPolicy = HotReloadPolicy.KEEP; before { }
                } === adopted)
                expect<IllegalArgumentException> {
                    b.hook(member) { hotReloadPolicy = HotReloadPolicy.KEEP; replaceTo(null) }
                }
                check(calls == 1)
            }
            check(live.isEmpty())
        }
        test("adoption prevalidates duplicate slots without partially tracking supplied wrappers") {
            val platform = ReflectionPlatform()
            val a = RoxyRuntime(platform)
            a.keep(); a.retireForHotReload()
            RoxyRuntime(platform).use { b ->
                val retained = platform.retainedHooks()
                expect<IllegalArgumentException> { b.adoptKeptHooks(retained + retained) }
                check(b.hookCount == 0)
                // The adapter owns received-native failure cleanup; core adoption must be atomic.
                retained.forEach { it.hook.unhook() }
            }
            check(platform.registrationCount == 0)
        }
        test("concurrent replay after adoption never installs replacement native callbacks") {
            val platform = ReflectionPlatform()
            val a = RoxyRuntime(platform)
            a.keep(); a.retireForHotReload()
            RoxyRuntime(platform).use { b ->
                val adopted = b.adoptKeptHooks(platform.retainedHooks()).single()
                val executor = Executors.newFixedThreadPool(8)
                try {
                    val results =
                        (0 until 32).map { executor.submit(Callable { b.keep(value = "ignored") }) }
                    check(results.all { it.get(10, TimeUnit.SECONDS) === adopted })
                    check(platform.hookCalls == 1L && platform.invoke(first, fixture) == "A")
                } finally {
                    executor.shutdownNow()
                }
            }
        }
        test("managed group and nested close during retirement preserve KEEP and dispose resources once") {
            val platform = ReflectionPlatform()
            val runtime = RoxyRuntime(platform)
            val group = runtime.manage(runtime.keepAll(first, second))
            val calls = mutableListOf<String>()
            runtime.onClose { calls += "first" }
            runtime.onClose { calls += "second"; runtime.close() }
            scope(runtime).loadHooker(object : RoxyHooker() {
                override fun PackageScope.onHook() = Unit
                override fun onHotReloadQuiesce() {
                    group.close()
                }
            })
            runtime.retireForHotReload()
            runtime.retireForHotReload()
            runtime.close()
            check(calls == listOf("second", "first"))
            check(group.handles.all { it.state == HookHandle.State.DETACHED })
            check(platform.registrationCount == 2)
            RoxyRuntime(platform).use { it.adoptKeptHooks(platform.retainedHooks()) }
            check(platform.registrationCount == 0)
        }
        test("runtime close retains failed removals for retry without repeating resource cleanup") {
            val platform = ReflectionPlatform()
            val runtime = RoxyRuntime(platform)
            val kept = runtime.keep()
            var disposed = 0
            runtime.onClose { disposed++ }
            platform.rejectUnhook = { true }
            expect<IllegalStateException> { runtime.close() }
            check(runtime.isClosed && runtime.hookCount == 1 && kept.state == HookHandle.State.ACTIVE)
            platform.rejectUnhook = null
            runtime.close()
            check(disposed == 1 && runtime.hookCount == 0 && platform.registrationCount == 0)
            check(kept.state == HookHandle.State.REMOVED)
        }
        test("failed retirement does not detach KEEP and failed native cleanup can be retried") {
            val platform = ReflectionPlatform()
            val runtime = RoxyRuntime(platform)
            val kept = runtime.keep()
            runtime.onClose { error("resource") }
            platform.rejectUnhook = { true }
            val failure = expect<IllegalStateException> { runtime.retireForHotReload() }
            check(failure.message == "resource" && failure.suppressed.isNotEmpty())
            check(kept.state == HookHandle.State.ACTIVE && runtime.hookCount == 1)
            platform.rejectUnhook = null
            runtime.close()
            check(platform.registrationCount == 0 && kept.state == HookHandle.State.REMOVED)
        }
        println("$tests KEEP ownership simulation tests passed; real framework/device compatibility NOT verified")
    }
}

class KeepFixture {
    fun first() = "first"
    fun second() = "second"
    fun third() = "third"
    fun fourth() = "fourth"
}
