package io.github.usmanzaheer1995

import buildsrc.convention.PluginVersions
import nu.studer.gradle.jooq.JooqExtension
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
import java.io.File
import java.sql.DriverManager

class JooqCodegenConventionPlugin : Plugin<Project> {
    private var postgresContainer: PostgreSQLContainer<Nothing>? = null
    private var containerJdbcUrl: String? = null
    private var containerUsername: String? = null
    private var containerPassword: String? = null

    override fun apply(project: Project) {
        project.run {
            extensions.extraProperties["jooq.version"] = PluginVersions.JOOQ
            extensions.extraProperties["testcontainers.version"] = PluginVersions.TESTCONTAINERS

            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("nu.studer.jooq")

            dependencies.apply {
                add("implementation", "org.jooq:jooq:${PluginVersions.JOOQ}")
                add("implementation", "org.postgresql:postgresql:${PluginVersions.POSTGRES}")
                add("jooqGenerator", "org.jooq:jooq-meta:${PluginVersions.JOOQ}")
                add("jooqGenerator", "org.jooq:jooq-codegen:${PluginVersions.JOOQ}")

                add("jooqGenerator", "org.postgresql:postgresql:${PluginVersions.POSTGRES}")
                add("jooqGenerator", "jakarta.xml.bind:jakarta.xml.bind-api:${PluginVersions.JAKARTA_XML}")
            }

            val jooqConventions = extensions.create<JooqConventionsExtension>("jooqConventions")

            val setupDatabaseForJooq =
                tasks.register("setupDatabaseForJooq") {
                    group = "jooq"
                    description = "Starts PostgreSQL container and runs SQL migrations for jOOQ generation"

                    doFirst {
                        val rawMigrationsDir =
                            jooqConventions.migrationsDir
                                ?: error(
                                    "jooqConventions.migrationsDir is required but was not set.\n" +
                                        "Please configure it in your build.gradle.kts:\n\n" +
                                        "    jooqConventions {\n" +
                                        "        migrationsDir = \"src/main/resources/db/migration\"\n" +
                                        "    }",
                                )

                        val migrationsDir = project.file(rawMigrationsDir)

                        println("DEBUG migrationsDir: ${migrationsDir.absolutePath}")
                        println("DEBUG exists: ${migrationsDir.exists()}")
                        println("DEBUG isDirectory: ${migrationsDir.isDirectory}")
                        println("DEBUG files: ${migrationsDir.listFiles()?.map { it.name }}")

                        if (!migrationsDir.exists()) {
                            error(
                                "Migrations directory does not exist: ${migrationsDir.absolutePath}\n" +
                                    "Make sure 'jooqConventions.migrationsDir' points to a valid directory.",
                            )
                        }

                        if (!migrationsDir.isDirectory) {
                            error(
                                "Migrations path is not a directory: ${migrationsDir.absolutePath}\n" +
                                    "Make sure 'jooqConventions.migrationsDir' points to a directory, not a file.",
                            )
                        }

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
                        println("Running SQL migrations from: ${migrationsDir.absolutePath}")

                        runMigrations(
                            migrationsDir = migrationsDir,
                            jdbcUrl = containerJdbcUrl!!,
                            username = containerUsername!!,
                            password = containerPassword!!,
                        )
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

                                        // Force jOOQ to respect NOT NULL constraints from DB metadata
                                        isForceIntegerTypesOnZeroScaleDecimals = true

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

                                        nullableAnnotationType = "jakarta.annotation.Nullable"
                                        nonnullAnnotationType = "jakarta.annotation.NonNull"
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

    private fun runMigrations(
        migrationsDir: File,
        jdbcUrl: String,
        username: String,
        password: String,
    ) {
        val sqlFiles =
            migrationsDir
                .listFiles { file -> file.isFile && file.extension.equals("sql", ignoreCase = true) }
                .orEmpty()
                .sortedBy { it.name }

        if (sqlFiles.isEmpty()) {
            println("No SQL migration files found in ${migrationsDir.absolutePath}, skipping migrations.")
            return
        }

        println("Found ${sqlFiles.size} migration file(s) to apply.")

        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.autoCommit = false

            sqlFiles.forEach { file ->
                println("  Applying: ${file.name}")
                try {
                    // Split on semicolons to handle multi-statement files, filtering blank segments
                    val statements =
                        file
                            .readText()
                            .split(";")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }

                    connection.createStatement().use { stmt ->
                        statements.forEach { sql -> stmt.execute(sql) }
                    }

                    connection.commit()
                    println("✓ ${file.name} applied successfully")
                } catch (e: Exception) {
                    connection.rollback()
                    throw RuntimeException(
                        "Migration failed on file '${file.name}': ${e.message}",
                        e,
                    )
                }
            }

            println("All migrations applied successfully (${sqlFiles.size} file(s)).")
        }
    }
}

open class JooqConventionsExtension {
    var migrationsDir: String? = null
    var databaseName: String = "testdb"
    var username: String = "test"
    var password: String = "test"
    var postgresVersion: String = "16-alpine"
    var inputSchema: String = "public"
    var targetPackage: String = "com.example.jooq.generated"
}
