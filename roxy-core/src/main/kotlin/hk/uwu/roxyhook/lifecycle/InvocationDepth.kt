package hk.uwu.roxyhook.lifecycle

import java.util.IdentityHashMap

/** Suppresses nested override -> super callbacks for the SAME instance and event on one thread. */
class InvocationDepth<K : Any> {
    private val local = ThreadLocal<IdentityHashMap<Any, MutableMap<K, Int>>>()
    fun enter(instance: Any, key: K): Boolean {
        val owners = local.get() ?: IdentityHashMap<Any, MutableMap<K, Int>>().also(local::set)
        val depths = owners.getOrPut(instance) { mutableMapOf() }
        val previous = depths[key] ?: 0
        depths[key] = previous + 1
        return previous == 0
    }
    fun exit(instance: Any, key: K): Boolean {
        val owners = checkNotNull(local.get()) { "Unbalanced lifecycle exit" }
        val depths = checkNotNull(owners[instance]) { "Unknown lifecycle instance" }
        val depth = checkNotNull(depths[key]) { "Unknown lifecycle event" }
        check(depth > 0)
        if (depth == 1) depths.remove(key) else depths[key] = depth - 1
        if (depths.isEmpty()) owners.remove(instance)
        if (owners.isEmpty()) local.remove()
        return depth == 1
    }
}
