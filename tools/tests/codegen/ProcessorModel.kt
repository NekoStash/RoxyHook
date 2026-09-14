package hk.uwu.roxyhook.tooltests

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import hk.uwu.roxyhook.ksp.RoxySymbolProcessorProvider
import java.io.ByteArrayOutputStream

internal class Source : KSFile
internal class Name(private val name: String) : KSName { override fun asString() = name }
internal class Parameter(override val hasDefault: Boolean = false, override val isVararg: Boolean = false) : KSValueParameter
internal class Constructor(override val parameters: List<KSValueParameter> = emptyList(),
                          override val modifiers: Set<Modifier> = emptySet()) : KSFunctionDeclaration {
    override val qualifiedName: KSName? = null
}
internal class Symbol(name: String = "example.MainModule", override val classKind: ClassKind = ClassKind.CLASS,
                      override val modifiers: Set<Modifier> = emptySet(),
                      constructors: List<KSFunctionDeclaration> = listOf(Constructor()),
                      derivesFromModule: Boolean = true,
                      override val typeParameters: List<KSTypeParameter> = emptyList(),
                      override val parentDeclaration: KSDeclaration? = null,
                      override val containingFile: KSFile? = Source()) : KSClassDeclaration {
    override var isValid = true
    override val qualifiedName = Name(name)
    override val testConstructors = constructors.asSequence()
    override val testSuperTypes: Sequence<KSType> = if (!derivesFromModule) emptySequence() else sequenceOf(object : KSType {
        override val declaration = object : KSDeclaration { override val qualifiedName = Name("hk.uwu.roxyhook.RoxyModule") }
    })
}
internal class ProcessorModel {
    val errors = mutableListOf<String>()
    val files = linkedMapOf<String, ByteArrayOutputStream>()
    val aggregating = mutableMapOf<String, Boolean>()
    val processor = RoxySymbolProcessorProvider().create(SymbolProcessorEnvironment(object : CodeGenerator {
        override fun createNewFile(dependencies: Dependencies, packageName: String, fileName: String, extensionName: String) =
            createNewFileByPath(dependencies, packageName.replace('.', '/') + "/" + fileName, extensionName)
        override fun createNewFileByPath(dependencies: Dependencies, path: String, extensionName: String): ByteArrayOutputStream {
            val name = path + if (extensionName.isEmpty()) "" else ".$extensionName"
            check(name !in files) { "Processor attempted to overwrite $name" }
            aggregating[name] = dependencies.aggregating
            return ByteArrayOutputStream().also { files[name] = it }
        }
    }, object : KSPLogger {
        override fun error(message: String, symbol: KSNode?) { errors += message }
    }))
    fun round(vararg symbols: KSAnnotated): List<KSAnnotated> = processor.process(object : Resolver {
        override fun getSymbolsWithAnnotation(annotationName: String): Sequence<KSAnnotated> {
            check(annotationName == "hk.uwu.roxyhook.annotation.RoxyEntry")
            return symbols.asSequence()
        }
    })
    fun text(path: String) = checkNotNull(files[path]).toString(Charsets.UTF_8.name())
}
