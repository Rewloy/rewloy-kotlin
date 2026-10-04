import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // kotlinx.coroutines 1.8 is written for Kotlin 1.9 and up; a newer one in the app wins by resolution.
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.addAll("-Xjdk-release=8", "-Xlambdas=class", "-Xsam-conversions=class")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    api(project(":rewloy"))
    api(libs.coroutines.core)
    testImplementation(testFixtures(project(":rewloy")))
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
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

mavenPublishing {
    coordinates("com.rewloy", "rewloy-coroutines", version.toString())
    pom {
        name.set("Rewloy coroutines")
        description.set("Kotlin coroutines support for the Rewloy API client: suspending calls and a Flow of stream events.")
    }
}
