import com.palantir.gradle.gitversion.VersionDetails
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import groovy.lang.Closure

plugins {
    kotlin("jvm") version "1.5.30"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    id("com.palantir.git-version") version "0.12.3"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

val versionDetails: Closure<VersionDetails> by extra

allprojects {
    group = "com.sourcegraph"
    version = (project.properties["version"] as String).let {
        if (it != "unspecified" && !it.startsWith("refs"))
            return@let it.removePrefix("v")
        val lastTag = versionDetails().lastTag
        val tag =
            if (lastTag.startsWith("v")) lastTag.removePrefix("v")
            else "0.0.0"
        val lastNum = tag.split(".").last().toInt() + 1
        "${tag.split(".").subList(0, 2).joinToString(".")}.$lastNum-SNAPSHOT"
    }
}

repositories {
    mavenCentral()
}

nexusPublishing {
    repositories {
        sonatype {
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}

subprojects {
    tasks.withType<PublishToMavenRepository> {
        doFirst {
            println("Publishing ${publication.groupId}:${publication.artifactId}:${publication.version} to ${repository.url}")
        }
        if (!(version as String).endsWith("SNAPSHOT")) {
            finalizedBy(rootProject.tasks.closeAndReleaseStagingRepository)
        }
    }
}

allprojects {
    afterEvaluate {
        tasks.withType<KotlinCompile> {
            kotlinOptions.jvmTarget = "1.8"
        }

        kotlin {
            jvmToolchain {
                (this as JavaToolchainSpec).languageVersion.set(JavaLanguageVersion.of(8))
            }
        }

        tasks.withType<JavaCompile> {
            sourceCompatibility = "1.8"
        }
    }
}