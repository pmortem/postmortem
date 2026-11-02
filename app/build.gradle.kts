import java.nio.ByteBuffer
import java.nio.ByteOrder

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

// Google Play needs 16 KB-aligned native code: fail the build when a LOAD segment of the engine is aligned to less.
val requiredLoadAlignment = 0x4000L

fun loadAlignments(elf: File): List<Long> {
    val header = ByteBuffer.wrap(elf.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val phoff = header.getLong(0x20).toInt()
    val phentsize = header.getShort(0x36).toInt()
    val phnum = header.getShort(0x38).toInt()
    return (0 until phnum)
        .map { phoff + it * phentsize }
        .filter { header.getInt(it) == 1 } // PT_LOAD
        .map { header.getLong(it + 0x30) } // p_align
}

val stageStockfish by tasks.registering {
    val target = stockfishJniLibs.map { it.file("$stockfishAbi/$stockfishLibName") }
    val source = stockfishAndroid
    inputs.files(source.map { files(it) }.orElse(files()))
    outputs.file(target)
    doLast {
        val binary =
            source.orNull?.takeIf { it.isFile }
                ?: error("Stockfish for Android is missing: run scripts/lab/prepare (it needs LAB_CACHE).")
        val aligned = loadAlignments(binary)
        check(aligned.isNotEmpty() && aligned.all { it >= requiredLoadAlignment }) {
            "$binary has LOAD segments aligned to ${aligned.map {
                "0x" +
                    it.toString(
                        16,
                    )
            }}, not 0x4000: rebuild it with scripts/lab/prepare."
        }
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

    sourceSets
        .getByName("main")
        .assets.directories
        // The splash scene is drawn at run time from design/splash-scene.svg, so the app ships the file the design lives in.
        .add(rootProject.file("design").path)

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

// Google Play also needs the APK itself 16 KB-aligned: zipalign checks it after every debug build.
val zipalign = androidComponents.sdkComponents.sdkDirectory.map { it.file("build-tools/${prop("postmortem.buildTools")}/zipalign") }
val apkDir = layout.buildDirectory.dir("outputs/apk/debug")
val checkApkAlignment by tasks.registering {
    inputs.dir(apkDir)
    doLast {
        val apk =
            apkDir
                .get()
                .asFile
                .listFiles { f -> f.extension == "apk" }
                ?.singleOrNull() ?: error("no debug APK in ${apkDir.get()}")
        val process = ProcessBuilder(zipalign.get().asFile.path, "-c", "-P", "16", "-v", "4", apk.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) {
            "zipalign -c -P 16 failed for $apk:\n${output.lines().filterNot {
                it.endsWith(
                    "(OK)",
                ) || it.endsWith("(OK - compressed)")
            }.joinToString("\n")}"
        }
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(checkApkAlignment) }

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
    implementation(libs.androidsvg)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
}
