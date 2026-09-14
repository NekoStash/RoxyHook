pluginManagement {
    includeBuild("roxy-gradle-plugin")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "RoxyHook"
include(":roxy-annotations", ":roxy-core", ":roxy-ksp", ":roxy-testing")
if (providers.gradleProperty("jvmOnly").orNull != "true") {
    include(":roxy-android", ":roxy-platforms:libxposed")
    include(":samples:demo-module", ":samples:demo-target")
}
