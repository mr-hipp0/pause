import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.pause.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":domain"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
android { defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" } }

// Validate the checked-in offline collection before any variant packages assets. Builds never
// download quotes, and malformed/empty collections cannot accidentally replace a good cache.
val validateBundledQuotes by tasks.registering {
    val asset = layout.projectDirectory.file("src/main/assets/quotes/zenquotes-v1.json")
    inputs.file(asset)
    doLast {
        val root = groovy.json.JsonSlurper().parse(asset.asFile, "UTF-8") as? Map<*, *>
            ?: error("The bundled quote collection must be a JSON object")
        check(root["schemaVersion"] == 1) { "Unsupported quote schema" }
        check(root["source"] == "ZenQuotes" && root["sourceUrl"] == "https://zenquotes.io/") { "Unexpected quote source" }
        check((root["collectionVersion"] as? String)?.isNotBlank() == true) { "Missing quote collection version" }
        Instant.parse(root["retrievedAt"] as? String ?: error("Missing retrieval date"))
        val rows = root["quotes"] as? List<*> ?: error("Missing quote records")
        check(rows.isNotEmpty() && rows.size <= 5_000) { "Expected a nonempty, bounded quote collection" }
        val ids = mutableSetOf<String>()
        val texts = mutableSetOf<String>()
        rows.forEach { value ->
            val row = value as? Map<*, *> ?: error("Malformed quote record")
            val id = row["id"] as? String ?: error("Missing quote ID")
            val text = row["text"] as? String ?: error("Missing quote text")
            val author = row["author"] as? String ?: error("Missing quote author")
            check(text.length in 1..160 && author.length in 1..80) { "Quote $id is too long or empty" }
            check(listOf(text, author).all { it == it.trim() && '<' !in it && '>' !in it && it.none(Char::isISOControl) }) { "Quote $id needs plain, trimmed text" }
            check(!author.equals("Unknown", true) && !author.equals("Anonymous", true)) { "Quote $id needs an author" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$text\n$author".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(24)
            check(id == "zen-$digest") { "Quote $id does not match its text/author" }
            check(ids.add(id) && texts.add(text.lowercase(Locale.ROOT))) { "Duplicate quote $id" }
        }
        logger.lifecycle("Validated ${rows.size} bundled ZenQuotes records")
    }
}
tasks.named("preBuild") { dependsOn(validateBundledQuotes) }
