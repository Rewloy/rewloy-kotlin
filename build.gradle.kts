import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.animalsniffer) apply false
}

allprojects {
    group = "com.rewloy"
    version = "0.2.3"
}

// `./gradlew generate` regenerates src/main/kotlin/com/rewloy/generated from the live OpenAPI document;
// `./gradlew generate -Pfile=openapi/openapi.json` does it from the saved snapshot.
tasks.register("generate") {
    group = "rewloy"
    description = "Regenerates the client from the live OpenAPI document (or -Pfile=<path>)."
    dependsOn(":generator:generate")
}

// Where the artifacts are written. Both are directories; nothing in this build uploads anything.
//   - `./gradlew publishAllPublicationsToVerifyRepository` builds every artifact into build/verify-repo, unsigned:
//     what CI lists, to see what would be published.
//   - `./gradlew publishAllPublicationsToRewloyRepoRepository -PrewloyRepoDir=<a checkout of Rewloy/maven>` adds this
//     version to that checkout, which GitHub Pages serves as https://maven.rewloy.com. Gradle reads the
//     maven-metadata.xml already there and adds the version to it. The release workflow
//     (.github/workflows/release.yml) runs it and pushes the new files; without the property the repository and
//     its tasks do not exist. A relative path is taken from this directory.
val rewloyRepoDir: String? = providers.gradleProperty("rewloyRepoDir").orNull
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "verify"
                    url = uri(rootProject.layout.buildDirectory.dir("verify-repo"))
                }
                if (rewloyRepoDir != null) {
                    maven {
                        name = "rewloyRepo"
                        url = rootProject.file(rewloyRepoDir).toURI()
                    }
                }
            }
        }
    }
}

// What every published artifact carries and says about itself; each module adds its own name and description.
// The files and the POM meet Maven Central's requirements even though releases go to maven.rewloy.com
// (docs/DECISIONS.md, 32), so that moving there later is credentials and a workflow step, not a new layout.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            // A sources jar (IDEs show the KDoc from it) and an empty javadoc jar, which Central accepts: no Dokka.
            configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
            // Configured, not used: Central now wants a paid Publisher Pro plan for a commercial SDK (DECISIONS 32).
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
