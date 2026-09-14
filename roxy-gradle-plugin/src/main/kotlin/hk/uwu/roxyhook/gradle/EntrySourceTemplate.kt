package hk.uwu.roxyhook.gradle

object EntrySourceTemplate {
    fun render(packageName: String, className: String, targetPackage: String): String {
        MetadataRenderer.validatePackage(packageName)
        MetadataRenderer.validatePackage(targetPackage)
        require(className.matches(Regex("[A-Z][A-Za-z0-9_]*"))) { "Entry class name must start with an uppercase letter" }
        val escapedPackage = packageName.split('.').joinToString(".") { "`$it`" }
        return """
            |package $escapedPackage
            |
            |import hk.uwu.roxyhook.PackageScope
            |import hk.uwu.roxyhook.RoxyModule
            |import hk.uwu.roxyhook.annotation.RoxyEntry
            |import hk.uwu.roxyhook.android.lifecycle.lifecycle
            |
            |@RoxyEntry
            |class $className : RoxyModule() {
            |    override fun PackageScope.onLoad() {
            |        loadApp("$targetPackage") {
            |            log("Module loaded")
            |            lifecycle {
            |                onAttach { log("Application attached: " + application.javaClass.name) }
            |                onCreate { log("Application created") }
            |            }
            |        }
            |    }
            |}
            |""".trimMargin()
    }
}
