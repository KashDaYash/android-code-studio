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

package com.tom.rv2ide.terminal

import android.content.Context
import android.system.Os
import com.termux.shared.file.FileUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession
import com.tom.rv2ide.app.configuration.CpuArch
import com.tom.rv2ide.app.configuration.IDEBuildConfigProvider
import com.tom.rv2ide.managers.ToolsManager
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import org.slf4j.LoggerFactory

/**
 * [TermuxSession] implementation that is used to run the `idesetup` script during automatic
 * installation.
 *
 * On many OEM ROMs (OPPO/ColorOS, etc.) the app-private data dir is mounted **noexec**, so
 * extracting `idesetup` to `files/usr/bin` and calling exec() fails with Permission denied.
 * The reliable path is the **native library directory** (`libidesetup.so` in jniLibs), which
 * Android always maps executable — same pattern as `libaapt2.so`.
 *
 * @author Akash Yadav
 */
class IdesetupSession
private constructor(
    terminalSession: TerminalSession,
    executionCommand: ExecutionCommand,
    termuxSessionClient: TermuxSessionClient?,
    setStdoutOnExit: Boolean,
    private val script: File,
) : TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit) {

  companion object {

    private val log = LoggerFactory.getLogger(IdesetupSession::class.java)

    private const val NATIVE_LIB_NAME = "libidesetup.so"

    @JvmStatic
    fun wrap(session: TermuxSession?, script: File): IdesetupSession? {
      return session?.let { IdesetupSession(it, script) }
    }

    /**
     * Resolve an executable `idesetup` path.
     *
     * Priority:
     * 1. `nativeLibraryDir/libidesetup.so` (always executable on Android)
     * 2. Extract from assets into PREFIX/bin + chmod (fallback)
     */
    @JvmStatic
    fun createScript(context: Context): File? {
      // --- Preferred: jniLibs native library (fixes OEM noexec / Permission denied) ---
      val nativeLib = File(context.applicationInfo.nativeLibraryDir, NATIVE_LIB_NAME)
      if (nativeLib.isFile && nativeLib.length() > 0) {
        try {
          nativeLib.setExecutable(true, false)
          Os.chmod(nativeLib.absolutePath, 493) // 0755
        } catch (_: Throwable) {
          // native dir is typically executable even if chmod fails
        }
        if (nativeLib.canExecute() || nativeLib.isFile) {
          log.info(
              "Using native idesetup: {} ({} bytes, canExecute={})",
              nativeLib.absolutePath,
              nativeLib.length(),
              nativeLib.canExecute(),
          )
          return nativeLib
        }
      } else {
        log.warn(
            "libidesetup.so not in nativeLibraryDir={}",
            context.applicationInfo.nativeLibraryDir,
        )
      }

      // --- Fallback: extract from assets to PREFIX/bin ---
      val binDir =
          try {
            Environment.BIN_DIR ?: File(context.filesDir, "usr/bin")
          } catch (_: Throwable) {
            File(context.filesDir, "usr/bin")
          }
      if (!binDir.exists()) binDir.mkdirs()

      val script = File(binDir, "idesetup")
      if (script.exists()) script.delete()

      if (!writeIdesetupScript(context, script)) {
        log.error("Failed to materialize idesetup from assets")
        return null
      }
      return finalizeExecutable(script)
    }

    private fun finalizeExecutable(script: File): File? {
      if (!script.exists() || script.length() == 0L) {
        log.error("idesetup missing or empty at {}", script.absolutePath)
        return null
      }

      script.setReadable(true, false)
      script.setWritable(true, true)
      script.setExecutable(true, false)
      FileUtils.setFilePermissions("idesetupScript", script.absolutePath, "rwx")
      try {
        Os.chmod(script.absolutePath, 493)
      } catch (t: Throwable) {
        log.warn("Os.chmod failed for {}: {}", script.absolutePath, t.message)
      }

      if (!script.canExecute()) {
        log.error(
            "idesetup not executable at {} (size={}). " +
                "OEM may block exec from app data; rebuild with jniLibs/libidesetup.so.",
            script.absolutePath,
            script.length(),
        )
      } else {
        log.info("idesetup ready: path={} size={}", script.absolutePath, script.length())
      }
      return script
    }

    private fun writeIdesetupScript(context: Context, script: File): Boolean {
      return try {
        val cpuArch = IDEBuildConfigProvider.getInstance().cpuArch
        val folderName =
            when (cpuArch) {
              CpuArch.AARCH64 -> "arm64"
              CpuArch.ARM -> "arm"
              CpuArch.X86_64 -> "x86_64"
              CpuArch.X86 -> "x86"
            }

        val assetCandidates =
            listOf(
                ToolsManager.getCommonAsset("$folderName/idesetup"),
                "$folderName/idesetup",
                "data/common/$folderName/idesetup",
            )

        var opened = false
        for (asset in assetCandidates) {
          try {
            context.assets.open(asset).use { inputStream ->
              FileOutputStream(script).use { outputStream -> inputStream.copyTo(outputStream) }
            }
            log.info("Wrote idesetup from assets '{}' -> {}", asset, script.absolutePath)
            opened = true
            break
          } catch (e: Exception) {
            log.debug("Asset '{}' not available: {}", asset, e.message)
          }
        }

        if (!opened) {
          log.error("No idesetup asset found for arch {} ({})", cpuArch, folderName)
          return false
        }

        val magic = ByteArray(4)
        script.inputStream().use { it.read(magic) }
        if (magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte()) {
          log.error("idesetup at {} is not an ELF binary", script.absolutePath)
          return false
        }
        true
      } catch (e: Exception) {
        log.error("Failed to write idesetup script: {}", e.message, e)
        false
      }
    }
  }

  private constructor(
      src: TermuxSession,
      script: File,
  ) : this(
      src.terminalSession,
      src.executionCommand,
      src.termuxSessionClient,
      src.isSetStdoutOnExit,
      script,
  )

  override fun finish() {
    super.finish()
  }
}
