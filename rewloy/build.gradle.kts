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

animalsniffer {
    // The library's own bytecode against Android 5.0's API. The Kotlin standard library is a dependency, not the platform.
    ignore("kotlin.*", "org.jetbrains.annotations.*")
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
