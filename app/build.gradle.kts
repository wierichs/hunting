import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningEnabled = providers.gradleProperty("releaseSigning")
    .map { it.toBooleanStrict() }.getOrElse(false)

fun requiredReleaseSigningProperty(name: String): String =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Release-Signierung: '$name' fehlt. Bitte in der lokalen Gradle-Benutzerkonfiguration eintragen.")

android {
    namespace = "org.wierichs.dogtracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.wierichs.jagdkarte"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        val supabaseUrl = providers.gradleProperty("supabaseUrl").getOrElse("https://qgtgeawsdxynnqsuanbl.supabase.co")
        val supabasePublicKey = providers.gradleProperty("supabasePublishableKey").getOrElse("sb_publishable_McuMiwcFEHR1V6QjOksZPA_8OzhaYtX")
        require(supabaseUrl.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?/?")))
        require(supabasePublicKey.matches(Regex("[A-Za-z0-9._-]+")))
        if (supabaseUrl.trimEnd('/') != "https://qgtgeawsdxynnqsuanbl.supabase.co") {
            val projectSuffix = MessageDigest.getInstance("SHA-256")
                .digest(supabaseUrl.trimEnd('/').toByteArray()).take(6).joinToString("") { byte -> "%02x".format(byte) }
            applicationId = "org.wierichs.jagdkarte.test$projectSuffix"
        }
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLIC_KEY", "\"$supabasePublicKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (releaseSigningEnabled) {
        signingConfigs.create("localRelease") {
            val keystore = file(requiredReleaseSigningProperty("jagdkarteReleaseStoreFile"))
            require(keystore.isFile) { "Release-Signierung: Keystore-Datei nicht gefunden." }
            storeFile = keystore
            storePassword = requiredReleaseSigningProperty("jagdkarteReleaseStorePassword")
            keyAlias = requiredReleaseSigningProperty("jagdkarteReleaseKeyAlias")
            keyPassword = requiredReleaseSigningProperty("jagdkarteReleaseKeyPassword")
        }
    }

    buildTypes {
        release {
            if (releaseSigningEnabled) signingConfig = signingConfigs.getByName("localRelease")
            proguardFiles("proguard-rules.pro")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            assets.directories.add(rootProject.file("docs/help").absolutePath)
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val apkFileName = if (variant.buildType == "debug") "Jagdbruder-debug.apk" else "Jagdbruder.apk"
        variant.outputs.forEach { output -> output.outputFileName.set(apkFileName) }
    }
}

dependencies {
    implementation(libs.osmdroid)
    implementation(libs.osmdroid.mapsforge) {
        exclude(group = "com.caverock", module = "androidsvg")
    }
    implementation(libs.androidsvg)
    implementation(libs.markwon)
    implementation(libs.jts.core)
    implementation(libs.jts.geojson)
    implementation(libs.zxing.core)
    implementation(libs.okhttp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.play.services.code.scanner)
    implementation(libs.play.services.nearby)
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}