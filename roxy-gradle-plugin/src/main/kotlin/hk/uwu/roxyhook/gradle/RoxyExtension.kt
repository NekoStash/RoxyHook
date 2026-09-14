package hk.uwu.roxyhook.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class RoxyExtension {
    abstract val version: Property<String>
    abstract val autoDependencies: Property<Boolean>
    abstract val minApiVersion: Property<Int>
    abstract val targetApiVersion: Property<Int>
    abstract val staticScope: Property<Boolean>
    abstract val scope: ListProperty<String>
    abstract val autoHotReload: Property<Boolean>
    abstract val nativeEntries: ListProperty<String>
    abstract val entryPackage: Property<String>
}
