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
    // A caller on OkHttp 5 gets 5: Gradle takes the higher version.
    api(libs.okhttp)
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
    coordinates("com.rewloy", "rewloy-okhttp", version.toString())
    pom {
        name.set("Rewloy OkHttp transport")
        description.set("An OkHttp transport for the Rewloy API client.")
    }
}
