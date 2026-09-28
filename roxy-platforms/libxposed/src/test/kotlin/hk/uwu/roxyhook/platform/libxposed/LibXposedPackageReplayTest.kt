package hk.uwu.roxyhook.platform.libxposed

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import hk.uwu.roxyhook.LoadStage
import hk.uwu.roxyhook.PackageContext
import hk.uwu.roxyhook.ProcessContext
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.android.ApplicationInfoSnapshot
import hk.uwu.roxyhook.testing.ReflectionPlatform
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Executable

private class ReplayTarget {
    fun greet() = "hello"
}

private class ReplayApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibXposedPackageReplayTest {
    private val target = ReplayTarget::class.java.getDeclaredMethod("greet")
    private val loader = checkNotNull(ReplayTarget::class.java.classLoader)
    private val packageName = ReplayTarget::class.java.packageName
    private val process = ProcessContext(packageName, false, "module.app", "/module.apk")

    @Test
    fun restoresDeliveredPackageContextAndRetainsItsStateKeys() {
        val old = LibXposedPackageReplay("existing.reload.")
        val info = ApplicationInfo().apply {
            this.packageName = this@LibXposedPackageReplayTest.packageName
        }
        old.record(
            PackageContext(
                packageName,
                process.processName,
                loader,
                isFirstPackage = false,
                stage = LoadStage.PACKAGE_READY,
                mainProcessName = "other.process",
                userId = 10,
                modulePackageName = "module.app",
                moduleApkPath = "/module.apk",
                platformSnapshot = ApplicationInfoSnapshot(info)
            )
        )
        val preparing = Preparing()
        assertEquals(true, old.prepare(preparing))
        val saved = preparing.saved as Bundle
        assertEquals(1, saved.getInt("existing.reload.version"))
        assertEquals(1, saved.getInt("existing.reload.count"))

        val next = LibXposedPackageReplay("existing.reload.")
        RoxyRuntime(ReflectionPlatform()).use { runtime ->
            var called = 0
            assertEquals(
                1,
                next.replay(runtime, process, Reloaded(saved, listOf(handle(target)))) { scope ->
                    called++
                    assertEquals(packageName, scope.packageName)
                    assertSame(loader, scope.appClassLoader)
                    assertEquals(false, scope.isFirstPackage)
                    assertEquals(false, scope.isMainProcess)
                    assertEquals(10, scope.userId)
                    assertEquals(
                        packageName,
                        (scope.context.platformSnapshot as ApplicationInfoSnapshot).applicationInfo.packageName
                    )
                })
            assertEquals(1, called)
        }
        val nextPreparing = Preparing()
        assertEquals(true, next.prepare(nextPreparing))
        assertEquals(1, (nextPreparing.saved as Bundle).getInt("existing.reload.count"))
    }

    @Test
    fun skipsScopeWhenNoTargetLoaderCanBeRecovered() {
        val old = LibXposedPackageReplay()
        old.record(PackageContext(packageName, process.processName, loader))
        val preparing = Preparing()
        assertEquals(true, old.prepare(preparing))
        RoxyRuntime(ReflectionPlatform()).use { runtime ->
            val new = LibXposedPackageReplay()
            assertEquals(0, new.replay(runtime, process, Reloaded(preparing.saved, emptyList())) {
                error("No loader must mean no installation")
            })
            assertEquals(
                0,
                new.replay(runtime, process, Reloaded(Bundle(), listOf(handle(target)))) {
                    error("Invalid state must mean no installation")
                })
        }
    }

    @Test
    fun acceptsExistingModuleBundleLayout() {
        val saved = Bundle().apply {
            putInt("reareye.reload.version", 1)
            putInt("reareye.reload.count", 1)
            putBundle("reareye.reload.context.0", Bundle().apply {
                putString("package", packageName)
                putString("process", process.processName)
                putBoolean("firstPackage", false)
                putBoolean("systemServer", false)
                putString("stage", "PACKAGE_READY")
                putString("mainProcess", packageName)
                putInt("userId", 10)
                putString("modulePackage", "module.app")
                putString("moduleApkPath", "/module.apk")
            })
        }
        RoxyRuntime(ReflectionPlatform()).use { runtime ->
            assertEquals(
                1, LibXposedPackageReplay("reareye.reload.")
                    .replay(runtime, process, Reloaded(saved, listOf(handle(target)))) { scope ->
                        assertSame(loader, scope.appClassLoader)
                        assertEquals(false, scope.isFirstPackage)
                        assertEquals(10, scope.userId)
                    })
        }
    }

    @Test
    fun provesLoaderFromSavedApplicationClassName() {
        val targetPackage = "com.xiaomi.subscreencenter"
        val targetProcess = ProcessContext(targetPackage, false, "module.app", "/module.apk")
        val info = ApplicationInfo().apply {
            packageName = targetPackage
            className = ReplayApplication::class.java.name
        }
        val old = LibXposedPackageReplay()
        old.record(
            PackageContext(
                targetPackage,
                targetProcess.processName,
                loader,
                platformSnapshot = ApplicationInfoSnapshot(info),
            )
        )
        val preparing = Preparing()
        assertEquals(true, old.prepare(preparing))

        RoxyRuntime(ReflectionPlatform()).use { runtime ->
            assertEquals(
                1,
                LibXposedPackageReplay().replay(
                    runtime,
                    targetProcess,
                    Reloaded(preparing.saved, listOf(handle(target))),
                ) { scope -> assertSame(loader, scope.appClassLoader) },
            )
        }
    }

    @Test
    fun systemServerAcceptsItsOnlyTargetLoader() {
        val old = LibXposedPackageReplay()
        old.record(
            PackageContext(
                "android", "system_server", loader,
                isSystemServer = true, stage = LoadStage.SYSTEM_SERVER_STARTING
            )
        )
        val preparing = Preparing()
        assertEquals(true, old.prepare(preparing))
        RoxyRuntime(ReflectionPlatform()).use { runtime ->
            assertEquals(
                1, LibXposedPackageReplay().replay(
                    runtime,
                    ProcessContext("system_server", true, "module.app"),
                    Reloaded(preparing.saved, listOf(handle(target)))
                ) { scope ->
                    assertEquals(true, scope.isSystemServer)
                    assertEquals(LoadStage.SYSTEM_SERVER_STARTING, scope.stage)
                })
        }
    }

    private class Preparing : HotReloadingParam {
        override fun getExtras(): Bundle? = null
        var saved: Any? = null
        override fun setSavedInstanceState(outState: Any?) {
            saved = outState
        }
    }

    private class Reloaded(
        private val saved: Any?, private val handles: List<XposedInterface.HookHandle>
    ) : HotReloadedParam {
        override fun getExtras(): Bundle? = null
        override fun getSavedInstanceState(): Any? = saved
        override fun getOldHookHandles(): List<XposedInterface.HookHandle> = handles
        override fun getProcessName() = "test.process"
        override fun isSystemServer() = false
    }

    private fun handle(executable: Executable) = object : XposedInterface.HookHandle {
        override fun getExecutable(): Executable = executable
        override fun getId(): String? = null
        override fun unhook() = Unit
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle =
            error("Not used")
    }
}
