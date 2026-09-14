package hk.uwu.roxyhook.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Explicit APK inspection command")
abstract class VerifyRoxyApkTask : DefaultTask() {
    /**
     * Declared `@Internal` (not `@InputFile`) on purpose: existence and presence are validated in
     * [verify] so failures can name both the raw `--apk` input and the resolved path. The task
     * produces no outputs, so input tracking would never skip it anyway.
     */
    @get:Internal abstract val apk: RegularFileProperty

    /** Raw `--apk` option text, kept so failures can report both the input and the resolved path. */
    private var apkInput: String? = null

    /**
     * `--apk` is resolved against the root project directory: the task is typically invoked from the
     * repository root, so `project.file` would misinterpret a root-relative path like
     * `samples/demo-module/build/outputs/apk/debug/demo-module-debug.apk` by prefixing the subproject
     * directory. Absolute paths are unaffected by the anchor and stay supported.
     */
    @Option(option = "apk", description = "APK file to inspect, relative to the root project or absolute")
    fun apkPath(value: String) {
        apkInput = value
        apk.set(project.rootProject.file(value))
        // When the resolved file is also produced by a packaging task in the same invocation,
        // verify must run after it (ordering only — never pulls packaging into the graph).
        mustRunAfter(project.tasks.withType(com.android.build.gradle.tasks.PackageApplication::class.java))
    }

    @TaskAction fun verify() {
        if (!apk.isPresent) throw GradleException(
            "roxyVerifyApk requires --apk=<path>, relative to the root project or absolute")
        val apkFile = apk.get().asFile
        if (!apkFile.isFile) throw GradleException(
            "APK not found: input '${apkInput ?: "<unset>"}' resolved to '${apkFile.absolutePath}'")
        val report = try { RoxyApkInspector.inspect(apkFile) }
        catch (error: Exception) {
            throw GradleException(
                "Roxy APK verification failed for '${apkFile.absolutePath}' (input '${apkInput ?: "<unset>"}'): ${error.message}",
                error)
        }
        logger.lifecycle("APK metadata + DEX entries verified: ${report.entries}, API ${report.minApi}..${report.targetApi}")
        logger.lifecycle("This is not a signing, framework loading, or device behavior test.")
    }
}
