package hk.uwu.roxyhook.tooltests

import com.google.devtools.ksp.symbol.*
import hk.uwu.roxyhook.ksp.*
import hk.uwu.roxyhook.gradle.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CodegenSuite {
    private var tests = 0
    private fun test(name: String, block: () -> Unit) { block(); tests++; println("PASS  $name") }
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        error("Expected invalid input to be rejected")
    }
    private const val LIST = "META-INF/xposed/java_init.list"
    private fun valid(name: String = "example.MainModule") = ProcessorModel().apply { round(Symbol(name)); processor.finish() }
    @JvmStatic fun main(args: Array<String>) {
        test("processor emits Kotlin during process, exact metadata only in finish") {
            val model = ProcessorModel(); val source = Symbol()
            check(model.round(source).isEmpty())
            check(model.files.keys.single().endsWith(".kt") && LIST !in model.files)
            model.processor.finish()
            check(model.errors.isEmpty() && model.files.size == 3)
            check(model.files.keys.none { it.endsWith(".list.") })
            check(model.aggregating[LIST] == true && model.aggregating.entries.single { it.key.endsWith(".kt") }.value == false)
            check(EntryMetadataValidator.validate(listOf(model.text(LIST))).startsWith("hk.uwu.roxyhook.generated.RoxyEntry_"))
        }
        test("class and object entries generate different initialization expressions") {
            val clazz = EntryRenderer.kotlin(EntryModel("a.Entry", false))
            val obj = EntryRenderer.kotlin(EntryModel("a.Entry", true))
            check("`a`.`Entry`()" in clazz && "`a`.`Entry`()" !in obj && "`a`.`Entry`" in obj)
            val model = ProcessorModel(); model.round(Symbol(classKind = ClassKind.OBJECT, constructors = emptyList())); model.processor.finish()
            check(model.errors.isEmpty() && LIST in model.files)
        }
        test("entry hash is deterministic, 24 hexadecimal digits and package-sensitive") {
            val one = EntryModel("a.Entry", false); val two = EntryModel("b.Entry", false)
            check(one.generatedName.matches(Regex("RoxyEntry_[a-f0-9]{24}")))
            check(one.generatedName == EntryModel("a.Entry", false).generatedName && one.generatedName != two.generatedName)
        }
        test("Kotlin keywords in package are escaped; nonportable identifiers fail") {
            check(EntryModel("example.when.Entry", false).kotlinReference == "`example`.`when`.`Entry`")
            rejects { EntryModel("a.bad-name", false) }; rejects { EntryModel(".Entry", false) }
        }
        test("default and vararg constructors are callable with no arguments") {
            for (parameter in listOf(Parameter(hasDefault = true), Parameter(isVararg = true))) {
                val model = ProcessorModel(); model.round(Symbol(constructors = listOf(Constructor(listOf(parameter))))); model.processor.finish()
                check(model.errors.isEmpty() && LIST in model.files)
            }
        }
        test("invalid class shape is diagnosed, never emitted as a valid entry") {
            val symbols = listOf(
                Symbol(modifiers = setOf(Modifier.PRIVATE)), Symbol(modifiers = setOf(Modifier.ABSTRACT)),
                Symbol(modifiers = setOf(Modifier.SEALED)), Symbol(classKind = ClassKind.INTERFACE),
                Symbol(constructors = listOf(Constructor(modifiers = setOf(Modifier.PRIVATE)))),
                Symbol(constructors = listOf(Constructor(listOf(Parameter())))), Symbol(derivesFromModule = false),
                Symbol(typeParameters = listOf(object : KSTypeParameter {})), Symbol(containingFile = null),
                Symbol(parentDeclaration = Symbol("parent.Outer"))
            )
            symbols.forEach { source ->
                val model = ProcessorModel(); model.round(source); model.processor.finish()
                check(model.errors.isNotEmpty() && model.files.isEmpty())
            }
        }
        test("multiple entries in one round are rejected") {
            val model = ProcessorModel(); model.round(Symbol("a.Entry"), Symbol("b.Entry")); model.processor.finish()
            check(model.errors.any { "Multiple" in it } && LIST !in model.files)
        }
        test("entry discovered in a later round still rejects multiple declarations") {
            val model = ProcessorModel(); model.round(Symbol("a.Entry")); model.round(Symbol("b.Entry")); model.processor.finish()
            check(model.errors.isNotEmpty() && LIST !in model.files)
        }
        test("repeated symbols do not generate duplicate files") {
            val source = Symbol(); val model = ProcessorModel(); repeat(3) { model.round(source) }; model.processor.finish()
            check(model.errors.isEmpty() && model.files.size == 3)
        }
        test("unresolved symbols defer and succeed after resolution") {
            val source = Symbol().apply { isValid = false }; val model = ProcessorModel()
            check(model.round(source) == listOf(source) && model.files.isEmpty())
            source.isValid = true; model.round(source); model.processor.finish()
            check(model.errors.isEmpty() && LIST in model.files)
        }
        test("unresolved final symbols and onError never produce metadata") {
            val unresolved = ProcessorModel(); unresolved.round(Symbol().apply { isValid = false }); unresolved.processor.finish()
            check(unresolved.errors.isNotEmpty() && LIST !in unresolved.files)
            val failed = ProcessorModel(); failed.round(Symbol()); failed.processor.onError(); failed.processor.finish()
            check(LIST !in failed.files)
        }
        test("test compilation with no entry is allowed, APK main validation rejects it") {
            val model = ProcessorModel(); model.round(); model.processor.finish()
            check(model.files.isEmpty() && model.errors.isEmpty())
            rejects { EntryMetadataValidator.validate(emptyList()) }
            rejects { EntryMetadataValidator.validate(listOf("")) }
        }
        test("entry metadata rejects duplicates, malformed names and whitespace") {
            val valid = valid().text(LIST)
            rejects { EntryMetadataValidator.validate(listOf(valid, valid)) }
            rejects { EntryMetadataValidator.validate(listOf(valid + valid)) }
            rejects { EntryMetadataValidator.validate(listOf(" " + valid)) }
            rejects { EntryMetadataValidator.validate(listOf("example.Entry\n")) }
        }
        test("module properties and scope are deterministic and reject injection") {
            check(MetadataRenderer.moduleProperties(102, 102, false, true) == "minApiVersion=102\ntargetApiVersion=102\nstaticScope=false\nautoHotReload=true\n")
            check(MetadataRenderer.scopeList(listOf("z.app", "a.app", "z.app")) == "a.app\nz.app\n")
            check(MetadataRenderer.scopeList(emptyList()).isEmpty())
            rejects { MetadataRenderer.scopeList(listOf("app\nandroid")) }
            rejects { MetadataRenderer.moduleProperties(101, 102, false, false) }
            rejects { MetadataRenderer.moduleProperties(103, 102, false, false) }
        }
        test("native metadata uses plain filenames without path traversal") {
            check(MetadataRenderer.nativeList(listOf("libdemo.so", "libdemo.so")) == "libdemo.so\n")
            listOf("../lib.so", "/lib.so", "..", "lib\n.so", "a\\b.so").forEach { bad -> rejects { MetadataRenderer.nativeList(listOf(bad)) } }
        }
        test("developer source template is a real annotated module, not a generated native entry") {
            val source = EntrySourceTemplate.render("example.when", "MyModule", "target.app")
            check("@RoxyEntry" in source && "RoxyModule()" in source && "PackageScope.onLoad()" in source)
            check("package `example`.`when`" in source && "loadApp(\"target.app\")" in source)
            rejects { EntrySourceTemplate.render("../bad", "MyModule", "target.app") }
            rejects { EntrySourceTemplate.render("good.app", "Bad-Name", "target.app") }
        }
        test("keep rules retain generated constructors and entry names") {
            val model = EntryModel("a.Entry", false)
            check(model.generatedQualifiedName in EntryRenderer.keepRules(model))
            check("public <init>();" in EntryRenderer.keepRules(model) && "RoxyXposedModule" in MetadataRenderer.keepRules)
        }
        test("DEX index reads class definitions, not arbitrary string matches") {
            val descriptor = "Lexample/Entry;"
            check(DexClassIndex.descriptors(minimalDex(descriptor)) == setOf(descriptor))
            val noClass = minimalDex(descriptor).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(96, 0) }
            check(DexClassIndex.descriptors(noClass).isEmpty())
            rejects { DexClassIndex.descriptors(byteArrayOf(1)) }
            val corrupt = minimalDex(descriptor).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(100, Int.MAX_VALUE) }
            rejects { DexClassIndex.descriptors(corrupt) }
        }
        test("APK inspector checks the actual generated entry definition") {
            val entry = EntryModel("a.Entry", false).generatedQualifiedName
            val apk = File.createTempFile("roxy-metadata-fixture-", ".zip")
            try {
                apk(apk, entry, "L" + entry.replace('.', '/') + ";")
                val report = RoxyApkInspector.inspect(apk)
                check(report.entries == listOf(entry) && report.minApi == 102)
                apk(apk, entry, "Lother/Class;")
                rejects { RoxyApkInspector.inspect(apk) }
                apk(apk, entry, "L" + entry.replace('.', '/') + ";", legacy = true)
                rejects { RoxyApkInspector.inspect(apk) }
            } finally { apk.delete() }
        }
        val out = File(System.getProperty("roxy.codegen.out", "build/codegen-check/generated"))
        val generated = valid("hk.uwu.roxyhook.sample.module.MainModule")
        generated.files.forEach { (name, stream) -> File(out, name).apply { parentFile.mkdirs(); writeBytes(stream.toByteArray()) } }
        File(out, "META-INF/xposed/module.prop").writeText(MetadataRenderer.moduleProperties(102, 102, false, false))
        File(out, "META-INF/xposed/scope.list").writeText(MetadataRenderer.scopeList(listOf("hk.uwu.roxyhook.sample.target")))
        println("RESULT: $tests/$tests generator/tool contracts passed")
        println("KSP tests used a handwritten resolver model, not the real compiler plugin; APK tests used synthetic DEX fixtures.")
        println("Generated sample source: ${out.absolutePath}")
    }
    /** Minimal index fixture, NOT an installable APK or executable DEX. */
    private fun minimalDex(descriptor: String): ByteArray {
        val text = descriptor.toByteArray(Charsets.UTF_8); check(text.size < 128)
        val size = 152 + 1 + text.size + 1
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("dex\n039\u0000".toByteArray(Charsets.UTF_8))
            putInt(32, size); putInt(36, 112); putInt(40, 0x12345678)
            putInt(56, 1); putInt(60, 112); putInt(64, 1); putInt(68, 116)
            putInt(96, 1); putInt(100, 120); putInt(112, 152); putInt(116, 0); putInt(120, 0)
            position(152); put(text.size.toByte()); put(text); put(0.toByte())
        }.array()
    }
    private fun apk(file: File, entry: String, descriptor: String, legacy: Boolean = false) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            add(LIST, "$entry\n".toByteArray())
            add("META-INF/xposed/module.prop", MetadataRenderer.moduleProperties(102, 102, false, false).toByteArray())
            add("classes.dex", minimalDex(descriptor))
            if (legacy) add("assets/xposed_init", "bad.Entry".toByteArray())
        }
    }
}
