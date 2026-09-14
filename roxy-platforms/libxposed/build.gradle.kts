import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}
android {
    namespace = "hk.uwu.roxyhook.platform.libxposed"
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
    api(project(":roxy-android"))
    compileOnly(libs.libxposed.api)
    // androidx.annotation provides @RequiresApi; not in the version catalog, declared literally.
    compileOnly("androidx.annotation:annotation:1.9.1")
    api(libs.libxposed.service)
}
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "roxy-libxposed"
                from(components["release"])
            }
        }
    }
}
