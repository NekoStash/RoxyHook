package hk.uwu.roxyhook.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

class RoxySymbolProcessor(private val output: CodeGenerator, private val logger: KSPLogger) : SymbolProcessor {
    private val entries = linkedMapOf<String, Pair<EntryModel, KSFile>>()
    private var failed = false
    private var deferredCount = 0

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        resolver.getSymbolsWithAnnotation("hk.uwu.roxyhook.annotation.RoxyEntry").forEach { symbol ->
            if (!symbol.validate()) { deferred += symbol; return@forEach }
            val declaration = symbol as? KSClassDeclaration
            if (declaration == null) { report("@RoxyEntry is only valid on classes and objects", symbol); return@forEach }
            val name = declaration.qualifiedName?.asString().orEmpty()
            if (name in entries) return@forEach
            val inaccessible = setOf(Modifier.PRIVATE, Modifier.PROTECTED)
            val shape = EntryShape(
                name = name,
                topLevel = declaration.parentDeclaration == null,
                accessible = declaration.modifiers.none { it in inaccessible },
                concrete = Modifier.ABSTRACT !in declaration.modifiers && Modifier.SEALED !in declaration.modifiers,
                generic = declaration.typeParameters.isNotEmpty(),
                isObject = declaration.classKind == ClassKind.OBJECT,
                isClass = declaration.classKind == ClassKind.CLASS,
                hasCallableConstructor = declaration.getConstructors().any { constructor ->
                    constructor.modifiers.none { it in inaccessible } && constructor.parameters.all { it.hasDefault || it.isVararg }
                },
                extendsRoxyModule = declaration.getAllSuperTypes().any {
                    it.declaration.qualifiedName?.asString() == "hk.uwu.roxyhook.RoxyModule"
                }
            )
            val errors = EntryValidator.errors(shape)
            if (errors.isNotEmpty()) { errors.forEach { report(it, symbol) }; return@forEach }
            val source = declaration.containingFile
            if (source == null) { report("@RoxyEntry must be defined in the current compilation's source", symbol); return@forEach }
            val model = try { EntryModel(name, shape.isObject) }
            catch (error: IllegalArgumentException) { report(error.message.orEmpty(), symbol); return@forEach }
            entries[name] = model to source
            if (entries.size > 1) {
                report("Multiple @RoxyEntry declarations: ${entries.keys.joinToString()}. Use loadHooker for additional hook classes.", symbol)
                return@forEach
            }
            output.createNewFile(Dependencies(false, source), model.generatedPackage, model.generatedName, "kt")
                .bufferedWriter(Charsets.UTF_8).use { it.write(EntryRenderer.kotlin(model)) }
        }
        deferredCount = deferred.size
        return deferred
    }
    override fun finish() {
        if (deferredCount > 0) { report("$deferredCount @RoxyEntry declaration(s) still have unresolved types"); return }
        // A test compilation normally contains no entry. The Gradle main-variant validation rejects a missing APK entry.
        if (failed || entries.isEmpty()) return
        val (model, file) = entries.values.single()
        val dependencies = Dependencies(true, file)
        output.createNewFileByPath(dependencies, "META-INF/xposed/java_init.list", "")
            .bufferedWriter(Charsets.UTF_8).use { it.write(EntryRenderer.entryList(listOf(model))) }
        output.createNewFileByPath(dependencies, "META-INF/proguard/roxy-entry.pro", "")
            .bufferedWriter(Charsets.UTF_8).use { it.write(EntryRenderer.keepRules(model)) }
    }
    override fun onError() { failed = true }
    private fun report(message: String, node: KSNode? = null) { failed = true; logger.error(message, node) }
}
