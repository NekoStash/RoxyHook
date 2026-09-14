import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.android.application)
    id("hk.uwu.roxyhook")
}
android {
    namespace = "hk.uwu.roxyhook.sample.module"
    compileSdk = 37
    defaultConfig {
        applicationId = "hk.uwu.roxyhook.sample.module"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
// The Roxy plugin wires platform + KSP + compileOnly libxposed and per-variant metadata.
roxy {
    scope.add("hk.uwu.roxyhook.sample.target")
    staticScope.set(false)
    entryPackage.set("hk.uwu.roxyhook.sample.module")
}
