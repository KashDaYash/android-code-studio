import com.tom.rv2ide.plugins.NoDesugarPlugin
import com.tom.rv2ide.build.config.BuildConfig
import java.io.File

apply { plugin(NoDesugarPlugin::class.java) }

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.mohammedbaqernull.logger.logwire"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig { minSdk = 21 }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures { aidl = true }

    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.0")
}

tasks.register("fixAarName") {
    doLast {
        val aarDir = layout.buildDirectory.dir("outputs/aar").get().asFile
        val assetsRoot = File(projectDir, "../../core/app/src/main/assets").canonicalFile
        val assetsCommon = File(assetsRoot, "data/common")
        assetsRoot.mkdirs()
        assetsCommon.mkdirs()
        val files = aarDir.listFiles { f -> f.extension == "aar" } ?: return@doLast
        val finalName = "logger-runtime.aar"
        val aar = files.maxByOrNull { it.lastModified() } ?: return@doLast
        val renamed = File(aar.parentFile, finalName)
        if (aar.name != finalName) {
            renamed.delete()
            aar.renameTo(renamed)
        }
        // Root assets (IDEApplication) + data/common (ToolsManager.getCommonAsset)
        renamed.copyTo(File(assetsRoot, finalName), overwrite = true)
        renamed.copyTo(File(assetsCommon, finalName), overwrite = true)
        logger.lifecycle("Packaged {} ({} bytes) into app assets", finalName, renamed.length())
    }
}

plugins.withId("com.android.library") {
    afterEvaluate {
        listOf("bundleReleaseAar", "bundleDebugAar").forEach { taskName ->
            tasks.findByName(taskName)?.finalizedBy("fixAarName")
        }
    }
}

gradle.projectsEvaluated {
    // Ensure every ACS app package step has logger-runtime.aar in assets
    tasks.matching {
        it.path.startsWith(":core:app:pre") && it.path.endsWith("Build")
    }.configureEach {
        dependsOn(":external:logwire:assembleRelease")
        dependsOn(":external:logwire:fixAarName")
    }
}
