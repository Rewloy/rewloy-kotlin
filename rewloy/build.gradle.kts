import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.animalsniffer)
    alias(libs.plugins.maven.publish)
}

// What the library promises to run on (docs/DECISIONS.md explains why):
//   - JVM 8 bytecode, so the oldest Android runtimes and Java 8 servers can load it;
//   - Android API 21 (Android 5.0) and up: the standard library calls it makes are checked against that API level
//     by the animalsniffer task, which `check` runs;
//   - Kotlin 2.0 or later in the caller's compiler (the metadata the library is written with).
kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.addAll(
            "-Xjdk-release=8",
            "-Xsuppress-version-warnings",
            // Lambdas as classes, not invokedynamic: no LambdaMetafactory, which Android below API 26 only has
            // through the build tools' desugaring.
            "-Xlambdas=class",
            "-Xsam-conversions=class",
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    // The oldest standard library the 2.0 API level allows: Gradle raises it to whatever else the caller uses.
    api("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    signature("net.sf.androidscents.signature:android-api-level-21:5.0.1_r2@signature")
    testFixturesApi(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val shippedSources: SourceSet = sourceSets.main.get()
animalsniffer {
    // The library's own bytecode against Android 5.0's API. The Kotlin standard library is a dependency, not the platform.
    ignore("kotlin.*", "org.jetbrains.annotations.*")
    // Only the shipped code: the live tests run on a desktop JDK against a development server.
    sourceSets = listOf(shippedSources)
}

tasks.test {
    useJUnitPlatform()
    // `./gradlew test -PtestJava=8` runs the tests on a Java 8 runtime, to prove the bytecode and the APIs it uses.
    // Or point at a JDK directly (-PtestJavaHome=/path/to/jdk8), for one the toolchain detection does not match.
    providers.gradleProperty("testJavaHome").orNull?.let { home -> executable = "$home/bin/java" }
    providers.gradleProperty("testJava").orNull?.let { version ->
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(version.toInt())) })
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The live tests (docs: README, "Live tests"): the library against a running development Rewloy, through
// `./gradlew liveTest`. They are a source set of their own, so `test` and `check` never run them and never need a
// network; without REWLOY_BASE_URL and REWLOY_API_KEY the task skips cleanly.
val liveTestSourceSet: SourceSet = sourceSets.create("liveTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[liveTestSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[liveTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    // The summary listener is written against the launcher API.
    "liveTestCompileOnly"(platform(libs.junit.bom))
    "liveTestCompileOnly"(libs.junit.launcher)
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileLiveTestKotlin") {
    // The shipped code declares its API explicitly (explicitApi above); a test does not have to.
    explicitApiMode.set(org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled)
}

tasks.register<Test>("liveTest") {
    group = "verification"
    description = "Runs the library against a development Rewloy (REWLOY_BASE_URL, REWLOY_API_KEY = a rwk_test_ key). Skips without them."
    testClassesDirs = liveTestSourceSet.output.classesDirs
    classpath = liveTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    // Always runs, never from the cache: its inputs are a server, not files.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    // The environment (REWLOY_BASE_URL, REWLOY_API_KEY, REWLOY_STAFF_SESSION) reaches the test JVM as it is.
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

// `check` compiles the live tests (so they cannot rot) but never runs them.
tasks.named("check") { dependsOn("liveTestClasses") }

// The test helpers (the stub server) are for this repository's modules, not for Maven Central.
(components["java"] as AdhocComponentWithVariants).apply {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}

afterEvaluate {
    (components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(configurations["testFixturesSourcesElements"]) { skip() }
}

mavenPublishing {
    coordinates("com.rewloy", "rewloy", version.toString())
    pom {
        name.set("Rewloy")
        description.set("Rewloy API client for Kotlin, Java and Android POS terminals.")
    }
}
