package hk.uwu.roxyhook.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@DisableCachingByDefault(because = "Explicit developer command that creates a source file without overwriting existing code")
abstract class CreateRoxyEntryTask : DefaultTask() {
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val className: Property<String>
    @get:Input abstract val targetPackage: Property<String>
    @get:Internal abstract val sourceDirectory: DirectoryProperty
    @Option(option = "package", description = "Entry Kotlin package") fun entryPackage(value: String) { packageName.set(value) }
    @Option(option = "name", description = "Entry class name") fun entryName(value: String) { className.set(value) }
    @Option(option = "target", description = "Initial target app package") fun target(value: String) { targetPackage.set(value) }
    @TaskAction fun createEntry() {
        val source = EntrySourceTemplate.render(packageName.get(), className.get(), targetPackage.get())
        val file = sourceDirectory.get().asFile.resolve(packageName.get().replace('.', '/') + "/" + className.get() + ".kt")
        file.parentFile.mkdirs()
        Files.writeString(file.toPath(), source, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        logger.lifecycle("Created ${file.absolutePath}")
    }
}
