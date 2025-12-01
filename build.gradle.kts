plugins {
    `kotlin-dsl`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.0.0"
}

group = "io.github.usmanzaheer1995"
version = "1.0.5"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

val generatePluginVersions by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/sources/kotlin-templates")
    outputs.dir(outputDir)

    // Define inputs so Gradle knows when to re-run (optional but good practice)
    inputs.property("jooqVersion", pluginLibs.versions.jooq)
    inputs.property("postgresVersion", pluginLibs.versions.postgresql)
    inputs.property("testcontainersVersion", pluginLibs.versions.testcontainers)
    inputs.property("flywayVersion", pluginLibs.versions.flyway)
    inputs.property("jakartaVersion", pluginLibs.versions.jakartaXmlBind)

    doLast {
        val outputFile = outputDir.get().file("PluginVersions.kt").asFile
        outputFile.parentFile.mkdirs()

        outputFile.writeText(
            """
            package buildsrc.convention
            
            object PluginVersions {
                const val JOOQ = "${pluginLibs.versions.jooq.get()}"
                const val POSTGRES = "${pluginLibs.versions.postgresql.get()}"
                const val TESTCONTAINERS = "${pluginLibs.versions.testcontainers.get()}"
                const val FLYWAY = "${pluginLibs.versions.flyway.get()}"
                const val JAKARTA_XML = "${pluginLibs.versions.jakartaXmlBind.get()}"
            }
            """.trimIndent(),
        )
    }
}

kotlin {
    sourceSets.getByName("main").kotlin.srcDir(generatePluginVersions)
    jvmToolchain(
        pluginLibs.versions.java
            .get()
            .toInt(),
    )
}

dependencies {
    implementation(pluginLibs.kotlin.gradle.plugin)
    implementation(pluginLibs.flyway.gradle.plugin)
    implementation(pluginLibs.jooq.gradle.plugin)
    // https://stackoverflow.com/a/78665419
    implementation(pluginLibs.commons.compress)
    implementation(pluginLibs.testcontainers.postgresql)
    implementation(pluginLibs.flyway.core)
    implementation(pluginLibs.flyway.database.postgresql)
    implementation(pluginLibs.postgresql)
    implementation(pluginLibs.jooq)
    implementation(pluginLibs.jooq.codegen)
    implementation(pluginLibs.jooq.meta)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/usmanzaheer1995/flyway-jooq-convention-plugin")
            credentials {
                username = extra.properties["github.flyway-jooq-plugin.githubActor"] as String? ?: System.getenv("GITHUB_ACTOR")
                password = extra.properties["github.flyway-jooq-plugin.githubToken"] as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

gradlePlugin {
    website = "https://github.com/usmanzaheer1995/flyway-jooq-convention-plugin"
    vcsUrl = "https://github.com/usmanzaheer1995/flyway-jooq-convention-plugin"

    plugins {
        register("flyway-jooq-convention") {
            id = "io.github.usmanzaheer1995.flyway-jooq-convention-plugin"
            displayName = "Flyway & JOOQ Convention Plugin"
            description =
                "A framework-agnostic Gradle convention plugin for generating JOOQ classes from Flyway migrations for Kotlin-based projects."
            tags = listOf("jooq", "flyway")
            implementationClass = "io.github.usmanzaheer1995.FlywayJooqConventionPlugin"
        }
    }
}
