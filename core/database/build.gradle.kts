plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.kepler.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.androidx.datastore.preferences)
    ksp(libs.room.compiler)
    // Host JVM tests cannot load the Android .so of the bundled driver; use its desktop build there instead.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

configurations.matching { it.name.startsWith("test") && it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
