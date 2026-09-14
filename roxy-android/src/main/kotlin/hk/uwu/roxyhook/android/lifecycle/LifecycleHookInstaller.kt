package hk.uwu.roxyhook.android.lifecycle

import android.content.ContentProvider
import android.content.Context
import hk.uwu.roxyhook.HookHandle
import hk.uwu.roxyhook.HookPriority
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.lifecycle.InvocationDepth
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class LifecycleHookInstaller(private val runtime: RoxyRuntime, private val registry: LifecycleRegistry) : AutoCloseable {
    private val handles = linkedMapOf<Pair<Executable, String>, HookHandle>()
    private val lock = Any()
    private val depth = InvocationDepth<LifecycleKind>()
    @Volatile private var closed = false

    fun root(members: List<Executable>, label: String, afterConstruction: Boolean = false, action: (Any) -> Unit) {
        check(members.isNotEmpty()) { "Android lifecycle root $label is unavailable on this runtime" }
        members.forEach { member ->
            synchronized(lock) {
                check(!closed)
                handles.getOrPut(member to label) {
                    runtime.hook(member) {
                        priority = HookPriority.HIGHEST
                        if (afterConstruction) after { if (!hasThrowable) instance?.let(action) }
                        else before { instance?.let(action) }
                    }
                }
            }
        }
    }
    /** Find the actual override BEFORE its first invocation, including overrides that omit super. */
    fun virtual(type: Class<*>, owner: Class<*>, name: String, kind: LifecycleKind,
                accepts: (Method) -> Boolean = { true }) {
        val selected = linkedMapOf<List<Class<*>>, Method>()
        var cursor: Class<*>? = type
        while (cursor != null && cursor != Any::class.java) {
            cursor.declaredMethods.filter { it.name == name && !it.isBridge && !it.isSynthetic &&
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) && accepts(it) }
                .forEach { selected.putIfAbsent(it.parameterTypes.toList(), it) }
            cursor = cursor.superclass
        }
        selected.values.forEach { method -> install(method, owner, kind) }
    }
    private fun install(method: Method, owner: Class<*>, kind: LifecycleKind) = synchronized(lock) {
        check(!closed)
        handles.getOrPut(method to kind.name) {
            runtime.hook(method) {
                priority = HookPriority.HIGHEST
                before {
                    val target = instance ?: return@before
                    if (!owner.isInstance(target)) return@before
                    val outer = depth.enter(target, kind)
                    extras["roxy.lifecycle.entered"] = true
                    if (outer) event(target, kind, LifecyclePhase.BEFORE, args, null)?.let(registry::publish)
                }
                after {
                    val target = instance ?: return@after
                    if (extras["roxy.lifecycle.entered"] != true) return@after
                    if (depth.exit(target, kind)) event(target, kind, LifecyclePhase.AFTER, args, throwable)?.let(registry::publish)
                }
            }
        }
        Unit
    }
    private fun event(target: Any, kind: LifecycleKind, phase: LifecyclePhase, args: Array<Any?>,
                      failure: Throwable?): LifecycleEvent? {
        val attachment = kind == LifecycleKind.APPLICATION_ATTACH || kind == LifecycleKind.APPLICATION_ATTACH_BASE_CONTEXT ||
            kind == LifecycleKind.PROVIDER_ATTACH
        val context = (if (attachment) args.firstOrNull { it is Context } as? Context else null)
            ?: (target as? ContentProvider)?.context ?: target as? Context ?: return null
        return LifecycleEvent(kind, phase, target, context, args.toList(), failure)
    }
    override fun close() {
        val snapshot = synchronized(lock) {
            if (closed) return
            closed = true
            handles.values.toList().asReversed().also { handles.clear() }
        }
        var failure: Throwable? = null
        snapshot.forEach { try { it.unhook() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        failure?.let { throw it }
    }
}
