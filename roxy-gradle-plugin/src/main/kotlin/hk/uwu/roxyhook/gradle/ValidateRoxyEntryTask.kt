package hk.uwu.roxyhook.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class ValidateRoxyEntryTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedResources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun validate() {
        val lists = generatedResources.asFileTree.files.filter { it.name == "java_init.list" }
        if (lists.isEmpty()) {
            val searched = generatedResources.files.joinToString(", ") { it.absolutePath }
            throw GradleException(
                "RoxyHook KSP output did not produce java_init.list. Searched generated resources: [$searched]. " +
                    "Ensure a @RoxyEntry extending RoxyModule exists and the ksp<Variant>Kotlin task ran."
            )
        }
        try { EntryMetadataValidator.validate(lists.map { it.readText(Charsets.UTF_8) }) }
        catch (error: IllegalArgumentException) { throw GradleException(error.message.orEmpty(), error) }
        outputDirectory.get().asFile.mkdirs()
    }
}
