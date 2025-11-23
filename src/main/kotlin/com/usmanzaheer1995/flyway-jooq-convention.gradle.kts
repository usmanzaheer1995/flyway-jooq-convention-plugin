package com.usmanzaheer1995

import buildsrc.convention.PluginVersions
import nu.studer.gradle.jooq.JooqConfig
import nu.studer.gradle.jooq.JooqExtension
import org.flywaydb.core.Flyway
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.invoke
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Logging
import org.testcontainers.containers.PostgreSQLContainer

plugins {
    kotlin("jvm")
    id("org.flywaydb.flyway")
    id("nu.studer.jooq")
}

project.dependencies.apply {
    add("implementation", "org.jooq:jooq:${PluginVersions.JOOQ}")
    add("implementation", "org.postgresql:postgresql:${PluginVersions.POSTGRES}")

    add("jooqGenerator", "org.postgresql:postgresql:${PluginVersions.POSTGRES}")
    add("jooqGenerator", "jakarta.xml.bind:jakarta.xml.bind-api:${PluginVersions.JAKARTA_XML}")

    add("testImplementation", "org.testcontainers:postgresql:${PluginVersions.TESTCONTAINERS}")
    add("testImplementation", "org.testcontainers:junit-jupiter:${PluginVersions.TESTCONTAINERS}")

    add("implementation", "org.flywaydb:flyway-core:${PluginVersions.FLYWAY}")
    add("implementation", "org.flywaydb:flyway-database-postgresql:${PluginVersions.FLYWAY}")
}

open class JooqConventionsExtension {
    var databaseName: String = "testdb"
    var username: String = "test"
    var password: String = "test"
    var postgresVersion: String = "16-alpine"
    var inputSchema: String = "public"
    var targetPackage: String = "com.example.jooq.generated"
    var excludedTables: String = "flyway_schema_history"
}

val jooqConventions = extensions.create<JooqConventionsExtension>("jooqConventions")

var postgresContainer: PostgreSQLContainer<Nothing>? = null
var containerJdbcUrl: String? = null
var containerUsername: String? = null
var containerPassword: String? = null

// Task to start a container, run migrations, and configure jOOQ
val setupDatabaseForJooq by tasks.registering {
    group = "jooq"
    description = "Starts PostgreSQL container and runs Flyway migrations for jOOQ generation"

    doFirst {
        println("Starting PostgreSQL container...")
        postgresContainer =
            PostgreSQLContainer<Nothing>("postgres:${jooqConventions.postgresVersion}").apply {
                withDatabaseName(jooqConventions.databaseName)
                withUsername(jooqConventions.username)
                withPassword(jooqConventions.password)
                start()
            }

        containerJdbcUrl = postgresContainer!!.jdbcUrl
        containerUsername = postgresContainer!!.username
        containerPassword = postgresContainer!!.password

        println("PostgreSQL container started: $containerJdbcUrl")
        println("Running Flyway migrations...")

        val migrationsDir = project.file("src/main/resources/db/migration")

        // Run Flyway migrations programmatically
        val flyway =
            Flyway
                .configure()
                .dataSource(containerJdbcUrl, containerUsername, containerPassword)
                .locations("filesystem:${migrationsDir.absolutePath}")
                .load()

        val result = flyway.migrate()
        println("Flyway migrations completed: ${result.migrationsExecuted} migrations executed")
    }
}

val stopPostgresContainer by tasks.registering {
    group = "jooq"
    description = "Stops PostgreSQL testcontainer"

    doLast {
        postgresContainer?.stop()
        postgresContainer?.close() // Explicitly close to remove the container
        postgresContainer = null
        println("PostgreSQL container stopped and removed")
    }
}

// Configure jOOQ code generation
afterEvaluate {
    jooq {
        version.set(PluginVersions.JOOQ)

        configurations {
            create("main", fun JooqConfig.() {
                generateSchemaSourceOnCompilation = false

                jooqConfiguration.apply {
                    logging = Logging.INFO

                    jdbc.apply {
                        driver = "org.postgresql.Driver"
                        url = project.extra.properties["containerJdbcUrl"]?.toString()
                            ?: "jdbc:postgresql://localhost:5432/${jooqConventions.databaseName}"
                        user = project.extra.properties["containerUsername"]?.toString() ?: jooqConventions.username
                        password = project.extra.properties["containerPassword"]?.toString() ?: jooqConventions.password
                    }

                    generator.apply {
                        name = "org.jooq.codegen.KotlinGenerator"

                        database.apply {
                            name = "org.jooq.meta.postgres.PostgresDatabase"
                            inputSchema = jooqConventions.inputSchema
                            excludes = jooqConventions.excludedTables

                            // Configure forced types for better Kotlin integration
                            forcedTypes.addAll(
                                listOf(
                                    ForcedType().apply {
                                        name = "varchar"
                                        includeExpression = ".*"
                                        includeTypes = "JSONB?"
                                    },
                                    ForcedType().apply {
                                        name = "varchar"
                                        includeExpression = ".*"
                                        includeTypes = "INET"
                                    },
                                ),
                            )
                        }

                        generate.apply {
                            isDeprecated = false
                            isRecords = true
                            isImmutablePojos = true
                            isFluentSetters = true
                            isKotlinSetterJvmNameAnnotationsOnIsPrefix = true
                            isKotlinNotNullPojoAttributes = true
                            isKotlinNotNullRecordAttributes = true
                            isKotlinNotNullInterfaceAttributes = true
                        }

                        target.apply {
                            packageName = jooqConventions.targetPackage
                            directory = "build/generated-src/jooq/main"
                        }

                        strategy.name = "org.jooq.codegen.DefaultGeneratorStrategy"
                    }
                }
            })
        }
    }
}

// Configure jOOQ task dependencies after evaluation
afterEvaluate {
    tasks.named("generateJooq") {
        dependsOn(setupDatabaseForJooq)
        finalizedBy(stopPostgresContainer)

        // Override JDBC connection at runtime
        doFirst {
            if (containerJdbcUrl != null) {
                println("Configuring jOOQ to use container: $containerJdbcUrl")

                // Access the jOOQ configuration and update it
                val jooqExt = project.extensions.getByType(JooqExtension::class.java)
                jooqExt.configurations.getByName("main").jooqConfiguration.apply {
                    jdbc.url = containerJdbcUrl
                    jdbc.user = containerUsername
                    jdbc.password = containerPassword
                }
            }
        }
    }

    // Make compilation depend on jOOQ generation
    tasks.named("compileKotlin") {
        dependsOn("generateJooq")
    }
}

// Ensure generated sources are included in compilation
kotlin {
    sourceSets {
        main {
            kotlin.srcDir("build/generated-src/jooq/main")
        }
    }
}

// Make compilation depend on jOOQ generation
tasks.named("compileKotlin") {
    dependsOn("generateJooq")
}

// Clean generated sources
tasks.named("clean") {
    doLast {
        delete("build/generated-src/jooq")
    }
}

// Configure test tasks to use testcontainers
tasks.withType<Test> {
    useJUnitPlatform()

    environment("TESTCONTAINERS_RYUK_DISABLED", "false")

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
