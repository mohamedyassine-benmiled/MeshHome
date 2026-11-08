plugins {
    id("com.android.application")
    kotlin("android")
}

val releaseKeystorePath = providers.environmentVariable("MESH_HOME_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("MESH_HOME_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MESH_HOME_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MESH_HOME_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
check(releaseSigningValues.all { it == null } || releaseSigningValues.all { !it.isNullOrBlank() }) {
    "Release signing requires MESH_HOME_KEYSTORE_PATH, MESH_HOME_KEYSTORE_PASSWORD, MESH_HOME_KEY_ALIAS, and MESH_HOME_KEY_PASSWORD."
}

android {
    namespace = "com.mybm.meshhome"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mybm.meshhome"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").orNull?.toInt() ?: 9
        versionName = providers.gradleProperty("releaseVersionName").orNull ?: "0.3.6-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

if (providers.gradleProperty("requireNative").isPresent) {
    val verifyNative by tasks.registering {
        doLast {
            check(file("src/main/jniLibs/arm64-v8a/libmeshhome.so").isFile) {
                "Native engine missing. Run scripts/build-native.py before building a native-enabled APK."
            }
            check(file("src/main/assets/licenses/libtelio-LICENSE.txt").isFile) {
                "Native engine license missing. Re-run scripts/build-native.py."
            }
        }
    }
    tasks.named("preBuild").configure { dependsOn(verifyNative) }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        doFirst {
            check(releaseKeystorePath != null) {
                "Release artifacts must be signed with the persistent MeshHome keystore; see docs/ANDROID_SIGNING.md."
            }
        }
    }
}
