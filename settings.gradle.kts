pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Kepler"
include(
    ":app",
    ":core:designsystem", ":core:model", ":core:spine", ":core:database",
    ":engine:epub", ":engine:tts", ":engine:playback", ":engine:ai", ":engine:srs",
    ":feature:library", ":feature:reader", ":feature:player", ":feature:study",
)
