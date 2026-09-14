plugins { alias(libs.plugins.kotlin.jvm); `java-library`; `maven-publish` }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; withSourcesJar() }
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
