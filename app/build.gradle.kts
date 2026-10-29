import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

val baseVersion = "0.1.4"

val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrElse { 1 }

val gitHash: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrElse { "unknown" }

android {
    namespace = "dev.neroices.zibzip"
    compileSdk = 37
    ndkPath = "/opt/android-ndk"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "dev.neroices.zibzip"
        minSdk = 30
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$baseVersion-r$gitHash"

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }

    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("release.storeFile")
                ?: localProperties.getProperty("storeFile")
                ?: localProperties.getProperty("keystore.path")
                ?: localProperties.getProperty("keystore.file")
            val storePass = localProperties.getProperty("release.storePassword")
                ?: localProperties.getProperty("storePassword")
                ?: localProperties.getProperty("keystore.password")
            val kAlias = localProperties.getProperty("release.keyAlias")
                ?: localProperties.getProperty("keyAlias")
                ?: localProperties.getProperty("keystore.alias")
            val kPass = localProperties.getProperty("release.keyPassword")
                ?: localProperties.getProperty("keyPassword")
                ?: localProperties.getProperty("keystore.keyPassword")
                ?: storePass

            if (!storeFilePath.isNullOrBlank() && !storePass.isNullOrBlank() && !kAlias.isNullOrBlank()) {
                val resolvedFile = file(storeFilePath).let { f ->
                    if (f.isAbsolute) f else rootProject.file(storeFilePath)
                }
                storeFile = resolvedFile
                storePassword = storePass
                keyAlias = kAlias
                keyPassword = kPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Map each ABI to a distinct integer code to ensure 64-bit has higher priority
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            if (abi != null) {
                output.versionCode.set((gitCommitCount * 10) + (abiCodes[abi] ?: 0))
            }
        }
    }
}

dependencies {
    // AndroidX / Jetpack
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    // Code analysis
    detektPlugins(libs.detekt.formatting)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
        checkstyle.required.set(true)
    }
}

// --- Cargo NDK Tasks per Architecture ---
val targetAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val ndkDirPath = project.findProperty("ndk.dir") as? String
    ?: System.getenv("ANDROID_NDK_HOME")
    ?: "/opt/android-ndk"
val cargoBinPath = File(System.getProperty("user.home"), ".cargo/bin").absolutePath
val updatedPath = "$cargoBinPath:" + (System.getenv("PATH") ?: "")
val rustDirPath = rootProject.file("rust")
val jniLibsDirPath = file("src/main/jniLibs").absolutePath

targetAbis.forEach { abi ->
    val taskName = "cargoBuild_" + abi.replace('-', '_')
    tasks.register<Exec>(taskName) {
        description = "Compile Rust native library for $abi using cargo-ndk"
        group = "build"
        workingDir = rustDirPath
        environment("ANDROID_NDK_HOME", ndkDirPath)
        environment("PATH", updatedPath)
        commandLine("cargo", "ndk", "-t", abi, "-o", jniLibsDirPath, "build", "--release")
    }
}

tasks.register<Exec>("cargoNdkBuild") {
    description = "Compile Rust native library for all target ABIs using cargo-ndk"
    group = "build"
    workingDir = rustDirPath
    environment("ANDROID_NDK_HOME", ndkDirPath)
    environment("PATH", updatedPath)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", jniLibsDirPath,
        "build", "--release"
    )
}

tasks.register<Exec>("cargoBuildHost") {
    description = "Compile Rust native library for host desktop JVM unit tests"
    group = "build"
    workingDir = rustDirPath
    environment("PATH", updatedPath)
    commandLine("cargo", "build", "--release")
}

tasks.withType<Test>().configureEach {
    dependsOn("cargoBuildHost")
}

// Hook into Android's build process so the native libs are always compiled
tasks.configureEach {
    if (name == "preBuild" || name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("cargoNdkBuild")
    }
}

tasks.register("cargoBuild") {
    description = "Alias to compile all architectures via cargo-ndk"
    group = "build"
    dependsOn("cargoNdkBuild")
}

