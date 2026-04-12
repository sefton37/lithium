plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)       // Hilt requires kapt (KSP not fully supported)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)               // Room uses KSP
    alias(libs.plugins.hilt)
}

android {
    namespace = "ai.talkingrock.lithium"
    compileSdk = 35

    // NDK version — required for llama.cpp native build
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "ai.talkingrock.lithium"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // llama.cpp NDK build — only when -PenableLlama is set and NDK is installed.
        // Requires: vendor/llama.cpp/ source in app/src/main/cpp/
        if (project.hasProperty("enableLlama")) {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release"
                    )
                }
            }
        }
    }

    // llama.cpp CMake build — conditional on -PenableLlama flag
    if (project.hasProperty("enableLlama")) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // Expose Room schema JSON files to the instrumented test APK so
    // MigrationTestHelper can load them via the assets folder.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Avoid conflicts between SQLCipher and ONNX native libraries
            excludes += "/META-INF/versions/**"
            excludes += "/META-INF/INDEX.LIST"
            // MockK pulls in JUnit Jupiter transitively — exclude duplicate license files
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

// KSP arguments for Room schema export
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Compose BOM — pins all Compose library versions together
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)
    implementation(libs.compose.viewmodel)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

    // Room + SQLCipher
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.sqlite.ktx)

    // ONNX Runtime (M3: AI engine — included here so the dependency is declared)
    implementation(libs.onnxruntime.android)

    // WorkManager
    implementation(libs.workmanager.ktx)

    // Security (EncryptedSharedPreferences)
    implementation(libs.security.crypto)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)

    // Coroutines
    implementation(libs.coroutines.android)

    // Kotlin serialization (condition_json parsing in RuleRepository)
    implementation(libs.kotlinx.json)


    // Ktor (API server — Phase 1)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Core
    implementation(libs.core.ktx)

    // Diagnostics module (can be excluded with -PexcludeDiagnostics)
    if (!project.hasProperty("excludeDiagnostics")) {
        implementation(project(":diagnostics"))
    }

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)

    // Instrumented tests
    androidTestImplementation(libs.junit.android)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.room.ktx)
    androidTestImplementation(libs.coroutines.test)
}

// Allow Hilt's kapt to use the correct Java version
kapt {
    correctErrorTypes = true
}
