package hk.uwu.roxyhook.android

import android.content.Context
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.RuntimeKey

/**
 * Minimal injectable boundary behind [PackageScope.systemContext]. Production uses
 * [HiddenApiSystemContextResolver]; tests substitute a fake through
 * [SystemContextResolver.installForTest]. Contract: either return a non-null [Context] or throw —
 * the result is never silently replaced with a fallback context.
 */
fun interface SystemContextResolver {
    /** Resolve the system context for the given scope. Implementations must throw on failure. */
    fun resolve(scope: PackageScope): Context

    companion object {
        /**
         * One runtime-scoped slot. The production default is [HiddenApiSystemContextResolver];
         * installing a fake here is the only supported override and exists for tests.
         */
        private val KEY = RuntimeKey<SystemContextResolver>("android.systemContextResolver")

        /** Resolver bound to this scope's runtime; production code never calls this directly. */
        fun of(scope: PackageScope): SystemContextResolver =
            scope.runtime.service(KEY) { HiddenApiSystemContextResolver }

        /**
         * Test seam: pre-seed the runtime slot with a fake resolver before [systemContext] is read.
         * The override lives exactly as long as the runtime; there is no global state to leak.
         */
        fun installForTest(runtime: RoxyRuntime, resolver: SystemContextResolver) {
            runtime.service(KEY) { resolver }
        }
    }
}

/**
 * Production resolver authorized to use the hidden `ActivityThread.currentActivityThread()` /
 * `getSystemContext()` API in system_server (same mechanism as YukiHookAPI). Every failure mode —
 * class or method absent, hidden-API restriction, dead thread, null or wrongly-typed result —
 * surfaces as an [IllegalStateException] carrying the cause. No fallback, no caching.
 */
object HiddenApiSystemContextResolver : SystemContextResolver {
    override fun resolve(scope: PackageScope): Context {
        val thread = try {
            val type = Class.forName("android.app.ActivityThread", false, scope.appClassLoader)
            type.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
        } catch (failure: IllegalStateException) {
            throw failure // Already a contract failure carrying its own context; never wrap it again.
        } catch (failure: Exception) {
            // ReflectiveOperationException and RuntimeException (SecurityException, hidden-API
            // enforcement, InaccessibleObjectException) all surface as IllegalStateException with
            // the original cause attached. Error subclasses are never caught and propagate.
            throw IllegalStateException("ActivityThread.currentActivityThread() is unavailable or restricted", failure)
        }
        checkNotNull(thread) { "ActivityThread is not initialized" }
        val resolved = try {
            thread.javaClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread)
        } catch (failure: IllegalStateException) {
            throw failure // Already a contract failure carrying its own context; never wrap it again.
        } catch (failure: Exception) {
            throw IllegalStateException("ActivityThread.getSystemContext() is unavailable or restricted", failure)
        }
        return resolved as? Context
            ?: throw IllegalStateException("ActivityThread.getSystemContext() returned ${resolved?.javaClass?.name}")
    }
}
