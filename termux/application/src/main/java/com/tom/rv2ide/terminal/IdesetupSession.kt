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

    @JvmStatic
    fun wrap(session: TermuxSession?, script: File): IdesetupSession? {
      return session?.let { IdesetupSession(it, script) }
    }

    /**
     * Extract the architecture-matching `idesetup` binary and make it executable.
     *
     * Writes under `$PREFIX/bin` (Termux-safe) with [Os.chmod] 0755 to avoid
     * "Permission denied" on OEM devices when executing from plain files/tmp.
     */
    @JvmStatic
    fun createScript(context: Context): File? {
      // Prefer PREFIX/bin — executable location Termux expects
      val binDir =
          try {
            Environment.BIN_DIR ?: File(context.filesDir, "usr/bin")
          } catch (_: Throwable) {
            File(context.filesDir, "usr/bin")
          }
      if (!binDir.exists()) binDir.mkdirs()

      val script = File(binDir, "idesetup")
      // Remove stale non-executable copy from older installs
      if (script.exists()) {
        //noinspection ResultOfMethodCallIgnored
        script.delete()
      }

      if (!writeIdesetupScript(context, script)) {
        // Fallback: files/tmp (matches some older error paths)
        val tmpDir = File(context.filesDir, "tmp").also { it.mkdirs() }
        val fallback = File(tmpDir, "idesetup")
        if (fallback.exists()) fallback.delete()
        if (!writeIdesetupScript(context, fallback)) {
          return null
        }
        return finalizeExecutable(fallback)
      }

      return finalizeExecutable(script)
    }

    private fun finalizeExecutable(script: File): File? {
      if (!script.exists() || script.length() == 0L) {
        log.error("idesetup missing or empty at {}", script.absolutePath)
        return null
      }

      // 1) Java API
      script.setReadable(true, false)
      script.setWritable(true, true)
      script.setExecutable(true, false)

      // 2) Termux FileUtils
      FileUtils.setFilePermissions("idesetupScript", script.absolutePath, "rwx")

      // 3) Native chmod 0755 — most reliable on OEM (OPPO/ColorOS, etc.)
      try {
        Os.chmod(script.absolutePath, 493) // 0755
      } catch (t: Throwable) {
        log.warn("Os.chmod failed for {}: {}", script.absolutePath, t.message)
      }

      if (!script.canExecute()) {
        log.error(
            "idesetup is not executable at {} (size={}). exec will fail with Permission denied.",
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
          log.error("idesetup at {} is not an ELF binary (corrupt asset?)", script.absolutePath)
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
    if (script.absolutePath.contains("/tmp/") || script.absolutePath.contains("/temp/")) {
      val error = FileUtils.deleteFile("idesetupScript", script.absolutePath, true)
      if (error != null) {
        log.error(error.errorLogString)
      }
    }
  }
}
