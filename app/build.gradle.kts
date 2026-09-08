plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp") }
android {
 namespace = "es.iesvirgendelacaridad.etcp"; compileSdk = 37
 defaultConfig { applicationId = "es.iesvirgendelacaridad.etcp"; minSdk = 28; targetSdk = 36; versionCode = 1; versionName = "1.0.0" }
 buildFeatures { compose = true }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2026.08.00")); implementation("androidx.activity:activity-compose:1.13.0"); implementation("androidx.compose.material3:material3"); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview"); debugImplementation("androidx.compose.ui:ui-tooling"); implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0"); implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0"); implementation("androidx.navigation:navigation-compose:2.10.0"); implementation("androidx.room:room-runtime:2.8.3"); implementation("androidx.room:room-ktx:2.8.3"); ksp("androidx.room:room-compiler:2.8.3"); implementation("androidx.datastore:datastore-preferences:1.1.7"); implementation("com.squareup.okhttp3:okhttp:4.12.0"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"); implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
