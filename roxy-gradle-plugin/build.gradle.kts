plugins {
    kotlin("jvm") version "2.4.10"
    `java-gradle-plugin`
    `maven-publish`
}
group = "hk.uwu.roxyhook"
version = "0.3.0"
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; withSourcesJar() }
dependencies {
    compileOnly("com.android.tools.build:gradle:9.3.2")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.9")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}
gradlePlugin {
    plugins {
        create("roxyhook") {
            id = "hk.uwu.roxyhook"
            implementationClass = "hk.uwu.roxyhook.gradle.RoxyGradlePlugin"
            displayName = "RoxyHook Module Tools"
            description = "KSP entry generation, LibXposed metadata, keep rules and module developer tools"
        }
    }
}
