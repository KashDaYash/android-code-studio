package com.tom.rv2ide.build.config

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

import org.gradle.api.JavaVersion

/**
 * Build configuration for the IDE.
 *
 * @author Akash Yadav
 */
object BuildConfig {

  /**
   * Java/Kotlin namespace and install applicationId.
   * Must remain `com.tom.rv2ide` so Termux bootstrap binaries (PREFIX under
   * /data/data/com.tom.rv2ide/...) work. Side-by-side with official ACS needs
   * rebuilt native packages — not supported in this fork yet.
   */
  const val packageName = "com.tom.rv2ide"

  /** Same as packageName — required for Termux/bootstrap paths. */
  const val applicationId = "com.tom.rv2ide"

  /** The compile SDK version (can be higher than targetSdk). */
  const val compileSdk = 36
  
  /** The build tools version. */
  const val buildToolsVersion = "35.0.0"

  /** The minimum SDK version. */
  const val minSdk = 26

  /**
   * MUST stay at 28 (same as upstream ACS + official Termux).
   * Android 10+ W^X: targetSdk >= 29 blocks exec() of binaries under
   * /data/data/<pkg>/files (PREFIX/bin/bash, idesetup scripts, etc.) via
   * SELinux → "Permission denied". Upstream intentionally uses 28.
   * User project templates can still use targetSdk 36 — that is separate.
   */
  const val targetSdk = 28

  const val ndkVersion = "26.1.10909125"

  /** The source and target Java compatibility. */
  val javaVersion = JavaVersion.VERSION_11
}
