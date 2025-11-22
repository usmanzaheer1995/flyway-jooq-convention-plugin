dependencyResolutionManagement {
    versionCatalogs {
        create("pluginLibs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}
