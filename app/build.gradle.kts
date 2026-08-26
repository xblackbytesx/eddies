import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// CI passes -PappVersionName=<tag> -PappVersionCode=<run number>; local builds
// fall back to a dev version.
val appVersionName: String = (findProperty("appVersionName") as String?) ?: "0.1.0-dev"
val appVersionCode: Int = ((findProperty("appVersionCode") as String?) ?: "1").toInt()

android {
    namespace = "com.eddies.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eddies.app"
        minSdk = 35
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * Demo is a separate installable app, not a runtime switch.
     *
     * A different applicationId means a different data directory, so the demo
     * build physically cannot read the real ledger. That is an isolation the
     * operating system enforces, rather than one that depends on six database
     * write paths all remembering to check a flag, two of which run outside any
     * screen (RootViewModel on launch, DailyWorker on a schedule) and could fire
     * mid-screenshot.
     *
     * Both flavours share every line of app code. The only difference is which
     * DemoSeeder implementation is on the source path.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
            // Defined here rather than in strings.xml: a resValue and a resource
            // file entry with the same name are a duplicate-resource error.
            resValue("string", "app_name", "Eddies")
        }
        create("demo") {
            dimension = "distribution"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            resValue("string", "app_name", "Eddies Demo")
        }
    }

    signingConfigs {
        val debugKeystore = rootProject.file("keystore/debug.keystore")
        if (debugKeystore.exists()) {
            getByName("debug") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Release signing from keystore.properties or env vars; unsigned otherwise.
        val props = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val storePath = props.getProperty("storeFile") ?: System.getenv("RELEASE_STORE_FILE")
        val storePass = props.getProperty("storePassword") ?: System.getenv("RELEASE_STORE_PASSWORD")
        val alias = props.getProperty("keyAlias") ?: System.getenv("RELEASE_KEY_ALIAS")
        val keyPass = props.getProperty("keyPassword") ?: System.getenv("RELEASE_KEY_PASSWORD")
        if (!storePath.isNullOrBlank() && !storePass.isNullOrBlank() &&
            !alias.isNullOrBlank() && !keyPass.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Pin the JVM locale and zone so money and date formatting assertions do
        // not pass in the container and fail on a workstation.
        unitTests.all {
            it.systemProperty("user.language", "en")
            it.systemProperty("user.country", "US")
            it.systemProperty("user.timezone", "UTC")
        }
    }

    // SQLCipher ships a native library per ABI, and at ~5 MB each the four of
    // them are two thirds of the APK. Splitting means a phone downloads one
    // instead of four: the arm64-v8a build is roughly 15 MB against 29 MB.
    //
    // The universal APK is still produced, because this app is sideloaded and
    // "download the one that works" has to remain an option for anyone who does
    // not know their ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.tink.android)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
}
