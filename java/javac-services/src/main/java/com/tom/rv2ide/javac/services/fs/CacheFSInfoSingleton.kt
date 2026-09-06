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

package com.tom.rv2ide.javac.services.fs

import com.tom.rv2ide.utils.VMUtils
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Singleton helper around openjdk.tools.javac.file.CacheFSInfo.
 *
 * IMPORTANT: Do NOT extend CacheFSInfo at the class level. If the openjdk class is
 * missing from the APK dex (ClassNotFoundException / NoClassDefFoundError), project
 * initialization must still succeed. We load the real implementation via reflection
 * and fall back to no-op / path.normalize() when unavailable.
 *
 * @author Akash Yadav
 */
object CacheFSInfoSingleton {

  const val TEST_PROP_ENABLED_ON_JVM = "ide.testing.javac.fsCache.isEnabledOnJVM"
  private val log = LoggerFactory.getLogger(CacheFSInfoSingleton::class.java)

  /** Reflective instance of openjdk.tools.javac.file.CacheFSInfo, or null if unavailable. */
  private val delegate: Any? by lazy {
    try {
      val clazz = Class.forName("openjdk.tools.javac.file.CacheFSInfo")
      clazz.getDeclaredConstructor().newInstance()
    } catch (t: Throwable) {
      log.warn(
        "openjdk.tools.javac.file.CacheFSInfo is not on the runtime classpath; " +
          "FS attribute caching disabled. ({})",
        t.toString()
      )
      null
    }
  }

  /**
   * Returns the canonical path for [file], using CacheFSInfo when available.
   * Falls back to absolute normalized path if CacheFSInfo is missing.
   */
  @JvmStatic
  fun getCanonicalFile(file: Path): Path {
    val d = delegate ?: return file.toAbsolutePath().normalize()
    return try {
      d.javaClass.getMethod("getCanonicalFile", Path::class.java).invoke(d, file) as Path
    } catch (t: Throwable) {
      log.debug("getCanonicalFile fallback for {}", file, t)
      file.toAbsolutePath().normalize()
    }
  }

  /** Caches information about the given [Path]. No-op if CacheFSInfo is unavailable. */
  @JvmOverloads
  fun cache(file: Path, cacheJarClasspath: Boolean = true) {

    if (System.getProperty(TEST_PROP_ENABLED_ON_JVM, null) != "true") {
      if (VMUtils.isJvm()) {
        return
      }
    }

    val d = delegate ?: return

    try {
      // Cache canonical path
      d.javaClass.getMethod("getCanonicalFile", Path::class.java).invoke(d, file)

      // Cache attributes
      d.javaClass.getMethod("getAttributes", Path::class.java).invoke(d, file)

      // Cache jar classpath if requested
      if (cacheJarClasspath) {
        d.javaClass.getMethod("getJarClassPath", Path::class.java).invoke(d, file)
      }
    } catch (err: Throwable) {
      log.warn("Failed to cache jar file: {}", file, err)
    }
  }
}
