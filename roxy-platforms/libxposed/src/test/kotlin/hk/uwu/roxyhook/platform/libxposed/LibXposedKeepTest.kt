package hk.uwu.roxyhook.platform.libxposed

import android.content.pm.ApplicationInfo
import android.os.Bundle
import hk.uwu.roxyhook.HookHandle
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.ProcessContext
import hk.uwu.roxyhook.RoxyModule
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.platform.HookInterceptor
import hk.uwu.roxyhook.platform.HookOptions
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Executable
import java.lang.reflect.Proxy

private class KeepTarget {
    fun first() = "first"
    fun second() = "second"
    fun third() = "third"
}

/** This fake proves adapter ownership, not an execution framework's cross-generation enumeration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibXposedKeepTest {
    private val first = KeepTarget::class.java.getDeclaredMethod("first")
    private val second = KeepTarget::class.java.getDeclaredMethod("second")
    private val third = KeepTarget::class.java.getDeclaredMethod("third")
    private val keep = HookOptions(hotReloadPolicy = HotReloadPolicy.KEEP)

    @Test
    fun nativeIdsAreEncodedWhileOrdinaryIdentityAndPublicIdsStayUnchanged() {
        val framework = Framework()
        val platform = LibXposedPlatform(framework.api)
        val interceptor = HookInterceptor { "first-generation" }
        val auto = platform.hook(first, keep, interceptor)
        val named = platform.hook(first, keep.copy(id = "用户"), interceptor)
        val ordinary = platform.hook(second, HookOptions(id = "business"), interceptor)
        assertNull(auto.id)
        assertEquals("用户", named.id)
        assertEquals("business", ordinary.id)
        assertEquals("roxy.keep.v1:a:50", framework.native[0].id)
        assertEquals(KeepHookId.encode(50, "用户"), framework.native[1].id)
        assertEquals("business", framework.native[2].id)
        assertTrue(framework.native.all { it.mode == XposedInterface.ExceptionMode.PASSTHROUGH })
        assertThrows(IllegalStateException::class.java) { auto.replace(interceptor) }
        ordinary.replace(interceptor)
        assertEquals(1, framework.replacements)
        assertThrows(IllegalArgumentException::class.java) {
            platform.hookClassInitializer(KeepTarget::class.java, keep, interceptor)
        }
        assertEquals(3, framework.installations)
    }

    @Test
    fun hookAllReordersAndOverlapsWithoutChangingNativeSlots() {
        val framework = Framework()
        RoxyRuntime(LibXposedPlatform(framework.api)).use { runtime ->
            val single = runtime.intercept(first, keep) { "original" }
            val group = runtime.hookAll(listOf(second, first, second)) {
                hotReloadPolicy = HotReloadPolicy.KEEP
                before { }
            }
            assertEquals(2, framework.installations)
            assertSame(single, group.handles[1])
            val overlap = runtime.hookAll(listOf(first, third)) {
                hotReloadPolicy = HotReloadPolicy.KEEP
                before { }
            }
            assertSame(single, overlap.handles[0])
            assertEquals(3, framework.installations)
            val named = runtime.hookAll(listOf(first, second)) {
                hotReloadPolicy = HotReloadPolicy.KEEP
                id = "shared"
                before { }
            }
            assertTrue(named.handles.all { it.id == "shared" })
            assertEquals(5, framework.installations)
            group.close()
            assertFalse(single.isActive)
            assertFalse(overlap.handles[0].isActive)
            assertTrue(overlap.handles[1].isActive)
            assertTrue(named.handles.all { it.isActive })
        }
        assertTrue(framework.native.none { it.active })
    }

    @Test
    fun hookAllFailureDoesNotRemovePreviouslyAdoptedKeep() {
        val framework = Framework()
        val old = framework.external(first, KeepHookId.encode(50, null))
        val platform = LibXposedPlatform(framework.api)
        RoxyRuntime(platform).use { runtime ->
            platform.adoptOldHooks(runtime, listOf(old)) { }
            framework.failAt = third
            assertThrows(IllegalStateException::class.java) {
                runtime.hookAll(listOf(first, second, third)) {
                    hotReloadPolicy = HotReloadPolicy.KEEP
                    before { }
                }
            }
            assertTrue(old.active)
            assertEquals(0, old.unhookCalls)
            assertFalse(framework.native.single { it.target == second }.active)
            assertEquals(1, runtime.hookCount)
        }
        assertFalse(old.active)
    }

    @Test
    fun invalidBatchMetadataFailsBeforeAnyNativeRegistration() {
        val framework = Framework()
        RoxyRuntime(LibXposedPlatform(framework.api)).use { runtime ->
            assertThrows(IllegalArgumentException::class.java) {
                runtime.hookAll(listOf(first, second)) {
                    hotReloadPolicy = HotReloadPolicy.KEEP
                    id = "x".repeat(KeepHookId.MAX_ID_BYTES + 1)
                    before { }
                }
            }
            assertEquals(0, framework.installations)
        }
    }

    @Test
    fun moduleRetiresAndAdoptsAcrossThreeGenerationsWithoutReplacingCallbackOrState() {
        val framework = Framework()
        val saved = Bundle().apply { putString("business.key", "untouched") }
        val aBusiness = Business("A", saved)
        val a = Entry(aBusiness).apply { attachFramework(framework.api) { } }
        a.onModuleLoaded(Loaded)
        val originalHandle = aBusiness.kept!!
        val originalNative = framework.native.first()
        val preparingA = Preparing()
        assertTrue(a.onHotReloading(preparingA))
        assertSame(saved, preparingA.saved)
        assertEquals(1, aBusiness.disposed)
        assertFalse(originalHandle.isActive)
        assertTrue(originalNative.active)
        assertEquals(0, originalNative.unhookCalls)
        originalHandle.unhook()
        a.runtime.close()
        assertTrue(originalNative.active)

        val oldListA = framework.native.toList()
        val bBusiness = Business("B", saved)
        val b = Entry(bBusiness).apply { attachFramework(framework.api) { } }
        val reloadedB = Reloaded(saved, oldListA)
        b.onHotReloaded(reloadedB)
        assertSame(reloadedB, bBusiness.received)
        assertEquals(2, b.runtime.hookCount)
        assertEquals(3, framework.installations)
        assertEquals("A", originalNative.invoke())
        assertNotSame(originalHandle, bBusiness.kept)
        assertNull(bBusiness.kept!!.id)
        assertEquals("untouched", saved.getString("business.key"))

        assertTrue(b.onHotReloading(Preparing()))
        val cBusiness = Business("C", saved)
        val c = Entry(cBusiness).apply { attachFramework(framework.api) { } }
        // Deliberately model a framework that enumerates all surviving older-generation handles.
        c.onHotReloaded(Reloaded(saved, framework.native.filter { it.active }))
        assertEquals("A", originalNative.invoke())
        assertEquals(4, framework.installations)
        assertEquals(0, framework.replacements)
        assertEquals(2, c.runtime.hookCount)
        c.runtime.close()
        assertTrue(framework.native.none { it.active })
    }

    @Test
    fun rejectedPrepareLeavesHooksAndResourcesRunning() {
        val framework = Framework()
        val business = Business("A", Bundle()).apply { accept = false }
        val entry = Entry(business).apply { attachFramework(framework.api) { } }
        entry.onModuleLoaded(Loaded)
        assertFalse(entry.onHotReloading(Preparing()))
        assertTrue(entry.runtime.isActive)
        assertTrue(business.kept!!.isActive)
        assertEquals(0, business.disposed)
        assertTrue(framework.native.all { it.active })
        entry.runtime.close()
        assertTrue(framework.native.none { it.active })
    }

    @Test
    fun malformedMetadataAndDuplicateSlotsFailClosedBeforeBusinessInstall() {
        listOf("roxy.keep.v2:a:50", "roxy.keep.v1:a:050", "roxy.keep.v1:n:50:_w").forEach { bad ->
            val framework = Framework()
            val valid = framework.external(first, KeepHookId.encode(50, null))
            val malformed = framework.external(second, bad)
            val ordinary = framework.external(third, null)
            val platform = LibXposedPlatform(framework.api)
            val runtime = RoxyRuntime(platform)
            assertThrows(Exception::class.java) {
                platform.adoptOldHooks(runtime, listOf(valid, malformed, ordinary)) {
                    fail("Business installation must not run")
                }
            }
            assertTrue(framework.native.none { it.active })
            assertEquals(0, runtime.hookCount)
        }
        val framework = Framework()
        val duplicateA = framework.external(first, KeepHookId.encode(50, null))
        val duplicateB = framework.external(first, KeepHookId.encode(60, null))
        val platform = LibXposedPlatform(framework.api)
        val runtime = RoxyRuntime(platform)
        assertThrows(IllegalArgumentException::class.java) {
            platform.adoptOldHooks(
                runtime,
                listOf(duplicateA, duplicateB)
            ) { fail("Duplicate slot") }
        }
        assertTrue(framework.native.none { it.active })
    }

    @Test
    fun installationFailureCleansAdoptedUnselectedAndNewHooksAndAggregatesCleanupErrors() {
        val framework = Framework()
        val selected = framework.external(first, KeepHookId.encode(50, null))
        val unselected = framework.external(second, KeepHookId.encode(50, "old-only"))
        val ordinary = framework.external(third, "ordinary")
        val platform = LibXposedPlatform(framework.api)
        val runtime = RoxyRuntime(platform)
        val primary = IllegalStateException("business install")
        val cleanup = IllegalStateException("native cleanup")
        unselected.unhookError = cleanup
        val thrown = assertThrows(IllegalStateException::class.java) {
            platform.adoptOldHooks(runtime, listOf(selected, unselected, ordinary)) {
                assertEquals(2, runtime.hookCount)
                assertFalse(ordinary.active)
                runtime.intercept(third) { "new" }
                throw primary
            }
        }
        assertSame(primary, thrown)
        assertTrue(thrown.suppressed.any { it === cleanup })
        assertFalse(selected.active)
        assertFalse(framework.native.last().active)
        assertTrue(unselected.unhookCalls > 0)
        unselected.unhookError = null
        unselected.unhook()
    }

    @Test
    fun ordinaryCleanupAttemptsEveryHandleEvenWhenFailuresRepeat() {
        val framework = Framework()
        val a = framework.external(first, null)
        val b = framework.external(second, null)
        val c = framework.external(third, null)
        val failure = IllegalStateException("native")
        a.unhookError = failure
        b.unhookError = failure
        val platform = LibXposedPlatform(framework.api)
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            platform.adoptOldHooks(
                RoxyRuntime(platform),
                listOf(a, b, c)
            ) { fail("No replay on cleanup failure") }
        })
        assertTrue(a.unhookCalls > 0)
        assertTrue(b.unhookCalls > 0)
        assertFalse(c.active)
    }

    private inner class Business(private val version: String, private val state: Bundle) :
        RoxyModule(), LibXposedHotReload {
        var kept: HookHandle? = null
        var received: HotReloadedParam? = null
        var disposed = 0
        var accept = true
        override fun PackageScope.onLoad() = Unit
        override fun onModuleLoaded(runtime: RoxyRuntime, process: ProcessContext) {
            install(runtime)
        }

        private fun install(runtime: RoxyRuntime) {
            kept = runtime.intercept(first, keep) { version }
            runtime.intercept(second) { version }
        }

        override fun prepareHotReload(runtime: RoxyRuntime, param: HotReloadingParam): Boolean {
            param.setSavedInstanceState(state)
            return accept
        }

        override fun installAfterHotReload(
            runtime: RoxyRuntime,
            process: ProcessContext,
            param: HotReloadedParam
        ) {
            assertSame(state, param.savedInstanceState)
            received = param
            install(runtime)
        }

        override fun onDispose() {
            disposed++
        }
    }

    private class Entry(override val module: RoxyModule) : RoxyXposedModule() {
        val runtime get() = roxy
    }

    private object Loaded : ModuleLoadedParam {
        override fun getProcessName() = "test.app"
        override fun isSystemServer() = false
    }

    private class Preparing : HotReloadingParam {
        var saved: Any? = null
        override fun getExtras(): Bundle? = null
        override fun setSavedInstanceState(outState: Any?) {
            saved = outState
        }
    }

    private class Reloaded(
        private val state: Any?,
        private val handles: List<XposedInterface.HookHandle>
    ) : HotReloadedParam {
        override fun getProcessName() = "test.app"
        override fun isSystemServer() = false
        override fun getExtras(): Bundle? = null
        override fun getSavedInstanceState() = state
        override fun getOldHookHandles() = handles
    }

    private class Framework {
        val native = mutableListOf<Native>()
        var installations = 0
        var replacements = 0
        var failAt: Executable? = null
        val api: XposedInterface = proxy(XposedInterface::class.java) { name, args ->
            when (name) {
                "getApiVersion" -> 102
                "getFrameworkName" -> "test"
                "getFrameworkVersion" -> "1"
                "getFrameworkVersionCode", "getFrameworkProperties" -> 0L
                "getModuleApplicationInfo" -> ApplicationInfo().apply {
                    packageName = "module.app"; sourceDir = "/module.apk"
                }

                "log" -> null
                "hook" -> Builder(args[0] as Executable)
                else -> error("Unexpected Xposed API call: $name")
            }
        }

        fun external(target: Executable, id: String?) =
            Native(target, id, { "external" }).also(native::add)

        private inner class Builder(private val target: Executable) : XposedInterface.HookBuilder {
            private var id: String? = null
            private var mode = XposedInterface.ExceptionMode.DEFAULT
            override fun setPriority(priority: Int) = this
            override fun setExceptionMode(mode: XposedInterface.ExceptionMode) =
                apply { this.mode = mode }

            override fun setId(id: String?) = apply { this.id = id }
            override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
                check(target != failAt) { "native install" }
                installations++
                if (id != null) native.filter { it.active && it.target == target && it.id == id }
                    .forEach { it.active = false }
                return Native(target, id, hooker, mode).also(native::add)
            }
        }

        inner class Native(
            val target: Executable,
            private val nativeId: String?,
            private val hooker: XposedInterface.Hooker,
            val mode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        ) : XposedInterface.HookHandle {
            var active = true
            var unhookCalls = 0
            var unhookError: Throwable? = null
            override fun getExecutable() = target
            override fun getId() = nativeId
            override fun unhook() {
                unhookCalls++
                unhookError?.let { throw it }
                active = false
            }

            override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
                check(active)
                replacements++
                active = false
                return Native(target, nativeId, hooker, mode).also(native::add)
            }

            fun invoke(): Any? =
                hooker.intercept(proxy(XposedInterface.Chain::class.java) { name, _ ->
                    when (name) {
                        "getExecutable" -> target
                        "getThisObject" -> null
                        "getArgs" -> emptyList<Any?>()
                        "proceed" -> "original"
                        else -> error("Unexpected chain call $name")
                    }
                })
        }
    }
}

private fun <T> proxy(type: Class<T>, body: (String, Array<out Any?>) -> Any?): T = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        body(
            method.name,
            args ?: emptyArray()
        )
    }
)!!
