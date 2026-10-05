plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "es.iesvirgendelacaridad.etcp"; compileSdk = 36
 defaultConfig { applicationId = "es.iesvirgendelacaridad.transcriptorrva.clean"; minSdk = 28; targetSdk = 36; versionCode = 7; versionName = "1.2.4-clean" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.12.01")); implementation("androidx.activity:activity-compose:1.12.1"); implementation("androidx.compose.material3:material3"); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview"); implementation("androidx.core:core-ktx:1.17.0"); debugImplementation("androidx.compose.ui:ui-tooling")
}
