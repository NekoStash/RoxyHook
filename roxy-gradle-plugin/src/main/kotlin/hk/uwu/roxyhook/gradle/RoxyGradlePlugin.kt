package hk.uwu.roxyhook.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.Locale

class RoxyGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val extension = extensions.create("roxy", RoxyExtension::class.java).apply {
            version.convention("0.3.0")
            autoDependencies.convention(true)
            minApiVersion.convention(102)
            targetApiVersion.convention(102)
            staticScope.convention(false)
            autoHotReload.convention(false)
            scope.convention(emptyList())
            nativeEntries.convention(emptyList())
            entryPackage.convention("hk.uwu.roxyhook.module")
        }
        tasks.register("roxyCreateEntry", CreateRoxyEntryTask::class.java) {
            it.group = "roxyhook"
            it.description = "Create a Kotlin @RoxyEntry without overwriting existing code"
            it.packageName.convention(extension.entryPackage)
            it.className.convention("MainModule")
            it.targetPackage.convention("com.example.target")
            it.sourceDirectory.set(layout.projectDirectory.dir("src/main/kotlin"))
        }
        val doctor = tasks.register("roxyDoctor", RoxyDoctorTask::class.java) {
            it.group = "roxyhook"
            it.sdkVersion.set(extension.version)
            it.automaticDependencies.set(extension.autoDependencies)
        }
        tasks.register("roxyVerifyApk", VerifyRoxyApkTask::class.java) { it.group = "verification" }
        pluginManager.withPlugin("com.android.application") {
            pluginManager.apply("com.google.devtools.ksp")
            val components = extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.finalizeDsl { android ->
                if ((android.defaultConfig.minSdk ?: 1) < 26) throw GradleException("RoxyHook requires android.defaultConfig.minSdk >= 26")
                doctor.configure { task ->
                    task.androidNamespace.set(android.namespace.orEmpty())
                    task.minimumSdk.set(android.defaultConfig.minSdk ?: 1)
                }
                if (extension.autoDependencies.get()) {
                    val platformProject = rootProject.findProject(":roxy-platforms:libxposed")
                    val processorProject = rootProject.findProject(":roxy-ksp")
                    dependencies.add("implementation", platformProject?.let { dependencies.project(mapOf("path" to it.path)) } ?: "hk.uwu.roxyhook:roxy-libxposed:${extension.version.get()}")
                    dependencies.add("ksp", processorProject?.let { dependencies.project(mapOf("path" to it.path)) } ?: "hk.uwu.roxyhook:roxy-ksp:${extension.version.get()}")
                    dependencies.add("compileOnly", "io.github.libxposed:api:102.0.0")
                }
            }
            components.onVariants(components.selector().all()) { variant ->
                val capital = variant.name.replaceFirstChar { it.titlecase(Locale.ROOT) }
                val metadata = tasks.register("generate${capital}RoxyMetadata", GenerateRoxyMetadataTask::class.java) {
                    it.group = "roxyhook"
                    it.minApiVersion.set(extension.minApiVersion)
                    it.targetApiVersion.set(extension.targetApiVersion)
                    it.staticScope.set(extension.staticScope)
                    it.autoHotReload.set(extension.autoHotReload)
                    it.scope.set(extension.scope)
                    it.nativeEntries.set(extension.nativeEntries)
                    it.resourcesDirectory.set(layout.buildDirectory.dir("generated/roxy/${variant.name}/resources"))
                    it.keepRulesDirectory.set(layout.buildDirectory.dir("generated/roxy/${variant.name}/keepRules"))
                }
                val resources = variant.sources.resources ?: throw GradleException("AGP Java resources API is unavailable")
                val keepRules = variant.sources.keepRules ?: throw GradleException("RoxyHook plugin requires AGP 9.3.2 or newer")
                resources.addGeneratedSourceDirectory(metadata, GenerateRoxyMetadataTask::resourcesDirectory)
                keepRules.addGeneratedSourceDirectory(metadata, GenerateRoxyMetadataTask::keepRulesDirectory)
                val kspTasks = tasks.matching { it.name == "ksp${capital}Kotlin" }
                val validation = tasks.register("validate${capital}RoxyEntry", ValidateRoxyEntryTask::class.java) {
                    it.group = "verification"
                    it.generatedResources.from(layout.buildDirectory.dir("generated/ksp/${variant.name}/resources"))
                    it.generatedResources.builtBy(kspTasks)
                    it.dependsOn(kspTasks)
                    it.outputDirectory.set(layout.buildDirectory.dir("generated/roxy/${variant.name}/validated"))
                }
                // Adds the validation dependency to Java-resource packaging without copying KSP output twice.
                resources.addGeneratedSourceDirectory(validation, ValidateRoxyEntryTask::outputDirectory)
            }
        }
        afterEvaluate {
            if (!pluginManager.hasPlugin("com.android.application")) throw GradleException("Apply hk.uwu.roxyhook only to an Android application module")
        }
        Unit
    }
}
