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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.projects.IWorkspace
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class ClangWorkspaceSetup(private val context: Context, private val workspace: IWorkspace) {

  companion object {
    private val log = LoggerFactory.getLogger(ClangWorkspaceSetup::class.java)
  }

  fun setup(processManager: ClangServerProcessManager) {
    val workspaceRoot = workspace.getProjectDir().toURI().toString()
    ClangLogs.info("Setting up workspace with root: {}", workspaceRoot)

    processManager.startServer(null)

    val initParams = createInitParams(workspaceRoot)

    ClangLogs.info("Sending initialize request...")

    processManager.sendRequest("initialize", initParams) { result ->
      ClangLogs.info("Server initialized successfully")
      processManager.sendNotification("initialized", JsonObject())
    }
  }

  private fun createInitParams(workspaceRoot: String): JsonObject {
    return JsonObject().apply {
      addProperty("processId", android.os.Process.myPid())
      addProperty("rootUri", workspaceRoot)

      add(
          "capabilities",
          JsonObject().apply {
            add(
                "textDocument",
                JsonObject().apply {
                  add(
                      "completion",
                      JsonObject().apply {
                        add(
                            "completionItem",
                            JsonObject().apply {
                              addProperty("snippetSupport", true)
                              addProperty("commitCharactersSupport", true)
                              add(
                                  "documentationFormat",
                                  JsonArray().apply {
                                    add("plaintext")
                                    add("markdown")
                                  },
                              )
                              addProperty("deprecatedSupport", true)
                              addProperty("preselectSupport", true)
                            },
                        )
                        addProperty("contextSupport", true)
                      },
                  )
                  add(
                      "hover",
                      JsonObject().apply {
                        add(
                            "contentFormat",
                            JsonArray().apply {
                              add("plaintext")
                              add("markdown")
                            },
                        )
                      },
                  )
                  add(
                      "signatureHelp",
                      JsonObject().apply {
                        add(
                            "signatureInformation",
                            JsonObject().apply {
                              add(
                                  "documentationFormat",
                                  JsonArray().apply {
                                    add("plaintext")
                                    add("markdown")
                                  },
                              )
                            },
                        )
                      },
                  )
                  add("definition", JsonObject().apply { addProperty("linkSupport", true) })
                  add("references", JsonObject())
                  add("documentHighlight", JsonObject())
                  add("documentSymbol", JsonObject())
                  add("codeAction", JsonObject())
                  add("codeLens", JsonObject())
                  add("formatting", JsonObject())
                  add("rangeFormatting", JsonObject())
                  add("onTypeFormatting", JsonObject())
                  add("rename", JsonObject())
                  add(
                      "publishDiagnostics",
                      JsonObject().apply { addProperty("relatedInformation", true) },
                  )
                },
            )

            add(
                "workspace",
                JsonObject().apply {
                  addProperty("applyEdit", true)
                  add("workspaceEdit", JsonObject().apply { addProperty("documentChanges", true) })
                  add(
                      "didChangeConfiguration",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                  add(
                      "didChangeWatchedFiles",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                  add("symbol", JsonObject().apply { addProperty("dynamicRegistration", true) })
                  add(
                      "executeCommand",
                      JsonObject().apply { addProperty("dynamicRegistration", true) },
                  )
                },
            )
          },
      )

      add(
          "initializationOptions",
          JsonObject().apply {
            add(
                "fallbackFlags",
                JsonArray().apply { add("-std=c++17") },
            )
          },
      )
    }
  }
}
