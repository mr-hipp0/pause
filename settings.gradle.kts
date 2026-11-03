pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Pause"
include(":app", ":core", ":domain", ":data", ":feature-dashboard", ":feature-app-selector", ":feature-intervention", ":feature-settings", ":feature-statistics", ":service-interception")

