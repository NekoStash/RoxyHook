package hk.uwu.roxyhook.kavaref

import com.highcapable.kavaref.resolver.ConstructorResolver
import com.highcapable.kavaref.resolver.MethodResolver
import hk.uwu.roxyhook.HookBuilder
import hk.uwu.roxyhook.HookGroup
import hk.uwu.roxyhook.HookHandle
import hk.uwu.roxyhook.PackageScope

/** Compatibility bridge; KavaRef is included by roxy-core. KavaRef remains the real resolver; no names are reimplemented or shadowed. */
fun MethodResolver<*>.hook(scope: PackageScope, block: HookBuilder.() -> Unit): HookHandle =
    scope.runtime.hook(self, block)
fun ConstructorResolver<*>.hook(scope: PackageScope, block: HookBuilder.() -> Unit): HookHandle =
    scope.runtime.hook(self, block)

fun PackageScope.kava(block: KavaHookScope.() -> Unit) { KavaHookScope(this).block() }

/** One explicit kava { } block enables the scope-free resolver.hook { } syntax. */
class KavaHookScope(val packageScope: PackageScope) {
    val appClassLoader get() = packageScope.appClassLoader
    fun String.toClass(): Class<*> = with(packageScope) { this@toClass.toClass() }
    fun MethodResolver<*>.hook(block: HookBuilder.() -> Unit): HookHandle = packageScope.runtime.hook(self, block)
    fun ConstructorResolver<*>.hook(block: HookBuilder.() -> Unit): HookHandle = packageScope.runtime.hook(self, block)
    @JvmName("hookMethods")
    fun List<MethodResolver<*>>.hook(block: HookBuilder.() -> Unit): HookGroup =
        packageScope.runtime.hookAll(map { it.self }, block)
    @JvmName("hookConstructors")
    fun List<ConstructorResolver<*>>.hook(block: HookBuilder.() -> Unit): HookGroup =
        packageScope.runtime.hookAll(map { it.self }, block)
}
