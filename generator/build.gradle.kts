plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    // The generator reads and writes JSON with the library's own reader and writer (no other dependency), so
    // that it can run before the generated code exists. Only that package is shared.
    sourceSets.main {
        kotlin.srcDir("../rewloy/src/main/kotlin/com/rewloy/json")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.rewloy.generator.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generate") {
    group = "rewloy"
    description = "Regenerates the client."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.rewloy.generator.MainKt")
    workingDir = rootProject.projectDir
    val file = providers.gradleProperty("file")
    val url = providers.gradleProperty("url")
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            if (file.isPresent) { add("--file"); add(file.get()) }
            if (url.isPresent) { add("--url"); add(url.get()) }
        }
    })
}
