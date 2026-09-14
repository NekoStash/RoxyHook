// Deliberately limited KSP model. This checks our processor logic, not compatibility with real KSP.
package com.google.devtools.ksp.processing
import com.google.devtools.ksp.symbol.*
import java.io.OutputStream
class Dependencies(val aggregating: Boolean, vararg val sources: KSFile)
interface CodeGenerator {
    fun createNewFile(dependencies: Dependencies, packageName: String, fileName: String, extensionName: String = "kt"): OutputStream
    fun createNewFileByPath(dependencies: Dependencies, path: String, extensionName: String = "kt"): OutputStream
}
interface KSPLogger { fun error(message: String, symbol: KSNode? = null) }
interface Resolver { fun getSymbolsWithAnnotation(annotationName: String): Sequence<KSAnnotated> }
interface SymbolProcessor {
    fun process(resolver: Resolver): List<KSAnnotated>
    fun finish() = Unit
    fun onError() = Unit
}
interface SymbolProcessorProvider { fun create(environment: SymbolProcessorEnvironment): SymbolProcessor }
class SymbolProcessorEnvironment(val codeGenerator: CodeGenerator, val logger: KSPLogger)
