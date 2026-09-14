package hk.uwu.roxyhook.testing

import hk.uwu.roxyhook.*
import hk.uwu.roxyhook.channel.*
import hk.uwu.roxyhook.lifecycle.InvocationDepth
import hk.uwu.roxyhook.platform.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Actual JVM tests. They do not claim to validate an Android device or upstream dependency ABI. */
object RegressionSuite {
    private var tests = 0
    private fun test(name: String, block: () -> Unit) { block(); tests++; println("PASS  $name") }
    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (error: Throwable) { check(error is T) { "Expected ${T::class.java}, got $error" }; return error }
        error("Expected ${T::class.java}")
    }
    private val loader = RegressionSuite::class.java.classLoader
    private fun scope(runtime: RoxyRuntime, system: Boolean = false, stage: LoadStage = if (system) LoadStage.SYSTEM_SERVER_STARTING else LoadStage.PACKAGE_READY,
                      name: String = "demo.app", process: String = name, main: String = name) =
        runtime.scope(PackageContext(name, process, loader, isSystemServer = system, stage = stage, mainProcessName = main, userId = 10))
    private fun packet(time: Long = 1_000_000, id: String = UUID.randomUUID().toString(), payload: ByteArray = "hello".toByteArray()) =
        ChannelPacket("module.app", "sender.app", "target.app", "topic.v1", id = id, timestampMillis = time, payload = payload)
    @JvmStatic fun main(args: Array<String>) {
        test("loadAll excludes system_server including auxiliary package events") {
            RoxyRuntime(ReflectionPlatform()).use { runtime ->
                var calls = 0
                scope(runtime, true).loadAll { calls++ }
                scope(runtime, true, LoadStage.PACKAGE_READY, name = "auxiliary.app").loadAll { calls++ }
                scope(runtime).loadAll { calls++ }
                check(calls == 1)
            }
        }
        test("loadApp with no names accepts apps only") {
            RoxyRuntime(ReflectionPlatform()).use { runtime ->
                var calls = 0
                scope(runtime).loadApp { calls++ }
                scope(runtime, true).loadApp { calls++ }
                scope(runtime).loadApp("other.app", "demo.app") { calls++ }
                check(calls == 2)
            }
        }
        test("loadSystem fires only for system-server startup") {
            RoxyRuntime(ReflectionPlatform()).use { runtime ->
                var calls = 0
                scope(runtime).loadSystem { calls++ }
                scope(runtime, true).loadSystem { calls++ }
                scope(runtime, true, LoadStage.PACKAGE_READY).loadSystem { calls++ }
                check(calls == 1)
            }
        }
        test("process short names expand; custom main process is respected") {
            RoxyRuntime(ReflectionPlatform()).use { runtime ->
                var calls = 0
                val worker = scope(runtime, process = "demo.app:worker")
                worker.process(":worker") { calls++ }
                worker.exceptProcess(":ui") { calls++ }
                worker.mainProcess { error("Worker is not main") }
                scope(runtime, process = "custom.main", main = "custom.main").mainProcess { calls++ }
                check(calls == 3 && worker.userId == 10)
            }
        }
        test("classloader scope does not mutate parent") {
            RoxyRuntime(ReflectionPlatform()).use { runtime ->
                val parent = scope(runtime); val child = object : ClassLoader(loader) {}
                parent.withClassLoader(child) { check(appClassLoader === child) }
                check(parent.appClassLoader === loader)
            }
        }
        test("RoxyModule business entry is dispatched exactly once") {
            RoxyRuntime(ReflectionPlatform()).use { runtime ->
                var calls = 0
                val entry = object : RoxyModule() { override fun PackageScope.onLoad() { loadAll { calls++ } } }
                scope(runtime).loadHooker(entry)
                scope(runtime, true).loadHooker(entry)
                check(calls == 1)
            }
        }
        test("runtime service keys are typed identity keys; disposal is once") {
            val runtime = RoxyRuntime(ReflectionPlatform())
            val key = RuntimeKey<Any>("same-name"); val other = RuntimeKey<Any>("same-name")
            val service = runtime.service(key) { Any() }
            check(runtime.service(key) { error("Must be cached") } === service)
            check(runtime.serviceOrNull(other) == null)
            var count = 0; runtime.onClose { count++ }; runtime.close(); runtime.close()
            check(count == 1 && runtime.serviceOrNull(key) == null)
            expect<IllegalStateException> { runtime.service(key) { Any() } }
        }
        test("runtime service factories do not hold the global runtime lock") {
            RoxyRuntime(ReflectionPlatform()).use { runtime ->
                val executor = Executors.newFixedThreadPool(2)
                val entered = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                try {
                    val service = executor.submit(Callable { runtime.service(RuntimeKey<Any>("blocking")) {
                        entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); Any()
                    } })
                    check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    val query = executor.submit(Callable { runtime.scope(PackageContext("app", "app", loader)) })
                    check(query.get(2, java.util.concurrent.TimeUnit.SECONDS).packageName == "app")
                    release.countDown(); service.get(2, java.util.concurrent.TimeUnit.SECONDS)
                } finally { release.countDown(); executor.shutdownNow() }
            }
        }
        test("failed service factories retry and cyclic factories fail instead of hanging") {
            RoxyRuntime(ReflectionPlatform()).use { runtime ->
                val key = RuntimeKey<Any>("retry")
                expect<IllegalStateException> { runtime.service(key) { error("first attempt") } }
                check(runtime.serviceOrNull(key) == null)
                val value = runtime.service(key) { Any() }
                check(runtime.serviceOrNull(key) === value)
                val cycle = RuntimeKey<Any>("cycle")
                expect<IllegalStateException> { runtime.service(cycle) { runtime.service(cycle) { Any() } } }
                check(runtime.serviceOrNull(cycle) == null)
            }
        }
        test("close cleans every hook and resource even when cleanup throws") {
            val platform = ReflectionPlatform(); val runtime = RoxyRuntime(platform)
            val method = Fixture::class.java.getDeclaredMethod("greet", String::class.java)
            val handle = runtime.hook(method) { before {} }
            val calls = mutableListOf<String>()
            runtime.onClose { calls += "first"; error("first cleanup") }
            runtime.onClose { calls += "second"; error("second cleanup") }
            val error = expect<IllegalStateException> { runtime.close() }
            check(calls == listOf("second", "first") && error.suppressed.size == 1)
            check(platform.registrationCount == 0 && !handle.isActive && runtime.isClosed)
        }
        test("failure observer receives phase and never replays an original call") {
            val platform = ReflectionPlatform(); val fixture = Fixture()
            RoxyRuntime(platform).use { runtime ->
                val method = Fixture::class.java.getDeclaredMethod("greet", String::class.java)
                var phase = ""
                runtime.hook(method) {
                    onFailure { phase = it.phase; check(it.member == method) }
                    replace { callOriginal(); error("replacement failed after original") }
                }
                check(platform.invoke(method, fixture, "World") == "Hello World")
                check(fixture.calls == 1 && phase == "REPLACE")
            }
        }
        test("throwing failure observers do not prevent fallback") {
            val platform = ReflectionPlatform(); val fixture = Fixture()
            RoxyRuntime(platform).use { runtime ->
                val method = Fixture::class.java.getDeclaredMethod("greet", String::class.java)
                runtime.hook(method) { onFailure { error("observer") }; before { error("hook") } }
                check(platform.invoke(method, fixture, "x") == "Hello x" && fixture.calls == 1)
            }
        }
        test("test-platform creation is not reported as Xposed activation") {
            RoxyRuntime(ReflectionPlatform()).use { check(!RoxyHook.isActive) }
            val platform = object : HookPlatform by ReflectionPlatform() {
                override val info = PlatformInfo("fixture injection", "1", 102, isInjected = true)
            }
            RoxyRuntime(platform).use { check(RoxyHook.isActive && RoxyHook.apiVersion == 102) }
            check(!RoxyHook.isActive)
        }
        test("lifecycle nesting suppresses super duplicates and cleans depth") {
            val depth = InvocationDepth<String>(); val owner = Any()
            check(depth.enter(owner, "create")); check(!depth.enter(owner, "create"))
            check(!depth.exit(owner, "create")); check(depth.exit(owner, "create"))
            check(depth.enter(owner, "create")); check(depth.exit(owner, "create"))
            expect<IllegalStateException> { depth.exit(owner, "create") }
        }
        test("lifecycle depth uses object identity not equals") {
            data class Equal(val n: Int)
            val one = Equal(1); val two = Equal(1); val depth = InvocationDepth<String>()
            check(depth.enter(one, "a") && depth.enter(two, "a"))
            check(depth.exit(one, "a") && depth.exit(two, "a"))
        }
        test("lifecycle depths are thread local") {
            val depth = InvocationDepth<String>(); val owner = Any(); val executor = Executors.newFixedThreadPool(2)
            try {
                check(depth.enter(owner, "a"))
                check(executor.submit(Callable { depth.enter(owner, "a") && depth.exit(owner, "a") }).get())
                check(depth.exit(owner, "a"))
            } finally { executor.shutdownNow() }
        }
        test("channel binary packet roundtrip") {
            val source = packet(payload = byteArrayOf(0, 1, 127, -128, -1))
            ChannelAuthenticator(ByteArray(32) { it.toByte() }).use { codec ->
                val result = codec.decode(codec.encode(source))
                check(result.id == source.id && result.timestampMillis == source.timestampMillis)
                check(result.payload.contentEquals(source.payload) && result.sender == "sender.app")
            }
        }
        test("channel text roundtrip preserves Unicode") {
            ChannelAuthenticator(ChannelAuthenticator.newSecret()).use { codec ->
                check(codec.decode(codec.encode(packet(payload = "你好，Roxy".toByteArray()))).text() == "你好，Roxy")
            }
        }
        test("channel payloads are defensively copied") {
            val bytes = byteArrayOf(1, 2); val packet = packet(payload = bytes)
            bytes[0] = 9; packet.payload[0] = 8
            check(packet.payload.contentEquals(byteArrayOf(1, 2)))
        }
        test("channel tampering is rejected before delivery") {
            ChannelAuthenticator(ChannelAuthenticator.newSecret()).use { codec ->
                val wire = codec.encode(packet())
                for (index in listOf(0, 12, wire.size - 1)) {
                    val tampered = wire.copyOf(); tampered[index] = (tampered[index].toInt() xor 1).toByte()
                    expect<IllegalArgumentException> { codec.decode(tampered) }
                }
            }
        }
        test("channel rejects other keys, truncation and oversized payloads") {
            ChannelAuthenticator(ByteArray(32) { 1 }).use { a -> ChannelAuthenticator(ByteArray(32) { 2 }).use { b ->
                expect<IllegalArgumentException> { b.decode(a.encode(packet())) }
                expect<IllegalArgumentException> { a.decode(byteArrayOf(1)) }
                expect<IllegalArgumentException> { a.decode(ByteArray(ChannelAuthenticator.MAX_WIRE_BYTES + 1)) }
                expect<IllegalArgumentException> { packet(payload = ByteArray(ChannelPacket.MAX_PAYLOAD_BYTES + 1)) }
            } }
        }
        test("channel accepts maximum payload") {
            ChannelAuthenticator(ChannelAuthenticator.newSecret()).use { codec ->
                val wire = codec.encode(packet(payload = ByteArray(ChannelPacket.MAX_PAYLOAD_BYTES) { (it % 256).toByte() }))
                check(wire.size <= ChannelAuthenticator.MAX_WIRE_BYTES)
                check(codec.decode(wire).payload.size == ChannelPacket.MAX_PAYLOAD_BYTES)
            }
        }
        test("channel key conversion is exact and validates before decoding") {
            val key = ByteArray(32) { it.toByte() }; val hex = ChannelAuthenticator.secretToHex(key)
            check(hex.length == 64 && ChannelAuthenticator.secretFromHex(hex).contentEquals(key))
            expect<IllegalArgumentException> { ChannelAuthenticator.secretFromHex("bad") }
            expect<IllegalArgumentException> { ChannelAuthenticator(ByteArray(31)) }
        }
        test("closed authenticator rejects use and never mutates caller secret") {
            val key = ByteArray(32) { 3 }; val codec = ChannelAuthenticator(key); codec.close(); codec.close()
            check(key.all { it == 3.toByte() })
            expect<IllegalStateException> { codec.encode(packet()) }
        }
        test("channel routing and topic validate; reply carries correlation id") {
            expect<IllegalArgumentException> { ChannelPacket("bad\nmodule", "a", "b", "topic", payload = byteArrayOf()) }
            expect<IllegalArgumentException> { ChannelPacket("a", "b", "c", "bad/topic", payload = byteArrayOf()) }
            val source = packet(); val reply = ChannelPacket(source.module, source.target, source.sender, source.topic,
                replyTo = source.id, payload = byteArrayOf())
            ChannelAuthenticator(ChannelAuthenticator.newSecret()).use { codec ->
                check(codec.decode(codec.encode(reply)).replyTo == source.id)
            }
        }
        test("replay window rejects duplicate and stale messages") {
            val replay = ReplayWindow(); val source = packet()
            check(replay.accept(source, 1_000_000)); check(!replay.accept(source, 1_000_001))
            check(!replay.accept(packet(time = 1), 1_000_000))
            check(!replay.accept(packet(time = 1_040_000), 1_000_000))
        }
        test("replay window bounds memory without evicting live nonces") {
            val replay = ReplayWindow(ttlMillis = 100, capacity = 1, futureToleranceMillis = 0)
            val one = packet(time = 1000); val two = packet(time = 1000)
            check(replay.accept(one, 1000)); check(!replay.accept(two, 1000)); check(!replay.accept(one, 1000))
            check(replay.accept(packet(time = 1101), 1101)); replay.clear()
            check(replay.accept(packet(time = 1101), 1101))
        }
        test("replay timestamp arithmetic rejects malicious far future") {
            check(!ReplayWindow().accept(packet(time = Long.MAX_VALUE), 1))
        }
        println("RESULT: $tests/$tests regression tests passed")
    }
}
