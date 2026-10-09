plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // fsrskt-android declares the Dokka documentation plugin as a runtime dependency; it drags Jackson,
    // FreeMarker, kaml, Compose Multiplatform and more into the app. Only the library itself is needed.
    implementation(libs.fsrskt) { exclude(group = "org.jetbrains.dokka") }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
