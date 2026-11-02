plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun prop(name: String) = providers.gradleProperty(name).get()

fun git(vararg args: String) =
    providers
        .exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim() }
        .orElse("")

// versionCode is the commit count and versionName is "<semver>+<short sha>", so every build on the phone says where it came from.
val commitCount = git("rev-list", "--count", "HEAD").map { it.toIntOrNull() ?: 1 }.get()
val commitSha = git("rev-parse", "--short", "HEAD").map { it.ifEmpty { "unknown" } }.get()

// The phone's Stockfish ships as libstockfish.so for arm64. scripts/lab/prepare fetches it into $LAB_CACHE.
@Suppress("UNCHECKED_CAST")
val stockfishAndroid = rootProject.extra["stockfishAndroid"] as Provider<File>
val stockfishJniLibs = layout.buildDirectory.dir("generated/stockfish/jniLibs")
val stockfishLibName = "libstockfish.so"
val stockfishAbi = "arm64-v8a"

val stageStockfish by tasks.registering {
    val target = stockfishJniLibs.map { it.file("$stockfishAbi/$stockfishLibName") }
    val source = stockfishAndroid
    inputs.files(source.map { files(it) }.orElse(files()))
    outputs.file(target)
    doLast {
        val binary =
            source.orNull?.takeIf { it.isFile }
                ?: error("Stockfish for Android is missing: run scripts/lab/prepare (it needs LAB_CACHE).")
        target.get().asFile.let {
            it.parentFile.mkdirs()
            binary.copyTo(it, overwrite = true)
            it.setExecutable(true)
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(stageStockfish)
}

android {
    namespace = prop("postmortem.applicationId")
    compileSdk = prop("postmortem.compileSdk").toInt()
    buildToolsVersion = prop("postmortem.buildTools")

    defaultConfig {
        applicationId = prop("postmortem.applicationId")
        minSdk = prop("postmortem.minSdk").toInt()
        targetSdk = prop("postmortem.targetSdk").toInt()
        versionCode = commitCount
        versionName = "${prop("postmortem.versionName")}+$commitSha"
        buildConfigField("String", "ENGINE_LIBRARY", "\"$stockfishLibName\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        val java = JavaVersion.toVersion(prop("postmortem.javaVersion"))
        sourceCompatibility = java
        targetCompatibility = java
    }

    sourceSets
        .getByName("test")
        .resources.directories
        .add("../core/src/test/resources")

    packaging {
        // The engine runs as a process, so it must be a real file in nativeLibraryDir, not mapped from the APK.
        jniLibs.useLegacyPackaging = true
    }

    sourceSets
        .getByName("main")
        .jniLibs.directories
        .add(stockfishJniLibs.get().asFile.path)

    buildTypes {
        debug {
            // Android shows a blocking "not 16 KB-compatible" dialog for debuggable apps (issue #4); it covers the
            // UI the smoke check drives. The build is still signed with the debug key. This hides the warning, it does not fix the alignment.
            isDebuggable = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget(prop("postmortem.javaVersion")),
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
}
