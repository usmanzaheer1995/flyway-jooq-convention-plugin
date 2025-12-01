package io.github.usmanzaheer1995

import buildsrc.convention.PluginVersions
import nu.studer.gradle.jooq.JooqExtension
import org.flywaydb.core.Flyway
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Logging
import org.testcontainers.containers.PostgreSQLContainer

class FlywayJooqConventionPlugin : Plugin<Project> {
    private var postgresContainer: PostgreSQLContainer<Nothing>? = null
    private var containerJdbcUrl: String? = null
    private var containerUsername: String? = null
    private var containerPassword: String? = null

    override fun apply(project: Project) {
        project.run {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("org.flywaydb.flyway")
            pluginManager.apply("nu.studer.jooq")

            dependencies.apply {
                add("implementation", "org.jooq:jooq:${PluginVersions.JOOQ}")
                add("implementation", "org.postgresql:postgresql:${PluginVersions.POSTGRES}")

                add("jooqGenerator", "org.postgresql:postgresql:${PluginVersions.POSTGRES}")
                add("jooqGenerator", "jakarta.xml.bind:jakarta.xml.bind-api:${PluginVersions.JAKARTA_XML}")

                add("testImplementation", "org.testcontainers:postgresql:${PluginVersions.TESTCONTAINERS}")
                add("testImplementation", "org.testcontainers:junit-jupiter:${PluginVersions.TESTCONTAINERS}")

                add("runtimeOnly", "org.flywaydb:flyway-core:${PluginVersions.FLYWAY}")
                add("runtimeOnly", "org.flywaydb:flyway-database-postgresql:${PluginVersions.FLYWAY}")
            }

            val jooqConventions = extensions.create<JooqConventionsExtension>("jooqConventions")

            val setupDatabaseForJooq =
                tasks.register("setupDatabaseForJooq") {
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

                        val migrationsDir = file("src/main/resources/db/migration")

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

            val stopPostgresContainer =
                tasks.register("stopPostgresContainer") {
                    group = "jooq"
                    description = "Stops PostgreSQL testcontainer"

                    doLast {
                        postgresContainer?.stop()
                        postgresContainer?.close()
                        postgresContainer = null
                        println("PostgreSQL container stopped and removed")
                    }
                }

            afterEvaluate {
                configure<JooqExtension> {
                    version.set(PluginVersions.JOOQ)

                    configurations {
                        create("main").apply {
                            generateSchemaSourceOnCompilation.set(false)

                            jooqConfiguration.apply {
                                logging = Logging.INFO

                                jdbc.apply {
                                    driver = "org.postgresql.Driver"
                                    url = extra.properties["containerJdbcUrl"]?.toString()
                                        ?: "jdbc:postgresql://localhost:5432/${jooqConventions.databaseName}"
                                    user = extra.properties["containerUsername"]?.toString() ?: jooqConventions.username
                                    password = extra.properties["containerPassword"]?.toString() ?: jooqConventions.password
                                }

                                generator.apply {
                                    name = "org.jooq.codegen.KotlinGenerator"

                                    database.apply {
                                        name = "org.jooq.meta.postgres.PostgresDatabase"
                                        inputSchema = jooqConventions.inputSchema
                                        excludes = jooqConventions.excludedTables

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
                        }
                    }
                }
            }

            afterEvaluate {
                tasks.named("generateJooq") {
                    dependsOn(setupDatabaseForJooq)
                    finalizedBy(stopPostgresContainer)

                    doFirst {
                        if (containerJdbcUrl != null) {
                            println("Configuring jOOQ to use container: $containerJdbcUrl")

                            val jooqExt = project.extensions.getByType(JooqExtension::class.java)
                            jooqExt.configurations.getByName("main").jooqConfiguration.apply {
                                jdbc.url = containerJdbcUrl
                                jdbc.user = containerUsername
                                jdbc.password = containerPassword
                            }
                        }
                    }
                }

                tasks.named("compileKotlin") {
                    dependsOn("generateJooq")
                }
            }

            configure<KotlinProjectExtension> {
                sourceSets {
                    getByName("main") {
                        kotlin.srcDir("build/generated-src/jooq/main")
                    }
                }
            }

            tasks.named("clean") {
                doLast {
                    delete("build/generated-src/jooq")
                }
            }

            tasks.withType<Test> {
                useJUnitPlatform()

                environment("TESTCONTAINERS_RYUK_DISABLED", "false")

                testLogging {
                    events("passed", "skipped", "failed")
                    showStandardStreams = false
                }
            }
        }
    }
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
