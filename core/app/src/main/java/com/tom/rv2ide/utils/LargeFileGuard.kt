/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.utils

import java.io.File
import java.util.Locale

/**
 * Soft limits for opening large files to reduce OOM / lag risk on-device.
 *
 * - [WARN_BYTES]: user is warned before open
 * - [HARD_BYTES]: open is blocked (too risky on typical phones)
 * - [SAFE_MODE_BYTES]: editor opens without LSP / heavy diagnostics
 */
object LargeFileGuard {

  /** Warn the user before opening (2 MB). */
  const val WARN_BYTES: Long = 2L * 1024L * 1024L

  /** Refuse to open above this size (10 MB). */
  const val HARD_BYTES: Long = 10L * 1024L * 1024L

  /** Skip LSP and heavy analysis at/above this size (2 MB). */
  const val SAFE_MODE_BYTES: Long = WARN_BYTES

  fun sizeOf(file: File): Long =
      try {
        if (file.exists()) file.length() else 0L
      } catch (_: Exception) {
        0L
      }

  fun needsWarning(file: File): Boolean {
    val size = sizeOf(file)
    return size >= WARN_BYTES && size < HARD_BYTES
  }

  fun isBlocked(file: File): Boolean = sizeOf(file) >= HARD_BYTES

  fun shouldUseSafeMode(file: File): Boolean = sizeOf(file) >= SAFE_MODE_BYTES

  fun formatSize(file: File): String {
    val bytes = sizeOf(file).toDouble()
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
  }
}
