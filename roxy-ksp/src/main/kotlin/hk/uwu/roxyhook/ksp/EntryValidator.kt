package hk.uwu.roxyhook.ksp

/** Validation input without processor-specific types. */
data class EntryShape(
    val name: String,
    val topLevel: Boolean,
    val accessible: Boolean,
    val concrete: Boolean,
    val generic: Boolean,
    val isObject: Boolean,
    val isClass: Boolean,
    val hasCallableConstructor: Boolean,
    val extendsRoxyModule: Boolean
)
object EntryValidator {
    fun errors(shape: EntryShape): List<String> = buildList {
        if (!shape.topLevel) add("@RoxyEntry must be a top-level declaration")
        if (!shape.accessible) add("@RoxyEntry must not be private or protected")
        if (!shape.concrete || (!shape.isClass && !shape.isObject)) add("@RoxyEntry must be a concrete class or object")
        if (shape.generic) add("@RoxyEntry cannot have type parameters")
        if (!shape.isObject && !shape.hasCallableConstructor) add("@RoxyEntry requires an accessible constructor callable without arguments")
        if (!shape.extendsRoxyModule) add("@RoxyEntry must extend hk.uwu.roxyhook.RoxyModule")
        if (shape.name.isBlank()) add("@RoxyEntry must have a qualified name")
    }
}
