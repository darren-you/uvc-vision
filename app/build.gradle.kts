import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseDebugSourcePath = "app/src/main/java/com/silicicsoft/doctorcam/UvcVisionApp.kt"
val releaseDebugCheckerPath = "tool/CheckReleaseDebug.java"
val javaExecutableName = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "java.exe"
} else {
    "java"
}
val javaExecutable = file("${System.getProperty("java.home")}/bin/$javaExecutableName")
val keytoolExecutableName = if (javaExecutableName.endsWith(".exe")) "keytool.exe" else "keytool"
val keytoolExecutable = file("${System.getProperty("java.home")}/bin/$keytoolExecutableName")

val verifyReleaseDebugDisabled = tasks.register<Exec>("verifyReleaseDebugDisabled") {
    group = "verification"
    description = "检查 Release 构建的 _debug 开关"
    workingDir(rootProject.projectDir)
    executable(javaExecutable)
    args(releaseDebugCheckerPath, "--source", releaseDebugSourcePath, "--display", releaseDebugSourcePath)
    inputs.file(rootProject.file(releaseDebugCheckerPath))
    inputs.file(rootProject.file(releaseDebugSourcePath))
}

val releaseDebugFixturePath = providers.gradleProperty("releaseDebugFixture")
    .orElse("app/src/testFixtures/release-debug-false.kt").get()
val verifyReleaseDebugFixture = tasks.register<Exec>("verifyReleaseDebugFixture") {
    group = "verification"
    description = "使用 fixture 验证 Release _debug 检查器"
    workingDir(rootProject.projectDir)
    executable(javaExecutable)
    args(releaseDebugCheckerPath, "--source", releaseDebugFixturePath, "--display", releaseDebugFixturePath)
    inputs.file(rootProject.file(releaseDebugCheckerPath))
    inputs.file(rootProject.file(releaseDebugFixturePath))
}

val releaseSigningScript = rootProject.file("tool/prepare_release_signing.py")
val releaseKeystore = layout.buildDirectory.file("signing/uvc-vision-release.p12")
val releaseSigningPassword = providers.environmentVariable("UVC_VISION_ANDROID_SIGNING_PASSWORD").orNull
val releaseSigningAlias = providers.environmentVariable("UVC_VISION_ANDROID_SIGNING_KEY_ALIAS").orNull
val releaseSigningCertificate = providers.environmentVariable("UVC_VISION_ANDROID_SIGNING_CERT_SHA256")
    .orElse("").get()
val prepareReleaseSigning = tasks.register<Exec>("prepareReleaseSigning") {
    group = "build setup"
    description = "从固定 Profile 准备并校验正式 PKCS12 签名"
    dependsOn(verifyReleaseDebugDisabled)
    commandLine("python3", releaseSigningScript, "--keytool", keytoolExecutable)
    inputs.file(releaseSigningScript)
    outputs.file(releaseKeystore)
    // 每次正式构建均核对本次 Profile，不能复用上次运行物化的签名文件。
    outputs.upToDateWhen { false }
}

val paddleLiteSdkDirectory = layout.buildDirectory.dir("native-sdk/paddle-lite")
val paddleLiteSdkPath = paddleLiteSdkDirectory.get().asFile.absolutePath
val paddleLitePreparationScript = rootProject.file("tool/prepare_paddle_lite.py")
val preparePaddleLiteSdk = tasks.register<Exec>("preparePaddleLiteSdk") {
    group = "build setup"
    description = "下载并校验固定版本的官方 Paddle Lite ARM64 SDK"
    commandLine("python3", paddleLitePreparationScript, "--output-dir", paddleLiteSdkPath)
    inputs.file(paddleLitePreparationScript)
    outputs.files(
        paddleLiteSdkDirectory.map { it.file("include/paddle_api.h") },
        paddleLiteSdkDirectory.map { it.file("include/paddle_place.h") },
        paddleLiteSdkDirectory.map { it.file("lib/arm64-v8a/libpaddle_api_light_bundled.a") },
    )
}
tasks.matching {
    it.name == "preBuild" || it.name.startsWith("configureCMake")
}.configureEach { dependsOn(preparePaddleLiteSdk) }

val nativeAlignmentChecker = rootProject.file("tool/check_native_alignment.py")
val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
val debugNativeAlignmentReport = layout.buildDirectory.file("reports/native-alignment-debug.json")
val verifyDebugNativeAlignment = tasks.register<Exec>("verifyDebugNativeAlignment") {
    group = "verification"
    description = "检查 Debug APK 全部 Native 库的 16 KiB ELF 与 ZIP 对齐"
    dependsOn("assembleDebug")
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", nativeAlignmentChecker, debugApk.get().asFile,
        "--report", debugNativeAlignmentReport.get().asFile,
    )
    inputs.file(nativeAlignmentChecker)
    inputs.file(debugApk)
    outputs.file(debugNativeAlignmentReport)
}

// 版本取 release.json；正式构建时 Fast Deploy 用环境变量给出本次的版本与构建号。
val releaseVersion = (groovy.json.JsonSlurper().parse(rootProject.file("release.json")) as Map<*, *>)["version"] as String
val clientRunId = providers.environmentVariable("FAST_DEPLOY_RUN_ID").orElse("").get()
val clientVersion = providers.environmentVariable("FAST_DEPLOY_VERSION").orElse(releaseVersion).get()
val clientBuildNumber = providers.environmentVariable("FAST_DEPLOY_BUILD_NUMBER").orNull
require(clientRunId.isEmpty() || clientBuildNumber != null) { "正式 Android 构建必须由 Fast Deploy 给出构建号" }
// 本地构建使用 1，不消耗正式编号。
val clientVersionCode = clientBuildNumber?.toInt() ?: 1
val clientLocalValidation = clientRunId.isEmpty()

android {
    namespace = "com.silicicsoft.doctorcam"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get().also {
        require(it == "debug" || it == "release") { "testBuildType 只允许 debug 或 release" }
    }

    defaultConfig {
        applicationId = "com.silicicsoft.doctorcam"
        minSdk = 26
        targetSdk = 37
        versionCode = clientVersionCode
        versionName = clientVersion
        manifestPlaceholders["clientLocalValidation"] = clientLocalValidation.toString()
        manifestPlaceholders["clientRunId"] = clientRunId
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DPADDLE_LITE_SDK_DIR=$paddleLiteSdkPath",
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore.get().asFile
            storeType = "PKCS12"
            storePassword = releaseSigningPassword
            keyPassword = releaseSigningPassword
            keyAlias = releaseSigningAlias
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // 保留本项目 NDK 30 的唯一共享 C++ 运行库，制品门禁回读实际摘要。
        jniLibs.pickFirsts += "lib/arm64-v8a/libc++_shared.so"
    }
}

val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
val releaseSignatureChecker = rootProject.file("tool/check_release_apk.py")
val releaseSignatureReport = layout.buildDirectory.file("reports/release-apk-signature.json")
val androidSdkDirectory = androidComponents.sdkComponents.sdkDirectory.get().asFile
val verifyReleaseSignature = tasks.register<Exec>("verifyReleaseSignature") {
    group = "verification"
    description = "检查最终 Release APK 的签名证书和非调试发布标志"
    dependsOn("packageRelease")
    commandLine(
        "python3", releaseSignatureChecker, releaseApk.get().asFile,
        "--apksigner", androidSdkDirectory.resolve("build-tools/37.0.0/apksigner"),
        "--apkanalyzer", androidSdkDirectory.resolve("cmdline-tools/latest/bin/apkanalyzer"),
        "--report", releaseSignatureReport.get().asFile,
    )
    inputs.file(releaseSignatureChecker)
    inputs.file(releaseApk)
    inputs.property("certificateSha256", releaseSigningCertificate)
    outputs.file(releaseSignatureReport)
}
val releaseNativeAlignmentReport = layout.buildDirectory.file("reports/native-alignment-release.json")
val verifyReleaseNativeAlignment = tasks.register<Exec>("verifyReleaseNativeAlignment") {
    group = "verification"
    description = "检查最终 Release APK 全部 Native 库的 16 KiB ELF 与 ZIP 对齐"
    dependsOn("packageRelease")
    commandLine(
        "python3", nativeAlignmentChecker, releaseApk.get().asFile,
        "--report", releaseNativeAlignmentReport.get().asFile,
    )
    inputs.file(nativeAlignmentChecker)
    inputs.file(releaseApk)
    outputs.file(releaseNativeAlignmentReport)
}
// 两个检查依赖真实打包任务；assemble 等待检查成功，避免反向依赖造成任务环。
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyReleaseSignature, verifyReleaseNativeAlignment)
}
tasks.matching {
    it.name == "validateSigningRelease" || it.name == "validateSigningReleaseAndroidTest"
}.configureEach { dependsOn(prepareReleaseSigning) }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.opencv)
    implementation(libs.uvc.camera)
    implementation(libs.onnx.runtime)

    constraints {
        implementation(libs.androidx.appcompat)
        implementation(libs.androidx.annotation)
        implementation(libs.hutool.core)
    }

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercaseChar() }
        if (variant.buildType == "release") {
            tasks.matching { it.name == "pre${name}Build" }.configureEach {
                dependsOn(verifyReleaseDebugDisabled, prepareReleaseSigning)
            }
        }
    }
}
