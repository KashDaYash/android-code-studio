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
 * [TermuxSession] used to run `idesetup` during automatic installation.
 *
 * OEM (OPPO/ColorOS) + Android 10+ W^X: files under app data that remain **writable**
 * cannot be exec'd → "Permission denied". Fix is chmod **0500** (strip write bit).
 * `idesetup` itself is loaded from jniLibs (`libidesetup.so`) which is always executable;
 * the bootstrap shell (`bash`) under PREFIX/bin must also be 0500 before idesetup execvp's it.
 */
class IdesetupSession
private constructor(
    terminalSession: TerminalSession,
    executionCommand: ExecutionCommand,
    termuxSessionClient: TermuxSession.TermuxSessionClient?,
    setStdoutOnExit: Boolean,
    private val script: File,
) : TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit) {

  companion object {

    private val log = LoggerFactory.getLogger(IdesetupSession::class.java)

    private const val NATIVE_LIB_NAME = "libidesetup.so"
    /** r-x------ — no write bit → satisfies W^X on OEM ROMs */
    private const val MODE_EXEC_ONLY = 320 // 0500 octal

    @JvmStatic
    fun wrap(session: TermuxSession?, script: File): IdesetupSession? {
      return session?.let { IdesetupSession(it, script) }
    }

    /**
     * Ensure PREFIX/bin (and common exec dirs) are executable without write bit.
     * Safe to call repeatedly; ignores missing dirs.
     */
    @JvmStatic
    fun repairPrefixExecutePermissions(context: Context) {
      val roots =
          listOfNotNull(
              try {
                Environment.BIN_DIR
              } catch (_: Throwable) {
                null
              },
              File(context.filesDir, "usr/bin"),
              File(context.filesDir, "usr/libexec"),
              File(context.filesDir, "usr/lib/apt/methods"),
          )
      for (dir in roots) {
        if (!dir.isDirectory) continue
        dir.listFiles()?.forEach { f ->
          if (!f.isFile) return@forEach
          try {
            f.setWritable(false, false)
            f.setExecutable(true, true)
            f.setReadable(true, true)
            Os.chmod(f.absolutePath, MODE_EXEC_ONLY)
          } catch (t: Throwable) {
            log.debug("chmod {} failed: {}", f.name, t.message)
          }
        }
      }
      for (name in listOf("bash", "sh", "dash", "busybox")) {
        val f = File(context.filesDir, "usr/bin/$name")
        if (f.isFile) {
          try {
            f.setWritable(false, false)
            f.setExecutable(true, true)
            Os.chmod(f.absolutePath, MODE_EXEC_ONLY)
            log.info(
                "PREFIX {} mode fixed canExecute={} path={}",
                name,
                f.canExecute(),
                f.absolutePath,
            )
          } catch (t: Throwable) {
            log.warn("Failed to fix {}: {}", name, t.message)
          }
        }
      }
    }

    /**
     * Resolve executable idesetup path.
     * 1. nativeLibraryDir/libidesetup.so (always executable)
     * 2. assets → PREFIX/bin fallback
     * Always repairs PREFIX execute bits so idesetup's execvp("bash") can succeed.
     */
    @JvmStatic
    fun createScript(context: Context): File? {
      repairPrefixExecutePermissions(context)

      val nativeLib = File(context.applicationInfo.nativeLibraryDir, NATIVE_LIB_NAME)
      if (nativeLib.isFile && nativeLib.length() > 0) {
        try {
          nativeLib.setWritable(false, false)
          nativeLib.setExecutable(true, false)
          Os.chmod(nativeLib.absolutePath, MODE_EXEC_ONLY)
        } catch (_: Throwable) {
        }
        log.info(
            "Using native idesetup: {} ({} bytes, canExecute={})",
            nativeLib.absolutePath,
            nativeLib.length(),
            nativeLib.canExecute(),
        )
        return nativeLib
      }
      log.warn("libidesetup.so not in nativeLibraryDir={}", context.applicationInfo.nativeLibraryDir)

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
      try {
        script.setWritable(false, false)
        script.setReadable(true, false)
        script.setExecutable(true, false)
        FileUtils.setFilePermissions("idesetupScript", script.absolutePath, "r-x")
        Os.chmod(script.absolutePath, MODE_EXEC_ONLY)
      } catch (t: Throwable) {
        log.warn("finalize chmod failed: {}", t.message)
      }
      log.info(
          "idesetup ready: path={} size={} canExecute={}",
          script.absolutePath,
          script.length(),
          script.canExecute(),
      )
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
