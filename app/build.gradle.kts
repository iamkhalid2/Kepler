import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Dev-only keys come from the untracked local.properties and are injected into DEBUG builds only.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun devKey(name: String) = "\"" + localProps.getProperty(name, "") + "\""

android {
    namespace = "app.betterbooks"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.betterbooks"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "FISH_API_KEY", devKey("FISH_API_KEY"))
            buildConfigField("String", "OPENROUTER_API_KEY", devKey("OPENROUTER_API_KEY"))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "FISH_API_KEY", "\"\"")
            buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
