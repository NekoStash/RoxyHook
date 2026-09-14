import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}
android {
    namespace = "hk.uwu.roxyhook.android"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}
// AGP 9 provides built-in Kotlin. Do not also apply org.jetbrains.kotlin.android.
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    api(project(":roxy-core"))
    api(platform(libs.kavaref.bom))
    api(libs.kavaref.android)
}
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "roxy-android"
                from(components["release"])
            }
        }
    }
}
