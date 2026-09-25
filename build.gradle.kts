import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("kapt") version "2.2.21"
    `java-library`
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// The group and the major live in gradle.properties. The release workflow works out
// the full version and passes it in; a local build is a snapshot of the current major.
version = providers.gradleProperty("releaseVersion")
    .getOrElse("${providers.gradleProperty("majorVersion").get()}.0-SNAPSHOT")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
}

val springBootVersion = "4.1.0"
val kotlinVersion = "2.2.21"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
//    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    kapt(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    implementation("org.springframework.boot:spring-boot-starter-web")
//    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    kapt("org.springframework.boot:spring-boot-configuration-processor")
    // Explicit version: the Spring Boot BOM still pins Kotlin 2.1.x.
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("org.slf4j:slf4j-api")

    // Present in every Spring Boot web app; we only need it to read Caddy's config.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))

    publishToMavenCentral()

    // Central rejects unsigned artifacts; a local build without a key must still work.
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("local-proxy-spring-boot-starter")
        description.set(
            "Spring Boot starter that registers an application with a local Caddy proxy on startup, " +
                "so it is reachable at https://<app-name>.localhost instead of a random port."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/raddatzk/local-proxy-spring-boot-starter")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("raddatzk")
                name.set("Kevin Raddatz")
                url.set("https://github.com/raddatzk")
            }
        }
        scm {
            url.set("https://github.com/raddatzk/local-proxy-spring-boot-starter")
            connection.set("scm:git:https://github.com/raddatzk/local-proxy-spring-boot-starter.git")
            developerConnection.set("scm:git:ssh://git@github.com/raddatzk/local-proxy-spring-boot-starter.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/raddatzk/local-proxy-spring-boot-starter")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}
