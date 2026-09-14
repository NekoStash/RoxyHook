@file:Suppress("PropertyName")
package hk.uwu.roxyhook.reflect

import java.lang.reflect.Executable
import java.lang.reflect.Method
import kotlin.reflect.KClass

val StringType: Class<String> get() = String::class.java
val IntType: Class<Int> get() = Int::class.javaPrimitiveType!!
val LongType: Class<Long> get() = Long::class.javaPrimitiveType!!
val BooleanType: Class<Boolean> get() = Boolean::class.javaPrimitiveType!!
val FloatType: Class<Float> get() = Float::class.javaPrimitiveType!!
val DoubleType: Class<Double> get() = Double::class.javaPrimitiveType!!
val ByteType: Class<Byte> get() = Byte::class.javaPrimitiveType!!
val ShortType: Class<Short> get() = Short::class.javaPrimitiveType!!
val CharType: Class<Char> get() = Char::class.javaPrimitiveType!!
val UnitType: Class<Void> get() = Void.TYPE
val AnyType: Class<Any> get() = Any::class.java
/** Wildcard for a single parameter; unlike AnyType it is not java.lang.Object. */
data object VagueType

internal fun resolveType(type: Any, loader: ClassLoader): Class<*> = when (type) {
    is Class<*> -> type
    is KClass<*> -> type.java
    is String -> primitiveTypes[type] ?: if (type.endsWith("[]")) {
        java.lang.reflect.Array.newInstance(resolveType(type.dropLast(2), loader), 0).javaClass
    } else Class.forName(type, false, loader)
    else -> throw IllegalArgumentException("Expected Class, KClass or class-name String, got $type")
}
private val primitiveTypes = mapOf(
    "boolean" to BooleanType, "byte" to ByteType, "short" to ShortType, "char" to CharType,
    "int" to IntType, "long" to LongType, "float" to FloatType, "double" to DoubleType, "void" to UnitType
)
private val boxedTypes = mapOf<Class<*>, Class<*>>(
    BooleanType to java.lang.Boolean::class.java, ByteType to java.lang.Byte::class.java,
    ShortType to java.lang.Short::class.java, CharType to java.lang.Character::class.java,
    IntType to java.lang.Integer::class.java, LongType to java.lang.Long::class.java,
    FloatType to java.lang.Float::class.java, DoubleType to java.lang.Double::class.java
)
internal fun accepts(type: Class<*>, value: Any?): Boolean = when {
    value == null -> !type.isPrimitive
    type.isPrimitive -> boxedTypes[type]?.isInstance(value) == true
    else -> type.isInstance(value)
}
internal fun validateArguments(member: Executable, arguments: Array<Any?>) {
    require(member.parameterCount == arguments.size) { "Argument count mismatch for $member" }
    member.parameterTypes.forEachIndexed { index, type ->
        require(accepts(type, arguments[index])) { "Argument $index of $member must be ${type.name}" }
    }
}
internal fun validateResult(member: Executable, result: Any?) {
    if (member is Method && member.returnType != Void.TYPE) {
        require(accepts(member.returnType, result)) { "Result of $member must be ${member.returnType.name}" }
    }
}
