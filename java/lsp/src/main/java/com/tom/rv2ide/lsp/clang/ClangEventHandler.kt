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
package com.tom.rv2ide.lsp.clang

/** @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
class ClangEventHandler(
    private val documentManager: ClangDocumentManager,
    private val languageServer: ClangLanguageServer,
) {

  private val lastChangeTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
  private val changeThrottleMs = 200L
  private val pendingOperations = java.util.concurrent.ConcurrentHashMap<String, PendingOperation>()

  private data class PendingOperation(
      val file: java.nio.file.Path,
      val text: String?,
      val type: OperationType,
  )

  private enum class OperationType {
    OPEN,
    CHANGE,
    CLOSE,
  }

  fun onServerInitialized() {
    ClangLogs.info("Server initialized, processing {} pending operations", pendingOperations.size)
    val operations = pendingOperations.values.toList()
    pendingOperations.clear()

    operations.forEach { op ->
      when (op.type) {
        OperationType.OPEN -> {
          ClangLogs.debug("Processing pending open for: {}", op.file)
          documentManager.ensureDocumentOpen(op.file, op.text)
        }
        OperationType.CLOSE -> {
          ClangLogs.debug("Processing pending close for: {}", op.file)
          documentManager.closeDocument(op.file)
        }
        OperationType.CHANGE -> {}
      }
    }
  }

  private fun isSupportedFile(file: java.nio.file.Path): Boolean {
    val fileName = file.toString().lowercase()
    return fileName.endsWith(".c") ||
        fileName.endsWith(".C") ||
        fileName.endsWith(".cpp") ||
        fileName.endsWith(".cc") ||
        fileName.endsWith(".cxx") ||
        fileName.endsWith(".h") ||
        fileName.endsWith(".H") ||
        fileName.endsWith(".hpp") ||
        fileName.endsWith(".hxx")
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onContentChange(event: com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent) {
    val file = event.changedFile
    if (!isSupportedFile(file)) return

    if (!languageServer.isInitialized()) {
      ClangLogs.debug("Server not initialized, ignoring change for: {}", file)
      return
    }

    val uri = file.toUri().toString()
    val currentTime = System.currentTimeMillis()
    val lastChange = lastChangeTime[uri] ?: 0L

    if (currentTime - lastChange < changeThrottleMs) {
      return
    }

    lastChangeTime[uri] = currentTime

    try {
      val content = event.newText ?: file.toFile().readText()

      if (content.isNotEmpty() && event.version > 0) {
        val currentVersion = documentManager.getDocumentVersion(uri)
        if (event.version > currentVersion) {
          documentManager.setDocumentVersion(uri, event.version)
          documentManager.notifyDocumentChange(file, content, event.version)
        }
      }
    } catch (e: Exception) {
      ClangLogs.error("Failed to handle document change", e)
    }
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileOpened(event: com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent) {
    val file = event.openedFile
    if (!isSupportedFile(file)) return

    ClangLogs.debug("Document open event for: {}", file)

    if (!languageServer.isInitialized()) {
      ClangLogs.warn("Server not initialized, queuing open operation for: {}", file)
      val uri = file.toUri().toString()
      pendingOperations[uri] = PendingOperation(file, event.text, OperationType.OPEN)
      return
    }

    try {
      documentManager.ensureDocumentOpen(file, event.text)
    } catch (e: Exception) {
      ClangLogs.error("Failed to open document", e)
    }
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileClosed(event: com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent) {
    val file = event.closedFile
    if (!isSupportedFile(file)) return

    ClangLogs.debug("Document close event for: {}", file)

    if (!languageServer.isInitialized()) {
      ClangLogs.warn("Server not initialized, queuing close operation for: {}", file)
      val uri = file.toUri().toString()
      pendingOperations[uri] = PendingOperation(file, null, OperationType.CLOSE)
      return
    }

    try {
      documentManager.closeDocument(file)
    } catch (e: Exception) {
      ClangLogs.error("Failed to close document", e)
    }
  }
}
