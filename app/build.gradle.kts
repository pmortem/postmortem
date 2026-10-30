plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun prop(name: String) = providers.gradleProperty(name).get()

android {
    namespace = prop("postmortem.applicationId")
    compileSdk = prop("postmortem.compileSdk").toInt()
    buildToolsVersion = prop("postmortem.buildTools")

    defaultConfig {
        applicationId = prop("postmortem.applicationId")
        minSdk = prop("postmortem.minSdk").toInt()
        targetSdk = prop("postmortem.targetSdk").toInt()
        versionCode = prop("postmortem.versionCode").toInt()
        versionName = prop("postmortem.versionName")
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

    sourceSets.getByName("test").resources.directories.add("../core/src/test/resources")

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(prop("postmortem.javaVersion")))
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
    testImplementation(libs.kotlin.test)
}
