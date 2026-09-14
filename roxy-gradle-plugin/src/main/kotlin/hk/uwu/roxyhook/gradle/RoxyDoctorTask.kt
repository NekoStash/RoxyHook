package hk.uwu.roxyhook.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Diagnostic report")
abstract class RoxyDoctorTask : DefaultTask() {
    @get:Input abstract val androidNamespace: Property<String>
    @get:Input abstract val minimumSdk: Property<Int>
    @get:Input abstract val sdkVersion: Property<String>
    @get:Input abstract val automaticDependencies: Property<Boolean>
    @TaskAction fun diagnose() {
        if (androidNamespace.get().isBlank()) throw GradleException("Set android.namespace before building the module")
        if (minimumSdk.get() < 26) throw GradleException("RoxyHook requires minSdk >= 26")
        logger.lifecycle("RoxyHook ${sdkVersion.get()} | namespace=${androidNamespace.get()} | minSdk=${minimumSdk.get()}")
        logger.lifecycle("KSP entry generation and per-variant metadata/keep-rule tasks are configured.")
        logger.lifecycle("Automatic runtime/API/processor dependencies: ${automaticDependencies.get()}")
        logger.lifecycle("Use roxyCreateEntry to create source; use roxyVerifyApk --apk <file> to inspect packaged metadata.")
        logger.lifecycle("loadAll is restricted to framework-selected application processes, never system_server.")
    }
}
