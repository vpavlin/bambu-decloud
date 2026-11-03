plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "mesh.office.bambuwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "mesh.office.bambuwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "0.43"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // unit tests run on the JVM: org.json is only a stub in android.jar, so bring the real one
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
