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

import android.content.Context
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.models.*
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.IWorkspace
import java.nio.file.Path
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/** @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
class ClangLanguageServer(private val context: Context) : ILanguageServer {

  companion object {
    const val SERVER_ID = "clang"
    private val log = LoggerFactory.getLogger(ClangLanguageServer::class.java)
  }

  private val processManager = ClangServerProcessManager(context)
  private val documentManager = ClangDocumentManager(processManager)
  private val requestHandler = ClangRequestHandler(processManager, documentManager)
  private val eventHandler: ClangEventHandler

  private var _client: ILanguageClient? = null
  private var initialized = false
  private var workspaceSetup: ClangWorkspaceSetup? = null

  private val completionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private var selectedFile: java.nio.file.Path? = null

  init {
    System.err.println("===== ClangLanguageServer CREATED =====")
    ClangLogs.error("ClangLanguageServer CONSTRUCTOR called")
    eventHandler = ClangEventHandler(documentManager, this)
    if (!org.greenrobot.eventbus.EventBus.getDefault().isRegistered(this)) {
      org.greenrobot.eventbus.EventBus.getDefault().register(this)
    }

    processManager.setDiagnosticsCallback { diagnostics ->
      _client?.publishDiagnostics(diagnostics)
    }
    ClangLogs.error("ClangLanguageServer initialization complete")
  }

  override val serverId: String = SERVER_ID
  override val client: ILanguageClient?
    get() = _client

  override fun connectClient(client: ILanguageClient?) {
    this._client = client
    ClangLogs.info("Connected language client: {}", client?.javaClass?.simpleName)
  }

  override fun applySettings(settings: IServerSettings?) {
    ClangLogs.debug("Applied settings: {}", settings)
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    ClangLogs.info("Setting up workspace: {}", workspace.getProjectDir())
    workspaceSetup = ClangWorkspaceSetup(context, workspace)
    workspaceSetup?.setup(processManager)
    initialized = true
    eventHandler.onServerInitialized()
    ClangLogs.info("Workspace setup complete, initialized={}", initialized)
  }

  fun isInitialized(): Boolean = initialized

  override fun complete(params: CompletionParams?): CompletionResult {
    System.err.println("!!!!! CLANG COMPLETE CALLED !!!!!")
    System.err.println("initialized = $initialized")
    System.err.println("params = $params")

    ClangLogs.error("********** CLANG COMPLETE METHOD CALLED **********")
    ClangLogs.error("Initialized: {}", initialized)
    ClangLogs.error("Params: {}", params)

    return if (initialized && params != null) {
      runBlocking {
        withTimeout(10000) {
          val result = async(Dispatchers.Default) { requestHandler.complete(params) }
          result.await()
        }
      }
    } else {
      ClangLogs.error("RETURNING EMPTY: initialized={}, params={}", initialized, params)
      CompletionResult(emptyList())
    }
  }

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    ClangLogs.info("findReferences called for: {}", params.file)
    return if (initialized) {
      requestHandler.findReferences(params)
    } else {
      ReferenceResult(emptyList())
    }
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    ClangLogs.info("findDefinition called for: {}", params.file)
    return if (initialized) {
      requestHandler.findDefinition(params)
    } else {
      DefinitionResult(emptyList())
    }
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    return params.selection
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    ClangLogs.info("signatureHelp called for: {}", params.file)
    return if (initialized) {
      requestHandler.signatureHelp(params)
    } else {
      SignatureHelp(emptyList(), 0, 0)
    }
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    return DiagnosticResult.NO_UPDATE
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    ClangLogs.info(
        "formatCode called - initialized: {}, selectedFile: {}, params: {}",
        initialized,
        selectedFile,
        params != null,
    )

    if (params == null) {
      ClangLogs.warn("Format params is null")
      return CodeFormatResult(false, mutableListOf())
    }

    if (!initialized) {
      ClangLogs.warn("Server not initialized")
      return CodeFormatResult(false, mutableListOf())
    }

    val fileToFormat = selectedFile
    if (fileToFormat == null) {
      ClangLogs.warn("No file selected for formatting")
      return CodeFormatResult(false, mutableListOf())
    }

    val fileName = fileToFormat.toString().lowercase()
    val isCFile =
        fileName.endsWith(".c") ||
            fileName.endsWith(".C") ||
            fileName.endsWith(".cpp") ||
            fileName.endsWith(".cc") ||
            fileName.endsWith(".cxx") ||
            fileName.endsWith(".h") ||
            fileName.endsWith(".H") ||
            fileName.endsWith(".hpp") ||
            fileName.endsWith(".hxx")

    if (!isCFile) {
      ClangLogs.debug("Not a C/C++ file: {}", fileToFormat)
      return CodeFormatResult(false, mutableListOf())
    }

    ClangLogs.info("Formatting file: {}", fileToFormat)

    return try {
      documentManager.ensureDocumentOpen(fileToFormat)

      if (params.content != null && params.content.toString().isNotEmpty()) {
        val uri = fileToFormat.toUri().toString()
        val currentVersion = documentManager.getDocumentVersion(uri)
        val newVersion = currentVersion + 1
        documentManager.setDocumentVersion(uri, newVersion)
        documentManager.notifyDocumentChange(fileToFormat, params.content.toString(), newVersion)

        Thread.sleep(100)
      }

      runBlocking { withTimeout(5000) { requestHandler.formatDocument(fileToFormat, params) } }
    } catch (e: Exception) {
      ClangLogs.error("Error during format", e)
      CodeFormatResult(false, mutableListOf())
    }
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    ClangLogs.error("LSP failure: type={}, error={}", failure?.type, failure?.error?.message)
    return false
  }

  override fun shutdown() {
    ClangLogs.error("=== SHUTDOWN CALLED ===")
    ClangLogs.error("Stack trace:")
    Thread.currentThread().stackTrace.take(10).forEach { ClangLogs.error("  at {}", it) }

    ClangLogs.info("Shutting down Clang Language Server...")
    completionScope.cancel()
    try {
      org.greenrobot.eventbus.EventBus.getDefault().unregister(eventHandler)
      if (org.greenrobot.eventbus.EventBus.getDefault().isRegistered(this)) {
        org.greenrobot.eventbus.EventBus.getDefault().unregister(this)
      }
    } catch (e: Exception) {
      ClangLogs.warn("Error unregistering from EventBus", e)
    }

    ClangLogs.error("About to call documentManager.clear()")
    documentManager.clear()

    ClangLogs.error("About to call processManager.shutdown()")
    processManager.shutdown()

    initialized = false
    ClangLogs.info("Clang Language Server shutdown complete")
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onContentChange(event: com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent) {
    val file = event.changedFile
    val fileName = file.toString().lowercase()
    val isCFile =
        fileName.endsWith(".c") ||
            fileName.endsWith(".C") ||
            fileName.endsWith(".cpp") ||
            fileName.endsWith(".cc") ||
            fileName.endsWith(".cxx") ||
            fileName.endsWith(".h") ||
            fileName.endsWith(".H") ||
            fileName.endsWith(".hpp") ||
            fileName.endsWith(".hxx")

    if (!isCFile) return

    selectedFile = file
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileOpened(event: com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent) {
    val file = event.openedFile
    val fileName = file.toString().lowercase()
    val isCFile =
        fileName.endsWith(".c") ||
            fileName.endsWith(".C") ||
            fileName.endsWith(".cpp") ||
            fileName.endsWith(".cc") ||
            fileName.endsWith(".cxx") ||
            fileName.endsWith(".h") ||
            fileName.endsWith(".H") ||
            fileName.endsWith(".hpp") ||
            fileName.endsWith(".hxx")

    if (!isCFile) return

    selectedFile = file
  }

  @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.ASYNC)
  fun onFileSelected(event: com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent) {
    ClangLogs.info("File selected: {}", event.selectedFile)
    selectedFile = event.selectedFile
  }
}
