package hk.uwu.roxyhook.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateRoxyMetadataTask : DefaultTask() {
    @get:Input abstract val minApiVersion: Property<Int>
    @get:Input abstract val targetApiVersion: Property<Int>
    @get:Input abstract val staticScope: Property<Boolean>
    @get:Input abstract val autoHotReload: Property<Boolean>
    @get:Input abstract val scope: ListProperty<String>
    @get:Input abstract val nativeEntries: ListProperty<String>
    @get:OutputDirectory abstract val resourcesDirectory: DirectoryProperty
    @get:OutputDirectory abstract val keepRulesDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val props = MetadataRenderer.moduleProperties(minApiVersion.get(), targetApiVersion.get(), staticScope.get(), autoHotReload.get())
        val scopes = MetadataRenderer.scopeList(scope.get())
        val natives = MetadataRenderer.nativeList(nativeEntries.get())
        val directory = resourcesDirectory.dir("META-INF/xposed").get().asFile.apply { mkdirs() }
        directory.resolve("module.prop").writeText(props, Charsets.UTF_8)
        directory.resolve("scope.list").writeText(scopes, Charsets.UTF_8)
        val nativeFile = directory.resolve("native_init.list")
        if (natives.isEmpty()) nativeFile.delete() else nativeFile.writeText(natives, Charsets.UTF_8)
        val rulesDirectory = keepRulesDirectory.get().asFile.apply { mkdirs() }
        // AGP keepRules source folders only accept .keep files; remove the legacy .pro output so incremental builds do not fail R8.
        rulesDirectory.resolve("roxy-entry.pro").delete()
        rulesDirectory.resolve("roxy-entry.keep").writeText(MetadataRenderer.keepRules, Charsets.UTF_8)
    }
}
