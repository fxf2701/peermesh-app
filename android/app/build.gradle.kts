import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "com.google.android.gms.oss-licenses-plugin")

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasKeystoreProperties = keystorePropertiesFile.exists()
if (hasKeystoreProperties) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
val hasLocalProperties = localPropertiesFile.exists()
if (hasLocalProperties) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

fun resolveBuildString(vararg keys: String): String {
    keys.forEach { key ->
        val value = providers.gradleProperty(key).orNull
            ?: System.getenv(key)
            ?: localProperties.getProperty(key)
        if (!value.isNullOrBlank()) {
            return value.trim()
        }
    }
    return ""
}

fun asBuildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val relayBaseUrlConfig = resolveBuildString("PEERMESH_RELAY_BASE_URL", "RELAY_BASE_URL")
val registryBaseUrlConfig = resolveBuildString("PEERMESH_REGISTRY_BASE_URL", "REGISTRY_BASE_URL")
val relayRequestSignatureSecretConfig = resolveBuildString(
    "PEERMESH_RELAY_REQUEST_SIGNATURE_SECRET",
    "RELAY_REQUEST_SIGNATURE_SECRET",
)
val registryRequestSignatureSecretConfig = resolveBuildString(
    "PEERMESH_REGISTRY_REQUEST_SIGNATURE_SECRET",
    "REGISTRY_REQUEST_SIGNATURE_SECRET",
)

android {
    namespace = "app.peermesh"
    compileSdk = 35
    buildFeatures {
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }

    defaultConfig {
        applicationId = "app.peermesh"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appAllowBackup"] = true
        manifestPlaceholders["appUsesCleartextTraffic"] = true
        buildConfigField("String", "RELAY_BASE_URL", asBuildConfigString(relayBaseUrlConfig))
        buildConfigField("String", "REGISTRY_BASE_URL", asBuildConfigString(registryBaseUrlConfig))
        buildConfigField(
            "String",
            "RELAY_REQUEST_SIGNATURE_SECRET",
            asBuildConfigString(relayRequestSignatureSecretConfig),
        )
        buildConfigField(
            "String",
            "REGISTRY_REQUEST_SIGNATURE_SECRET",
            asBuildConfigString(registryRequestSignatureSecretConfig),
        )
        buildConfigField("String", "PEER_MESH_PRO_SUBSCRIPTION_ID", "\"peer_mesh_pro_monthly\"")
        buildConfigField("boolean", "ENABLE_AUTO_TRAIL", "false")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("official") {
            dimension = "distribution"
            minSdk = 26
            targetSdk = 35
            buildConfigField("String", "DIST_NAME", "\"official\"")
        }
        create("emergency") {
            dimension = "distribution"
            minSdk = 26
            targetSdk = 29
            applicationIdSuffix = ".emergency"
            versionNameSuffix = "-emergency"
            buildConfigField("String", "DIST_NAME", "\"emergency\"")
        }
    }

    if (hasKeystoreProperties) {
        signingConfigs {
            create("release") {
                val storeFilePath = keystoreProperties["storeFile"] as String
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "ENV_MODE", "\"dev\"")
            buildConfigField("boolean", "ALLOW_PRO_DEV_OVERRIDE", "true")
            buildConfigField("boolean", "ENABLE_ACTIVITY_LOG", "true")
            buildConfigField("boolean", "ENABLE_DEBUG_TOOLS", "true")
            manifestPlaceholders["appAllowBackup"] = true
            manifestPlaceholders["appUsesCleartextTraffic"] = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "ENV_MODE", "\"prod\"")
            buildConfigField("boolean", "ALLOW_PRO_DEV_OVERRIDE", "false")
            buildConfigField("boolean", "ENABLE_ACTIVITY_LOG", "false")
            buildConfigField("boolean", "ENABLE_DEBUG_TOOLS", "false")
            manifestPlaceholders["appAllowBackup"] = false
            manifestPlaceholders["appUsesCleartextTraffic"] = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":transport"))
    implementation(project(":security"))
    implementation(project(":ui"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.android.billingclient:billing:8.3.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
