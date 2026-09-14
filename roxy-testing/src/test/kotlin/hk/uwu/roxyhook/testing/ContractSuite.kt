package hk.uwu.roxyhook.testing

import hk.uwu.roxyhook.*
import hk.uwu.roxyhook.platform.*
import hk.uwu.roxyhook.prefs.*
import hk.uwu.roxyhook.reflect.*
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Fixture {
    var calls = 0
    @JvmField var fieldValue = "before"
    val targetFailure = IllegalArgumentException("target failure")
    fun greet(name: String): String { calls++; return "Hello $name" }
    fun integer(value: Int): Int { calls++; return value * 2 }
    fun nullable(): String? { calls++; return "non-null" }
    fun boom(): String { calls++; throw targetFailure }
    fun nothing() { calls++ }
    fun overloaded(value: String) = value
    fun overloaded(value: Int) = value
    fun boxed(value: Int?) = value
    companion object { @JvmStatic fun sum(a: Int, b: Int): Int = a + b }
}
open class Parent { open fun inherited(): String = "parent" }
class Child : Parent() { override fun inherited(): String = "child" }
class NotInitialized { companion object { init { System.setProperty("roxy.test.initialized", "yes") } } }

object ContractSuite {
    private val results = mutableListOf<Pair<String, Throwable?>>()
    private fun test(name: String, block: () -> Unit) {
        try { block(); results += name to null; println("PASS  $name") }
        catch (error: Throwable) { results += name to error; println("FAIL  $name: $error"); error.printStackTrace() }
    }
    private inline fun <reified T : Throwable> throws(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            check(error is T) { "Expected ${T::class.java.name}, got $error" }; return error
        }
        error("Expected ${T::class.java.name}")
    }
    private fun equal(expected: Any?, actual: Any?) { check(expected == actual) { "Expected <$expected>, got <$actual>" } }
    private fun method(name: String, vararg params: Class<*>) = Fixture::class.java.getDeclaredMethod(name, *params)
    private val greet get() = method("greet", StringType)
    private val integer get() = method("integer", IntType)
    private val loader get() = Fixture::class.java.classLoader
    private fun RoxyRuntime.scope() = scope(PackageContext("demo.app", "demo.app", loader))
    private inline fun scenario(block: (ReflectionPlatform, RoxyRuntime, Fixture) -> Unit) {
        val platform = ReflectionPlatform()
        RoxyRuntime(platform).use { block(platform, it, Fixture()) }
    }

    @JvmStatic fun main(argv: Array<String>) {
        test("before modifies arguments and after modifies result") { scenario { p, r, f ->
            r.hook(greet) { before { args(0).set("Roxy") }; after { result = "$result!" } }
            equal("Hello Roxy!", p.invoke(greet, f, "World")); equal(1, f.calls)
        } }
        test("explicit null short-circuits original") { scenario { p, r, f ->
            val m = method("nullable"); r.hook(m) { before { result = null } }
            equal(null, p.invoke(m, f)); equal(0, f.calls)
        } }
        test("after still runs after short circuit") { scenario { p, r, f ->
            r.hook(greet) { before { result = "early" }; after { result = "$result-after" } }
            equal("early-after", p.invoke(greet, f, "x")); equal(0, f.calls)
        } }
        test("original throwable identity survives") { scenario { p, r, f ->
            val m = method("boom"); r.hook(m) { errorPolicy = CallbackErrorPolicy.PROPAGATE; after { check(hasThrowable) } }
            check(throws<IllegalArgumentException> { p.invoke(m, f) } === f.targetFailure)
        } }
        test("after recovers original exception") { scenario { p, r, f ->
            val m = method("boom"); r.hook(m) { after { if (hasThrowable) result = "recovered" } }
            equal("recovered", p.invoke(m, f)); equal(1, f.calls)
        } }
        test("explicit throwable skips original and is propagated") { scenario { p, r, f ->
            val failure = UnsupportedOperationException("assigned")
            r.hook(greet) { before { throwable = failure } }
            check(throws<UnsupportedOperationException> { p.invoke(greet, f, "x") } === failure); equal(0, f.calls)
        } }
        test("setting result clears throwable") { scenario { p, r, f ->
            r.hook(greet) { before { throwable = Exception(); result = "ok" } }
            equal("ok", p.invoke(greet, f, "x"))
        } }
        test("failed before restores argument vector and result") { scenario { p, r, f ->
            r.hook(greet) { before { args[0] = "bad"; result = "bad"; error("callback") } }
            equal("Hello original", p.invoke(greet, f, "original")); equal(1, f.calls)
        } }
        test("failed after restores original result without replay") { scenario { p, r, f ->
            r.hook(greet) { after { result = "bad"; error("callback") } }
            equal("Hello original", p.invoke(greet, f, "original")); equal(1, f.calls)
        } }
        test("failed after restores original throwable") { scenario { p, r, f ->
            val m = method("boom"); r.hook(m) { after { result = "bad"; error("callback") } }
            check(throws<IllegalArgumentException> { p.invoke(m, f) } === f.targetFailure); equal(1, f.calls)
        } }
        test("replacement failure before original falls through once") { scenario { p, r, f ->
            r.hook(greet) { replaceAny { error("callback") } }
            equal("Hello x", p.invoke(greet, f, "x")); equal(1, f.calls)
        } }
        test("replacement failure after callOriginal does not repeat original") { scenario { p, r, f ->
            r.hook(greet) { replaceAny { callOriginal(); error("callback") } }
            equal("Hello x", p.invoke(greet, f, "x")); equal(1, f.calls)
        } }
        test("callOriginal exception is not replayed by protection") { scenario { p, r, f ->
            val m = method("boom"); r.hook(m) { replaceAny { callOriginal() } }
            check(throws<IllegalArgumentException> { p.invoke(m, f) } === f.targetFailure); equal(1, f.calls)
        } }
        test("propagate policy does not hide callback failure") { scenario { p, r, f ->
            val failure = IllegalStateException("callback")
            r.hook(greet) { errorPolicy = CallbackErrorPolicy.PROPAGATE; before { throw failure } }
            check(throws<IllegalStateException> { p.invoke(greet, f, "x") } === failure); equal(0, f.calls)
        } }
        test("wrong primitive result rolls back safely") { scenario { p, r, f ->
            r.hook(integer) { before { result = "not an integer" } }
            equal(8, p.invoke(integer, f, 4)); equal(1, f.calls)
        } }
        test("wrong primitive argument rolls back safely") { scenario { p, r, f ->
            r.hook(integer) { before { args[0] = null } }
            equal(8, p.invoke(integer, f, 4)); equal(1, f.calls)
        } }
        test("void replacement returns null without executing original") { scenario { p, r, f ->
            val m = method("nothing"); var sideEffect = false
            r.hook(m) { replaceUnit { sideEffect = true } }
            equal(null, p.invoke(m, f)); equal(0, f.calls); check(sideEffect)
        } }
        test("priority is high-before low-before low-after high-after") { scenario { p, r, f ->
            val order = mutableListOf<String>()
            r.hook(greet) { priority = 10; before { order += "LB" }; after { order += "LA" } }
            r.hook(greet) { priority = 100; before { order += "HB" }; after { order += "HA" } }
            p.invoke(greet, f, "x"); equal(listOf("HB", "LB", "LA", "HA"), order)
        } }
        test("callOriginal bypasses other hooks") { scenario { p, r, f ->
            r.hook(greet) { priority = 1; replaceTo("lower") }
            r.hook(greet) { priority = 99; replaceAny { callOriginal() as String + "!" } }
            equal("Hello x!", p.invoke(greet, f, "x")); equal(1, f.calls)
        } }
        test("original invocation uses exact parent implementation") { scenario { p, _, _ ->
            val parent = Parent::class.java.getDeclaredMethod("inherited")
            equal("parent", p.invokeOriginal(parent, Child(), emptyArray()))
        } }
        test("raw proceed continues other hooks") { scenario { p, r, f ->
            r.hook(greet) { priority = 1; after { result = "$result-lower" } }
            r.intercept(greet, HookOptions(99)) { proceed(arguments.toTypedArray()) as String + "-outer" }
            equal("Hello x-lower-outer", p.invoke(greet, f, "x"))
        } }
        test("raw proceed cannot be called twice") { scenario { p, r, f ->
            r.intercept(greet) { proceed(arguments.toTypedArray()); proceed(arguments.toTypedArray()) }
            throws<IllegalStateException> { p.invoke(greet, f, "x") }; equal(1, f.calls)
        } }
        test("retained HookParam cannot be reused") { scenario { p, r, f ->
            lateinit var escaped: HookParam
            r.hook(greet) { before { escaped = this } }
            p.invoke(greet, f, "x"); throws<IllegalStateException> { escaped.result }
        } }
        test("HookParam rejects cross-thread use") { scenario { p, r, f ->
            val observed = AtomicReference<Throwable?>()
            r.hook(greet) { before {
                val param = this
                Thread { try { param.args } catch (t: Throwable) { observed.set(t) } }.also { it.start(); it.join() }
            } }
            p.invoke(greet, f, "x"); check(observed.get() is IllegalStateException)
        } }
        test("callback extras are per invocation") { scenario { p, r, f ->
            r.hook(greet) { errorPolicy = CallbackErrorPolicy.PROPAGATE; before { check(extras.isEmpty()); extras["arg"] = args[0] }; after { equal(args[0], extras["arg"]) } }
            p.invoke(greet, f, "one"); p.invoke(greet, f, "two")
        } }
        test("unhook is idempotent and removes tracking") { scenario { p, r, f ->
            val handle = r.hook(greet) { replaceTo("hook") }
            handle.unhook(); handle.unhook(); equal(0, r.hookCount); equal("Hello x", p.invoke(greet, f, "x"))
        } }
        test("native atomic handle replacement works") { scenario { p, r, f ->
            val handle = r.hook(greet) { id = "greeting"; replaceTo("old") }
            handle.replace { replaceTo("new") }; equal("new", p.invoke(greet, f, "x")); equal(1, p.registrationCount)
        } }
        test("replacement cannot change priority") { scenario { _, r, _ ->
            val handle = r.hook(greet) { priority = 2; replaceTo("old") }
            throws<IllegalArgumentException> { handle.replace { priority = 3; replaceTo("new") } }
        } }
        test("same id replaces and stale unhook leaves new hook installed") { scenario { p, r, f ->
            val old = r.hook(greet) { id = "same"; replaceTo("old") }
            r.hook(greet) { id = "same"; replaceTo("new") }; old.unhook()
            equal("new", p.invoke(greet, f, "x")); equal(1, p.registrationCount)
        } }
        test("in-flight snapshot keeps old interceptor after replacement") { scenario { p, r, f ->
            val lower = r.hook(greet) { priority = 1; replaceTo("old") }
            val first = AtomicBoolean(true)
            r.hook(greet) { priority = 99; before { if (first.compareAndSet(true, false)) lower.replace { replaceTo("new") } } }
            equal("old", p.invoke(greet, f, "x")); equal("new", p.invoke(greet, f, "x"))
        } }
        test("batch installation rolls back on failure") { scenario { p, r, _ ->
            p.reject = { it == integer }
            throws<IllegalStateException> { r.hookAll(listOf(greet, integer)) { before {} } }
            equal(0, p.registrationCount); equal(0, r.hookCount)
        } }
        test("named batch is rejected before changing existing hooks") { scenario { p, r, _ ->
            throws<IllegalArgumentException> { r.hookAll(listOf(greet, integer)) { id = "batch"; before {} } }
            equal(0, p.registrationCount)
        } }
        test("empty hooks and conflicting callback modes are rejected") { scenario { _, r, _ ->
            throws<IllegalStateException> { r.hook(greet) {} }
            throws<IllegalStateException> { r.hook(greet) { before {}; replaceTo("x") } }
        } }
        test("runtime close removes hooks and rejects new registrations") {
            val p = ReflectionPlatform(); val r = RoxyRuntime(p)
            r.hook(greet) { before {} }; r.close(); r.close()
            equal(0, p.registrationCount); throws<IllegalStateException> { r.hook(greet) { before {} } }
        }
        test("overloaded finder requires an explicit choice") { scenario { _, r, _ -> with(r.scope()) {
            throws<AmbiguousMemberException> { Fixture::class.java.method { name = "overloaded" }.hook { before {} } }
            equal(IntType, Fixture::class.java.method { name = "overloaded"; param(IntType) }.single().parameterTypes.single())
        } } }
        test("all overloads and constructor selection") { scenario { p, r, _ -> with(r.scope()) {
            Fixture::class.java.method { name = "overloaded" }.all().hook { before {} }
            equal(2, p.registrationCount)
            equal(0, Fixture::class.java.constructor { emptyParam() }.single().parameterCount)
        } } }
        test("missing-member optional does not swallow ambiguity") { scenario { _, r, _ -> with(r.scope()) {
            equal(null, Fixture::class.java.method { name = "absent" }.hookIfExists { before {} })
            throws<AmbiguousMemberException> { Fixture::class.java.method { name = "overloaded" }.hookIfExists { before {} } }
        } } }
        test("finder supports KClass, string type and wildcard") { scenario { _, r, _ -> with(r.scope()) {
            equal(greet, Fixture::class.java.method { name = "greet"; param(String::class) }.single())
            equal(greet, Fixture::class.java.method { name = "greet"; param("java.lang.String") }.single())
            equal(greet, Fixture::class.java.method { name = "greet"; param(VagueType) }.single())
        } } }
        test("primitive and boxed types remain distinct") { scenario { _, r, _ -> with(r.scope()) {
            throws<NoSuchMemberException> { Fixture::class.java.method { name = "boxed"; param(IntType) }.single() }
            equal(java.lang.Integer::class.java, Fixture::class.java.method {
                name = "boxed"; param(java.lang.Integer::class.java)
            }.single().parameterTypes.single())
        } } }
        test("inherited search prefers nearest declaration") { scenario { _, r, _ -> with(r.scope()) {
            equal(Child::class.java, Child::class.java.method { name = "inherited"; superClass() }.single().declaringClass)
        } } }
        test("field access and primitive array class resolution") { scenario { _, r, f -> with(r.scope()) {
            val access = Fixture::class.java.field { name = "fieldValue" }.of(f)
            access.set("after"); equal("after", access.cast<String>())
            equal(IntArray::class.java, "int[]".toClass())
        } } }
        test("class lookup does not initialize target class") { scenario { _, r, _ -> with(r.scope()) {
            System.clearProperty("roxy.test.initialized")
            "hk.uwu.roxyhook.testing.NotInitialized".toClass()
            equal(null, System.getProperty("roxy.test.initialized"))
        } } }
        test("package and process filters are exact") { scenario { _, r, _ ->
            var invoked = 0
            with(r.scope(PackageContext("demo.app", "demo.app:worker", loader, false))) {
                loadApp("other") { invoked++ }; mainProcess { invoked++ }
                loadApp("demo.app") { process("demo.app:worker") { invoked++ } }
                loadSystem { invoked++ }
            }
            equal(1, invoked)
        } }
        test("business hooker is platform independent") { scenario { p, r, f ->
            val business = object : RoxyHooker() {
                override fun PackageScope.onHook() {
                    Fixture::class.java.method { name = "greet"; param(StringType) }.hook { replaceTo("shared") }
                }
            }
            r.scope().loadHooker(business); equal("shared", p.invoke(greet, f, "x"))
        } }
        test("unsupported capability fails explicitly") { scenario { _, r, _ ->
            throws<UnsupportedCapabilityException> { r.scope().prefs() }
            throws<UnsupportedCapabilityException> { r.hookClassInitializer(Fixture::class.java) { before {} } }
        } }
        test("preference keys validate names and copy default sets") {
            throws<IllegalArgumentException> { stringPreference(" ") }
            val source = mutableSetOf("a"); val key = stringSetPreference("set", source); source += "b"
            equal(setOf("a"), key.default)
            var closed = 0; val subscription = Subscription.once { closed++ }; subscription.close(); subscription.close(); equal(1, closed)
        }
        test("normal JVM calls are intentionally not intercepted") { scenario { p, r, f ->
            r.hook(greet) { replaceTo("hooked") }
            equal("Hello x", f.greet("x")); equal("hooked", p.invoke(greet, f, "x"))
        } }
        test("concurrent invocations have isolated argument and callback state") { scenario { p, r, _ ->
            val sum = method("sum", IntType, IntType)
            r.hook(sum) { errorPolicy = CallbackErrorPolicy.PROPAGATE; before { extras["n"] = args[0]; args[1] = 1 }; after { equal((extras["n"] as Int) + 1, result) } }
            val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs = (0 until 200).map { n -> Callable { equal(n + 1, p.invoke(sum, null, n, 99)) } }
                pool.invokeAll(jobs).forEach { it.get() }
            } finally { pool.shutdownNow() }
        } }
        test("concurrent replacement has no unhook gap") { scenario { p, r, _ ->
            val sum = method("sum", IntType, IntType)
            val handle = r.hook(sum) { replaceTo(10) }
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            try {
                val a = pool.submit { start.await(); repeat(200) { n -> handle.replace { replaceTo(if (n % 2 == 0) 10 else 20) } } }
                val b = pool.submit { start.await(); repeat(1000) { check(p.invoke(sum, null, 1, 2) in setOf(10, 20)) } }
                start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        } }
        test("retained raw call rejects getters and proceed") { scenario { p, r, f ->
            lateinit var saved: HookCall
            r.intercept(greet) { saved = this; proceed(arguments.toTypedArray()) }
            p.invoke(greet, f, "x")
            throws<IllegalStateException> { saved.member }
            throws<IllegalStateException> { saved.arguments }
            throws<IllegalStateException> { saved.receiver }
            throws<IllegalStateException> { saved.proceed(arrayOf("x")) }
        } }
        test("encase rolls back registrations when setup fails") {
            val p = ReflectionPlatform()
            throws<IllegalStateException> {
                RoxyHook.encase(p, PackageContext("demo.app", "demo.app", loader)) {
                    greet.hook { replaceTo("hooked") }
                    error("setup failed")
                }
            }
            equal(0, p.registrationCount)
        }
        test("fatal callback failures are not protected") { scenario { p, r, f ->
            val fatal = object : VirtualMachineError("test only") {}
            r.hook(greet) { before { throw fatal } }
            check(throws<VirtualMachineError> { p.invoke(greet, f, "x") } === fatal)
            equal(0, f.calls)
        } }
        test("native callback SPI preserves before-original-after") {
            val p = CallbackReflectionPlatform(); val f = Fixture()
            RoxyRuntime(p).use { r ->
                r.hook(greet) { before { args[0] = "Roxy" }; after { result = "$result!" } }
                equal("Hello Roxy!", p.invoke(greet, f, "x")); equal(1, f.calls)
            }
        }
        test("native callback SPI observes external native changes") {
            val p = CallbackReflectionPlatform(); val f = Fixture()
            RoxyRuntime(p).use { r ->
                r.hook(greet) { after { result = "$result/${args[0]}" } }
                equal("outside/changed", p.invoke(greet, f, "x", externalAfter = {
                    CallbackCompletion(CallbackOutcome.Returned("outside"), arrayOf("changed"))
                }))
            }
        }
        test("native callback SPI preserves null short circuit and priority") {
            val p = CallbackReflectionPlatform(); val order = mutableListOf<String>(); val f = Fixture()
            RoxyRuntime(p).use { r ->
                val nullable = method("nullable")
                r.hook(nullable) { priority = 1; before { order += "skipped" } }
                r.hook(nullable) { priority = 2; before { result = null }; after { order += "after" } }
                equal(null, p.invoke(nullable, f)); equal(0, f.calls); equal(listOf("after"), order)
            }
        }
        test("native callback SPI recovers target exceptions") {
            val p = CallbackReflectionPlatform(); val f = Fixture(); val boom = method("boom")
            RoxyRuntime(p).use { r ->
                r.hook(boom) { after { if (hasThrowable) result = "recovered" } }
                equal("recovered", p.invoke(boom, f)); equal(1, f.calls)
            }
        }
        test("native callback replacement protection never replays original") {
            val p = CallbackReflectionPlatform(); val f = Fixture()
            RoxyRuntime(p).use { r ->
                r.hook(greet) { replaceAny { callOriginal(); error("callback") } }
                equal("Hello x", p.invoke(greet, f, "x")); equal(1, f.calls)
            }
        }
        test("callback-only platform rejects raw interceptors explicitly") {
            val p = CallbackReflectionPlatform()
            RoxyRuntime(p).use { r ->
                throws<UnsupportedCapabilityException> { r.intercept(greet) { proceed(arguments.toTypedArray()) } }
                throws<UnsupportedCapabilityException> { r.hook(greet) { id = "unsupported"; before {} } }
            }
        }
        test("same-id replacements release stale managed handles") { scenario { p, r, f ->
            val first = r.hook(greet) { id = "stable"; replaceTo("first") }
            repeat(50) { n -> r.hook(greet) { id = "stable"; replaceTo(n.toString()) } }
            equal(1, r.hookCount); equal(1, p.registrationCount)
            throws<IllegalStateException> { first.replace { replaceTo("stale") } }
            first.unhook(); equal("49", p.invoke(greet, f, "x"))
        } }
        test("same ids on different executables remain independent") { scenario { p, r, f ->
            r.hook(greet) { id = "shared"; replaceTo("one") }
            r.hook(integer) { id = "shared"; replaceTo(2) }
            equal(2, r.hookCount); equal("one", p.invoke(greet, f, "x")); equal(2, p.invoke(integer, f, 1))
        } }
        val failures = results.count { it.second != null }
        val report = System.getProperty("roxy.report", "build/reports/roxy-contracts.xml")
        File(report).apply { parentFile?.mkdirs(); writeText(xml()) }
        println("RESULT: ${results.size - failures}/${results.size} passed; report=$report")
        check(failures == 0) { "$failures contract tests failed" }
    }
    private fun xml(): String {
        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"RoxyHook.contract\" tests=\"${results.size}\" failures=\"${results.count { it.second != null }}\">\n" +
            results.joinToString("\n") { (name, error) ->
                "  <testcase name=\"${escape(name)}\">" +
                    (error?.let { "<failure message=\"${escape(it.toString())}\">${escape(it.stackTraceToString())}</failure>" } ?: "") + "</testcase>"
            } + "\n</testsuite>\n"
    }
}
