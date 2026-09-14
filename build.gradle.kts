plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}
allprojects {
    group = "hk.uwu.roxyhook"
    version = "0.1.0"
}

// Aggregate every published subproject's publishToMavenLocal so that a single
// `./gradlew publishToMavenLocal` (used by JitPack's `install` step and by local
// consumers) produces the complete set of hk.uwu.roxyhook coordinates.
// The roxy-gradle-plugin lives in an included build, so it is published
// separately via `./gradlew -p roxy-gradle-plugin publishToMavenLocal`.
val publishAllToMavenLocal = tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    description = "Publishes all RoxyHook library modules to the local Maven repository."
}
subprojects.forEach { sub: Project ->
    sub.pluginManager.withPlugin("maven-publish") {
        publishAllToMavenLocal.configure { dependsOn(sub.tasks.named("publishToMavenLocal")) }
    }
}
