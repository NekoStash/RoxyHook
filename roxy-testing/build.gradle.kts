plugins { alias(libs.plugins.kotlin.jvm); `java-library`; `maven-publish` }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; withSourcesJar() }
dependencies { api(project(":roxy-core")) }
val contractTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Execute the platform-independent RoxyHook contract suite."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("hk.uwu.roxyhook.testing.ContractSuite")
    systemProperty("roxy.report", layout.buildDirectory.file("test-results/contracts/TEST-contracts.xml").get().asFile.absolutePath)
}
val regressionTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("hk.uwu.roxyhook.testing.RegressionSuite")
}
val keepHotReloadTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description =
        "Execute KEEP ownership simulations (not real framework compatibility validation)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("hk.uwu.roxyhook.testing.KeepHotReloadSuite")
}
tasks.test {
    // 本模块的测试是自定义 main，由三个 JavaExec 验证任务执行；
    // 不存在 JUnit 可发现测试，避免 Gradle 9 默认因无发现测试而失败。
    failOnNoDiscoveredTests.set(false)
}
tasks.check { dependsOn(contractTest, regressionTest, keepHotReloadTest) }
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
