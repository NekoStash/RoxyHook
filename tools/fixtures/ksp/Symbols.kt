// Handwritten test-double signatures only. Never included in Gradle production source sets.
package com.google.devtools.ksp.symbol
interface KSNode
interface KSAnnotated : KSNode { val isValid: Boolean get() = true }
interface KSName { fun asString(): String }
interface KSFile : KSNode
interface KSDeclaration : KSAnnotated {
    val qualifiedName: KSName?
    val parentDeclaration: KSDeclaration? get() = null
    val modifiers: Set<Modifier> get() = emptySet()
    val containingFile: KSFile? get() = null
}
enum class Modifier { PRIVATE, PROTECTED, ABSTRACT, SEALED, INTERNAL, PUBLIC }
enum class ClassKind { CLASS, OBJECT, INTERFACE, ENUM_CLASS, ANNOTATION_CLASS }
interface KSTypeParameter
interface KSType { val declaration: KSDeclaration }
interface KSValueParameter { val hasDefault: Boolean; val isVararg: Boolean }
interface KSFunctionDeclaration : KSDeclaration { val parameters: List<KSValueParameter> }
interface KSClassDeclaration : KSDeclaration {
    val typeParameters: List<KSTypeParameter>
    val classKind: ClassKind
    val testConstructors: Sequence<KSFunctionDeclaration>
    val testSuperTypes: Sequence<KSType>
}
