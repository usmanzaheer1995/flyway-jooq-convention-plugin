dependencyResolutionManagement {
    versionCatalogs {
        create("pluginLibs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "flyway-jooq-convention-plugin"
