package hk.uwu.roxyhook.reflect

import hk.uwu.roxyhook.HookBuilder
import hk.uwu.roxyhook.HookGroup
import hk.uwu.roxyhook.RoxyDsl
import hk.uwu.roxyhook.RoxyRuntime
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method

class NoSuchMemberException(message: String) : ReflectiveOperationException(message)
class AmbiguousMemberException(message: String) : ReflectiveOperationException(message)

private fun modifierMask(modifiers: IntArray): Int {
    require(modifiers.all { it >= 0 }) { "Modifier masks must not be negative" }
    return modifiers.fold(0) { mask, modifier -> mask or modifier }
}

private fun matchesModifiers(member: Member, required: Int, excluded: Int): Boolean =
    member.modifiers and required == required && member.modifiers and excluded == 0

@RoxyDsl
open class ExecutableQuery internal constructor(private val loader: ClassLoader) {
    var paramCount: Int? = null
    internal var parameters: List<Class<*>?>? = null
    private var predicate: (Executable) -> Boolean = { true }
    private var requiredModifiers = 0
    private var excludedModifiers = 0
    fun param(vararg types: Any) { parameters = types.map { if (it === VagueType) null else resolveType(it, loader) } }
    fun emptyParam() = param()

    /** Require every supplied [java.lang.reflect.Modifier] bit on a matched executable. */
    fun requireModifier(vararg modifiers: Int) {
        requiredModifiers = requiredModifiers or modifierMask(modifiers)
    }

    /** Reject an executable when any supplied [java.lang.reflect.Modifier] bit is present. */
    fun excludeModifier(vararg modifiers: Int) {
        excludedModifiers = excludedModifiers or modifierMask(modifiers)
    }
    fun where(condition: (Executable) -> Boolean) {
        val previous = predicate; predicate = { previous(it) && condition(it) }
    }
    internal fun matches(member: Executable): Boolean {
        require(paramCount == null || paramCount!! >= 0) { "paramCount must not be negative" }
        val types = parameters
        if (paramCount != null && member.parameterCount != paramCount) return false
        if (types != null && (types.size != member.parameterCount ||
            types.indices.any { types[it] != null && types[it] != member.parameterTypes[it] })) return false
        return matchesModifiers(member, requiredModifiers, excludedModifiers) && predicate(member)
    }
}
@RoxyDsl
class MethodQuery internal constructor(loader: ClassLoader) : ExecutableQuery(loader) {
    var name: String? = null
    var returnType: Class<*>? = null
    var includeSynthetic: Boolean = false
    internal var searchParents: Boolean = false
    internal var searchInterfaces: Boolean = false
    fun superClass() { searchParents = true }

    /** Include methods declared by this class's interfaces and their parent interfaces. */
    fun interfaces() {
        searchInterfaces = true
    }

    /** Include both superclass and interface declarations in the search. */
    fun allParents() {
        searchParents = true; searchInterfaces = true
    }
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
    fun addMethods(owner: Class<*>) {
        owner.declaredMethods.sortedBy { it.toGenericString() }.forEach { method ->
            // Closest declaration wins for an overridden signature. Interface traversal is opt-in.
            val key = method.name + method.parameterTypes.joinToString(",", "(", ")") { it.name } + method.returnType.name
            if (query.accepts(method)) result.putIfAbsent(key, method)
        }
    }

    var current: Class<*>? = type
    while (current != null) {
        addMethods(current)
        current = if (query.searchParents) current.superclass else null
    }
    if (query.searchInterfaces) {
        val visited = mutableSetOf<Class<*>>()
        fun visit(interfaceType: Class<*>) {
            if (!visited.add(interfaceType)) return
            addMethods(interfaceType)
            interfaceType.interfaces.sortedBy { it.name }.forEach(::visit)
        }
        current = type
        while (current != null) {
            current.interfaces.sortedBy { it.name }.forEach(::visit)
            current = current.superclass
        }
    }
    return result.values.toList()
}

@RoxyDsl
class FieldQuery internal constructor() {
    var name: String? = null
    var type: Class<*>? = null
    internal var searchParents = false
    internal var searchInterfaces = false
    private var requiredModifiers = 0
    private var excludedModifiers = 0
    fun superClass() { searchParents = true }

    /** Include fields declared by this class's interfaces and their parent interfaces. */
    fun interfaces() {
        searchInterfaces = true
    }

    /** Include both superclass and interface declarations in the search. */
    fun allParents() {
        searchParents = true; searchInterfaces = true
    }

    /** Require every supplied [java.lang.reflect.Modifier] bit on a matched field. */
    fun requireModifier(vararg modifiers: Int) {
        requiredModifiers = requiredModifiers or modifierMask(modifiers)
    }

    /** Reject a field when any supplied [java.lang.reflect.Modifier] bit is present. */
    fun excludeModifier(vararg modifiers: Int) {
        excludedModifiers = excludedModifiers or modifierMask(modifiers)
    }

    internal fun accepts(field: Field): Boolean =
        (name == null || field.name == name) && (type == null || field.type == type) &&
            matchesModifiers(field, requiredModifiers, excludedModifiers)
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
        val candidates = current.declaredFields.filter(query::accepts)
        if (candidates.size > 1) throw AmbiguousMemberException("Multiple fields in ${current.name}")
        candidates.singleOrNull()?.let { return FieldAccess(it) }
        current = if (query.searchParents) current.superclass else null
    }
    if (query.searchInterfaces) {
        val candidates = mutableListOf<Field>()
        val visited = mutableSetOf<Class<*>>()
        fun visit(interfaceType: Class<*>) {
            if (!visited.add(interfaceType)) return
            interfaceType.declaredFields.filter(query::accepts).forEach { field ->
                if (field !in candidates) candidates += field
            }
            interfaceType.interfaces.sortedBy { it.name }.forEach(::visit)
        }
        current = type
        while (current != null) {
            current.interfaces.sortedBy { it.name }.forEach(::visit)
            current = current.superclass
        }
        if (candidates.size > 1) throw AmbiguousMemberException("Multiple interface fields in ${type.name}")
        candidates.singleOrNull()?.let { return FieldAccess(it) }
    }
    throw NoSuchMemberException("No field ${query.name} in ${type.name}")
}
