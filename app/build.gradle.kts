import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aibookkeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aibookkeeper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Azure OpenAI config from local.properties
        val properties = project.rootProject.file("local.properties")
        if (properties.exists()) {
            val localProps = Properties()
            properties.inputStream().use { localProps.load(it) }
            buildConfigField("String", "AZURE_OPENAI_API_KEY",
                "\"${localProps.getProperty("AZURE_OPENAI_API_KEY", "")}\"")
            buildConfigField("String", "AZURE_OPENAI_ENDPOINT",
                "\"${localProps.getProperty("AZURE_OPENAI_ENDPOINT", "")}\"")
            buildConfigField("String", "AZURE_OPENAI_DEPLOYMENT",
                "\"${localProps.getProperty("AZURE_OPENAI_DEPLOYMENT", "gpt-4o-mini")}\"")
        } else {
            buildConfigField("String", "AZURE_OPENAI_API_KEY", "\"\"")
            buildConfigField("String", "AZURE_OPENAI_ENDPOINT", "\"\"")
            buildConfigField("String", "AZURE_OPENAI_DEPLOYMENT", "\"gpt-4o-mini\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Modules
    implementation(project(":core-common"))
    implementation(project(":core-data"))
    implementation(project(":feature-input"))
    implementation(project(":feature-capture"))
    implementation(project(":feature-stats"))
    implementation(project(":feature-sync"))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Desugar
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Testing
    testImplementation(libs.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
