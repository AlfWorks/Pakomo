pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Pakomo"

// Sibling self-update library, consumed as a composite build. Same AGP/Kotlin/Compose
// versions, so dev.novi:novi-core / dev.novi:novi-compose substitute to these projects
// with no published Maven artifact, submodule, or version drift.
includeBuild("../novi")

include(":app")
