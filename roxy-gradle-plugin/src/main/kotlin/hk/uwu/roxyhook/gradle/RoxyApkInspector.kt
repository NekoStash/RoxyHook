package hk.uwu.roxyhook.gradle

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

object RoxyApkInspector {
    data class Report(val entries: List<String>, val minApi: Int, val targetApi: Int, val dexFiles: Int)
    /** Check metadata and actual DEX class definitions. Does not validate signing or framework/device behavior. */
    fun inspect(apk: File): Report = ZipFile(apk).use { zip ->
        fun read(path: String, limit: Int): ByteArray {
            val entry = requireNotNull(zip.getEntry(path)) { "APK is missing $path" }
            require(entry.size in 0..limit.toLong()) { "APK entry $path exceeds the inspection limit" }
            return zip.getInputStream(entry).use { input ->
                val bytes = input.readNBytes(limit + 1)
                require(bytes.size <= limit)
                bytes
            }
        }
        val entries = read("META-INF/xposed/java_init.list", 16 * 1024).toString(Charsets.UTF_8)
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(entries.isNotEmpty() && entries.distinct().size == entries.size) { "Invalid Java entry list" }
        val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        require(entries.all { identifier.matches(it) }) { "Invalid Java entry name" }
        val properties = Properties().apply { load(read("META-INF/xposed/module.prop", 16 * 1024).toString(Charsets.UTF_8).reader()) }
        val min = requireNotNull(properties.getProperty("minApiVersion")?.toIntOrNull()) { "Missing minApiVersion" }
        val target = requireNotNull(properties.getProperty("targetApiVersion")?.toIntOrNull()) { "Missing targetApiVersion" }
        require(min >= 102 && target >= min) { "Invalid LibXposed API range" }
        require(zip.getEntry("assets/xposed_init") == null) { "Legacy assets/xposed_init is forbidden for this API-102 module" }
        val dexEntries = zip.entries().asSequence().filter { Regex("classes(?:[2-9][0-9]*|1[0-9]+)?\\.dex").matches(it.name) }.toList()
        require(zip.getEntry("classes.dex") != null) { "APK has no classes.dex" }
        val missing = entries.map { "L" + it.replace('.', '/') + ";" }.toMutableSet()
        dexEntries.forEach { missing.removeAll(DexClassIndex.descriptors(read(it.name, 128 * 1024 * 1024))) }
        require(missing.isEmpty()) { "Entry classes are missing from DEX (check R8 rules): $missing" }
        Report(entries, min, target, dexEntries.size)
    }
}
