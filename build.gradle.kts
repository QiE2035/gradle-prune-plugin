plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    `java-gradle-plugin`
    `application`
    `maven-publish`
}

group = "io.github.qie2035"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("com.github.ajalt.mordant:mordant:3.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.qie2035.gradleprune.core.CliKt"
}

gradlePlugin {
    plugins {
        create("gradlePrune") {
            id = "io.github.qie2035.gradle-prune"
            implementationClass = "io.github.qie2035.gradleprune.plugin.GradlePrunePlugin"
            displayName = "Gradle Cache Prune"
            description = "Register the modules a build uses and prune unused modules from the shared Gradle dependency cache (like `pnpm store prune`)."
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// maven-publish + java (via java-gradle-plugin) auto-creates a `mavenJava`
// publication; consumed by prune-global.init.gradle.kts via mavenLocal().
