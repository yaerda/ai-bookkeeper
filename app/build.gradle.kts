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

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "aibookkeeper2026"
            keyAlias = "ai-bookkeeper"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "aibookkeeper2026"
        }
    }

    defaultConfig {
        applicationId = "com.aibookkeeper"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"

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
                "\"${localProps.getProperty("AZURE_OPENAI_DEPLOYMENT", "gpt-4.1-mini")}\"")
            buildConfigField("String", "AZURE_OPENAI_SPEECH_DEPLOYMENT",
                "\"${localProps.getProperty("AZURE_OPENAI_SPEECH_DEPLOYMENT", "")}\"")
        } else {
            buildConfigField("String", "AZURE_OPENAI_API_KEY", "\"\"")
            buildConfigField("String", "AZURE_OPENAI_ENDPOINT", "\"\"")
            buildConfigField("String", "AZURE_OPENAI_DEPLOYMENT", "\"gpt-4.1-mini\"")
            buildConfigField("String", "AZURE_OPENAI_SPEECH_DEPLOYMENT", "\"\"")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "ai-bookkeeper-${variant.versionName}-${variant.buildType.name}.apk"
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
    implementation(libs.splashscreen)

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
