// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored Android library configuration.
plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.github.mesmerprism.synedelica.spatial"
    compileSdk = 35
    defaultConfig { minSdk = 34 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core"))
    implementation("com.meta.spatial:meta-spatial-sdk:0.13.2")
    testImplementation("junit:junit:4.13.2")
}
