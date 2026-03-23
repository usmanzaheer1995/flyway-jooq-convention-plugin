dependencyResolutionManagement {
    versionCatalogs {
        create("pluginLibs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "jooq-codegen-convention-plugin"
