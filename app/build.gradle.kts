plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "es.iesvirgendelacaridad.etcp"; compileSdk = 36
 defaultConfig { applicationId = "es.iesvirgendelacaridad.etcp"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "1.0.0" }
 buildFeatures { compose = true }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.12.01")); implementation("androidx.activity:activity-compose:1.12.1"); implementation("androidx.compose.material3:material3"); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview"); debugImplementation("androidx.compose.ui:ui-tooling"); implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
