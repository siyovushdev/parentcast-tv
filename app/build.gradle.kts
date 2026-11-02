plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.siyovush.parentcast.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.siyovush.parentcast.tv"
        minSdk = 33
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.0-m3"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("privileged") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-privileged-candidate"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    sourceSets.getByName("privileged").java.srcDir("src/debug/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:common:0.18.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.11.1")
    implementation("commons-io:commons-io:2.5")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Candidate must be newer than the existing v5 data APK if system-image integration is approved.
androidComponents {
    onVariants(selector().withBuildType("privileged")) { variant ->
        variant.outputs.forEach { output -> output.versionCode.set(6) }
    }
}
