# Flyway & JOOQ Convention Plugin

A framework-agnostic Gradle convention plugin for generating JOOQ classes from Flyway migrations for Kotlin-based projects.

This plugin simplifies the JOOQ code generation workflow by leveraging **Testcontainers**. It automatically spins up a PostgreSQL container, applies your Flyway migrations to it, generates the JOOQ Java/Kotlin classes against that actual schema, and then cleans up.

## Features

- **Automated Database Lifecycle**: Starts a PostgreSQL Testcontainer before generation and shuts it down afterward.
- **Migration-based Generation**: Uses your actual Flyway SQL migrations (`src/main/resources/db/migration`) to define the schema.
- **Opinionated JOOQ Configuration**:
    - Configured for Kotlin generation.
    - Includes `JSONB` and `INET` forced types mapping.
    - Sets up generated sources to be automatically included in the Kotlin compilation path.
- **Dependency Management**: Automatically applies necessary dependencies (JOOQ, PostgreSQL driver, Flyway Core).

## Prerequisites

- **Java 21**
- **Docker** (Required for Testcontainers to spin up the temporary database)

## Installation

### 1. First option

Add the plugin to your `build.gradle.kts`:
```kotlin
plugins {
    id("io.github.usmanzaheer1995.flyway-jooq-convention-plugin") version "1.0.1"
}
```

### 2. Second option

Since this plugin is hosted on GitHub Packages (or a custom Maven repo based on your configuration), you can also install the plugin by adding the repository to your `settings.gradle.kts`:
```kotlin
pluginManagement { 
    repositories { 
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://maven.pkg.github.com/usmanzaheer1995/flyway-jooq-convention")
            credentials { 
                username = extra.properties["github.flyway-jooq-plugin.githubActor"] as String? ?: System.getenv("GITHUB_ACTOR")
                password = extra.properties["github.flyway-jooq-plugin.githubToken"] as String? ?: System.getenv("GITHUB_TOKEN") 
            } 
        } 
    } 
}
```

And then doing option one (Add the plugin to your `build.gradle.kts`).

## Configuration

The plugin provides a `jooqConventions` extension to configure the database and generation settings.

Add the following block to your `build.gradle.kts` to customize the defaults:
```kotlin
jooqConventions { 
    // The package where the JOOQ code will be generated targetPackage = "com.example.jooq.generated"
    // PostgreSQL version to use for the Testcontainer (default: 16-alpine)
    postgresVersion = "16-alpine"

    // Database credentials for the temporary container (default: test/test/testdb)
    databaseName = "testdb"
    username = "test"
    password = "test"

    // Schema to generate code for (default: public)
    inputSchema = "public"

    // Tables to exclude from generation (regex)
    excludedTables = "flyway_schema_history"
}
```


### Defaults

If you do not provide a configuration block, the following defaults are used:

| Property | Default Value |
| :--- | :--- |
| `targetPackage` | `com.example.jooq.generated` |
| `postgresVersion` | `16-alpine` |
| `databaseName` | `testdb` |
| `username` | `test` |
| `password` | `test` |
| `inputSchema` | `public` |
| `excludedTables` | `flyway_schema_history` |

## Usage

### Directory Structure

The plugin expects your Flyway migration scripts to be located in the standard Flyway path:
```text
src/main/resources/db/migration/V1__Initial_schema.sql V2__Add_users.sql
```


### Running the Generation

The plugin hooks into the standard Gradle lifecycle. The `compileKotlin` task automatically depends on the JOOQ generation.

To run the generation manually:
```bash
  ./gradlew generateJooq
```

To build the project (which triggers generation):
```bash 
  ./gradlew build
```


## How it Works

1. **`setupDatabaseForJooq`**: Starts a PostgreSQL Docker container and runs the Flyway migrations found in `src/main/resources/db/migration`.
2. **`generateJooq`**: Connects to the running container and generates Kotlin JOOQ code into `build/generated-src/jooq/main`.
3. **`stopPostgresContainer`**: Stops and removes the Docker container.

## Dependencies Added

By applying this plugin, the following dependencies are automatically added to your project:

- `org.jooq:jooq` (implementation)
- `org.postgresql:postgresql` (implementation)
- `org.flywaydb:flyway-core` (runtimeOnly)
- `org.flywaydb:flyway-database-postgresql` (runtimeOnly)
- `org.testcontainers:postgresql` (testImplementation)
- `org.testcontainers:junit-jupiter` (testImplementation)
