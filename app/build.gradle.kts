plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseKeystorePath = System.getenv("ALALPDF_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ALALPDF_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ALALPDF_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ALALPDF_KEY_PASSWORD")
val hasReleaseCredentials = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android { namespace = "com.alalkipgen.alalpdf"; compileSdk = 35
    defaultConfig { applicationId = "com.alalkipgen.alalpdf"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.1.1-beta"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    signingConfigs {
        create("release") {
            if (hasReleaseCredentials) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseCredentials) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
