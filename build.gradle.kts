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
    // Name the launcher `gradle-prune`, not the default `<project.name>`
    // (`gradle-prune-plugin`) — the READMEs and the Clikt command name both
    // say `gradle-prune`.
    applicationName = "gradle-prune"
    mainClass = "io.github.qie2035.gradleprune.core.CliKt"

    // Java 24+ prints "A restricted method in java.lang.System has been called"
    // on every run because Mordant's JNA terminal backend loads a native
    // library. Opting in silences it (and keeps working when it becomes an
    // error in a future release).
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
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

// maven-publish (via java-gradle-plugin) publishes the `pluginMaven` artifact
// plus the `io.github.qie2035.gradle-prune.gradle.plugin` marker, which is what
// prune-global.init.gradle.kts consumes through mavenLocal().
