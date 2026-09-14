package hk.uwu.roxyhook.gradle

object MetadataRenderer {
    fun validatePackage(name: String) {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) { "Invalid package name: $name" }
    }
    fun moduleProperties(min: Int, target: Int, staticScope: Boolean, hotReload: Boolean): String {
        require(min >= 102 && target >= min) { "RoxyHook requires targetApiVersion >= minApiVersion >= 102" }
        return "minApiVersion=$min\ntargetApiVersion=$target\nstaticScope=$staticScope\nautoHotReload=$hotReload\n"
    }
    fun scopeList(packages: List<String>): String {
        packages.forEach(::validatePackage)
        return packages.distinct().sorted().joinToString("\n", postfix = if (packages.isEmpty()) "" else "\n")
    }
    fun nativeList(entries: List<String>): String {
        require(entries.all { it.matches(Regex("[A-Za-z0-9_.+-]+")) && it != "." && it != ".." }) { "Native entries must be plain library filenames" }
        return entries.distinct().sorted().joinToString("\n", postfix = if (entries.isEmpty()) "" else "\n")
    }
    val keepRules = """
        |-keep public class * extends hk.uwu.roxyhook.platform.libxposed.RoxyXposedModule { public <init>(); }
        |-dontwarn io.github.libxposed.annotation.**
        |""".trimMargin()
}
