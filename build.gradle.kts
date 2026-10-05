import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.animalsniffer) apply false
}

allprojects {
    group = "com.rewloy"
    version = "0.2.0"
}

// `./gradlew generate` regenerates src/main/kotlin/com/rewloy/generated from the live OpenAPI document;
// `./gradlew generate -Pfile=openapi/openapi.json` does it from the saved snapshot.
tasks.register("generate") {
    group = "rewloy"
    description = "Regenerates the client from the live OpenAPI document (or -Pfile=<path>)."
    dependsOn(":generator:generate")
}

// `./gradlew publishAllPublicationsToVerifyRepository` builds every artifact into build/verify-repo, unsigned:
// what CI lists, to see what would be published. Publishing proper is the Maven Central tasks (README, "Releasing").
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "verify"
                    url = uri(rootProject.layout.buildDirectory.dir("verify-repo"))
                }
            }
        }
    }
}

// What every published artifact says about itself; each module adds its own name and description.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            // Prepared, not run: publishing needs the owner's Central Portal account and a signing key (README, "Publishing").
            publishToMavenCentral(automaticRelease = false)
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom {
                inceptionYear.set("2026")
                url.set("https://github.com/Rewloy/rewloy-kotlin")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("rewloy")
                        name.set("Rewloy")
                        url.set("https://rewloy.com")
                    }
                }
                scm {
                    url.set("https://github.com/Rewloy/rewloy-kotlin")
                    connection.set("scm:git:https://github.com/Rewloy/rewloy-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Rewloy/rewloy-kotlin.git")
                }
            }
        }
    }
}
