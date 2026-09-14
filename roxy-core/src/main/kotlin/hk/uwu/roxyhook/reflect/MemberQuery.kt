package hk.uwu.roxyhook.reflect

import hk.uwu.roxyhook.*
import java.lang.reflect.*

class NoSuchMemberException(message: String) : ReflectiveOperationException(message)
class AmbiguousMemberException(message: String) : ReflectiveOperationException(message)

@RoxyDsl
open class ExecutableQuery internal constructor(private val loader: ClassLoader) {
    var paramCount: Int? = null
    internal var parameters: List<Class<*>?>? = null
    private var predicate: (Executable) -> Boolean = { true }
    fun param(vararg types: Any) { parameters = types.map { if (it === VagueType) null else resolveType(it, loader) } }
    fun emptyParam() = param()
    fun where(condition: (Executable) -> Boolean) {
        val previous = predicate; predicate = { previous(it) && condition(it) }
    }
    internal fun matches(member: Executable): Boolean {
        require(paramCount == null || paramCount!! >= 0) { "paramCount must not be negative" }
        val types = parameters
        if (paramCount != null && member.parameterCount != paramCount) return false
        if (types != null && (types.size != member.parameterCount ||
            types.indices.any { types[it] != null && types[it] != member.parameterTypes[it] })) return false
        return predicate(member)
    }
}
@RoxyDsl
class MethodQuery internal constructor(loader: ClassLoader) : ExecutableQuery(loader) {
    var name: String? = null
    var returnType: Class<*>? = null
    var includeSynthetic: Boolean = false
    internal var searchParents: Boolean = false
    fun superClass() { searchParents = true }
    internal fun accepts(method: Method): Boolean = (name == null || method.name == name) &&
        (returnType == null || method.returnType == returnType) &&
        (includeSynthetic || (!method.isSynthetic && !method.isBridge)) && matches(method)
}
@RoxyDsl
class ConstructorQuery internal constructor(loader: ClassLoader) : ExecutableQuery(loader)

/** Unique by default. Never silently select an arbitrary overload. */
class MemberSelection<T : Executable> internal constructor(
    private val runtime: RoxyRuntime,
    candidates: List<T>,
    private val description: String,
    private val mode: SelectionMode = SelectionMode.UNIQUE
) {
    val members: List<T> = candidates.toList()
    fun all() = MemberSelection(runtime, members, description, SelectionMode.ALL)
    fun first() = MemberSelection(runtime, members.take(1), description, SelectionMode.UNIQUE)
    fun single(): T {
        if (members.isEmpty()) throw NoSuchMemberException("No match: $description")
        if (members.size != 1) throw AmbiguousMemberException("${members.size} matches: $description; specify param(...) or all()")
        return members.single()
    }
    fun hook(block: HookBuilder.() -> Unit): HookGroup = runtime.hookAll(
        if (mode == SelectionMode.ALL) members.ifEmpty { throw NoSuchMemberException("No match: $description") }
        else listOf(single()), block
    )
    /** Only absence is optional. Ambiguity and installation failures still propagate. */
    fun hookIfExists(block: HookBuilder.() -> Unit): HookGroup? = if (members.isEmpty()) null else hook(block)
}
internal enum class SelectionMode { UNIQUE, ALL }

internal fun findMethods(type: Class<*>, query: MethodQuery): List<Method> {
    val result = linkedMapOf<String, Method>()
    var current: Class<*>? = type
    while (current != null) {
        current.declaredMethods.sortedBy { it.toGenericString() }.forEach { method ->
            // Closest declaration wins for an overridden signature. Interface traversal is not implicit.
            val key = method.name + method.parameterTypes.joinToString(",", "(", ")") { it.name } + method.returnType.name
            if (query.accepts(method)) result.putIfAbsent(key, method)
        }
        current = if (query.searchParents) current.superclass else null
    }
    return result.values.toList()
}

@RoxyDsl
class FieldQuery internal constructor() {
    var name: String? = null
    var type: Class<*>? = null
    internal var searchParents = false
    fun superClass() { searchParents = true }
}
class FieldAccess internal constructor(private val reflectedField: Field, private val receiver: Any? = null) {
    val member: Field get() = reflectedField
    fun of(instance: Any?) = FieldAccess(reflectedField, instance)
    fun get(): Any? { reflectedField.isAccessible = true; return reflectedField.get(receiver) }
    inline fun <reified T> cast(): T = get() as T
    fun set(value: Any?) { reflectedField.isAccessible = true; reflectedField.set(receiver, value) }
}
internal fun findField(type: Class<*>, query: FieldQuery): FieldAccess {
    var current: Class<*>? = type
    while (current != null) {
        val candidates = current.declaredFields.filter {
            (query.name == null || it.name == query.name) && (query.type == null || it.type == query.type)
        }
        if (candidates.size > 1) throw AmbiguousMemberException("Multiple fields in ${current.name}")
        candidates.singleOrNull()?.let { return FieldAccess(it) }
        current = if (query.searchParents) current.superclass else null
    }
    throw NoSuchMemberException("No field ${query.name} in ${type.name}")
}
