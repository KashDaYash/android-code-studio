/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.tom.rv2ide.build.config.BuildConfig


plugins {
    id("com.android.library")
    id("kotlin-android")
}



android {
    namespace = "${BuildConfig.packageName}.javac.services"

    defaultConfig {
        // Ensure openjdk.tools.javac classes are kept when consumers minify
        consumerProguardFiles("consumer-rules.pro")
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Composite module (metadata / wiring)
    api(libs.composite.javac)

    // CRITICAL: ship the actual openjdk/javac tool jars into the Android APK.
    // Composite java-library + files() alone can omit classes from the final dex,
    // which causes ClassNotFoundException: openjdk.tools.javac.file.CacheFSInfo
    // during WorkspaceModelBuilder / project initialization.
    api(files(rootProject.file("composite-builds/build-deps/libs/jdk-compiler.jar")))
    api(files(rootProject.file("composite-builds/build-deps/libs/java-compiler.jar")))

    implementation(libs.common.kotlin)
    implementation(libs.common.utilcode)
    implementation(libs.google.guava)

    implementation(projects.core.common)
    implementation(projects.logging.logger)
    
}
