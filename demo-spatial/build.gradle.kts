// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored cleared source demonstration configuration; no recovered application assets.
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.github.mesmerprism.synedelica.demo.spatial"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.mesmerprism.synedelica.demo.spatial"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-source-slice"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":adapter-spatial"))
    implementation("com.meta.spatial:meta-spatial-sdk:0.13.2")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:0.13.2")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:0.13.2")
    testImplementation("junit:junit:4.13.2")
}
