plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("androidx.room")
}

android {
    namespace = "app.peermesh.data"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

room {
    schemaDirectory("release", "$projectDir/schemas")
    // Room Gradle plugin requires a schema directory per variant.
    // Keep debug schemas in build/ so only release schemas are tracked.
    schemaDirectory("debug", "$buildDir/room-schemas")
}
kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(project(":core"))
    implementation(project(":security"))
    implementation(project(":transport"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    api("androidx.room:room-runtime:2.6.1")
    api("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
