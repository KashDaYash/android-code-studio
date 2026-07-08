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

import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.models.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/** @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
class ClangRequestHandler(
    private val processManager: ClangServerProcessManager,
    private val documentManager: ClangDocumentManager,
) {

  companion object {
    private val log = LoggerFactory.getLogger(ClangRequestHandler::class.java)
    private const val COMPLETION_TIMEOUT = 15000L
    private const val DEBOUNCE_DELAY = 100L
  }

  private val completionConverter = ClangCompletionConverter()
  private val lastCompletionRequest = AtomicLong(0)
  private val fileIndexedTime = ConcurrentHashMap<String, Long>()

  suspend fun complete(params: CompletionParams): CompletionResult = coroutineScope {
    val startTime = System.currentTimeMillis()
    ClangLogs.error("========================================")
    ClangLogs.error(" COMPLETION REQUEST START")
    ClangLogs.error("========================================")
    ClangLogs.error("File: {}", params.file)
    ClangLogs.error("Position: line={}, col={}", params.position.line, params.position.column)

    if (params.position.line < 0 || params.position.column < 0) {
      ClangLogs.error("ABORT: Invalid position")
      return@coroutineScope CompletionResult(emptyList())
    }

    val requestTimestamp = System.currentTimeMillis()
    lastCompletionRequest.set(requestTimestamp)

    delay(DEBOUNCE_DELAY)

    if (lastCompletionRequest.get() != requestTimestamp) {
      ClangLogs.error("ABORT: Request superseded")
      return@coroutineScope CompletionResult(emptyList())
    }

    val deferred = CompletableDeferred<CompletionResult>()

    try {
      val uri = params.file.toUri().toString()
      val fileContent = params.content?.toString() ?: ""
      val prefix = extractPrefix(fileContent, params.position)

      ClangLogs.error("Checking document state for URI: {}", uri)
      val isDocOpen = documentManager.isDocumentOpen(uri)
      ClangLogs.error("Document open status: {}", isDocOpen)

      val currentContent = params.content?.toString()

      if (!isDocOpen) {
        ClangLogs.error("Document not open, opening now...")
        withContext(Dispatchers.IO) {
          val content = currentContent ?: params.file.toFile().readText()
          documentManager.ensureDocumentOpen(params.file, content)
          fileIndexedTime[uri] = System.currentTimeMillis()
        }
        delay(2000)
      } else if (currentContent != null && currentContent.isNotEmpty()) {
        ClangLogs.error("Syncing content immediately for completion...")
        withContext(Dispatchers.IO) {
          val currentVersion = documentManager.getDocumentVersion(uri)
          val newVersion = currentVersion + 1
          documentManager.notifyDocumentChange(params.file, currentContent, newVersion)
          documentManager.setDocumentVersion(uri, newVersion)
        }

        val lastIndexed = fileIndexedTime[uri] ?: 0L
        val timeSinceIndexed = System.currentTimeMillis() - lastIndexed
        if (timeSinceIndexed < 3000) {
          delay(500)
        } else {
          delay(200)
        }
      }

      val lspParams =
          JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", uri) })
            add(
                "position",
                JsonObject().apply {
                  addProperty("line", params.position.line)
                  addProperty("character", params.position.column)
                },
            )
            add("context", JsonObject().apply { addProperty("triggerKind", 1) })
          }

      ClangLogs.error("Sending completion request to clangd...")
      ClangLogs.error("Request URI: {}", uri)
      ClangLogs.error(
          "Request position: line={}, character={}",
          params.position.line,
          params.position.column,
      )

      processManager.sendRequest("textDocument/completion", lspParams) { result ->
        try {
          ClangLogs.error("=== COMPLETION CALLBACK INVOKED ===")
          ClangLogs.error(
              "Deferred isActive: {}, isCompleted: {}, isCancelled: {}",
              deferred.isActive,
              deferred.isCompleted,
              deferred.isCancelled,
          )
          ClangLogs.error("Result null? {}", result == null)

          if (deferred.isCompleted || deferred.isCancelled) {
            ClangLogs.error("!!! DEFERRED ALREADY COMPLETED/CANCELLED - IGNORING CALLBACK !!!")
            return@sendRequest
          }

          val items = mutableListOf<CompletionItem>()

          if (result != null) {
            ClangLogs.error("Result keys: {}", result.keySet())
            ClangLogs.error("Has 'items'? {}", result.has("items"))

            if (result.has("items")) {
              val itemsArray = result.getAsJsonArray("items")
              ClangLogs.error("Items array size: {}", itemsArray.size())

              val clangdItems = completionConverter.convert(itemsArray, prefix)
              items.addAll(clangdItems)
              ClangLogs.error("Converted {} clangd items", clangdItems.size)
            } else {
              if (result.has("isIncomplete") && result.has("items")) {
                val itemsArray = result.getAsJsonArray("items")
                ClangLogs.error("Alternative format - Items array size: {}", itemsArray.size())

                val clangdItems = completionConverter.convert(itemsArray, prefix)
                items.addAll(clangdItems)
                ClangLogs.error(
                    "Converted {} clangd items from alternative format",
                    clangdItems.size,
                )
              } else {
                ClangLogs.error(
                    "No 'items' key in result. Full result: {}",
                    result.toString().take(500),
                )
              }
            }
          } else {
            ClangLogs.error("Result is NULL - clangd returned error or no completions")
          }

          ClangLogs.error("Total completion items: {}", items.size)
          ClangLogs.error("About to call deferred.complete()...")

          val completed = deferred.complete(CompletionResult(items))
          ClangLogs.error("deferred.complete() returned: {}", completed)
        } catch (e: Exception) {
          ClangLogs.error("Error in completion callback: {}", e.message)
          e.printStackTrace()
          if (!deferred.isCompleted) {
            deferred.completeExceptionally(e)
          }
        }
      }

      val result =
          withTimeoutOrNull(COMPLETION_TIMEOUT) {
            ClangLogs.error("Waiting for deferred to complete...")
            deferred.await()
          }

      if (result == null) {
        ClangLogs.error(
            "COMPLETION TIMEOUT - deferred state: active={}, completed={}, cancelled={}",
            deferred.isActive,
            deferred.isCompleted,
            deferred.isCancelled,
        )
        return@coroutineScope CompletionResult(emptyList())
      }

      val elapsed = System.currentTimeMillis() - startTime
      ClangLogs.error("Completion succeeded with {} items in {}ms", result.items.size, elapsed)
      return@coroutineScope result
    } catch (e: Exception) {
      ClangLogs.error("Exception in complete method: {}", e.message)
      ClangLogs.error("Exception type: {}", e.javaClass.name)
      e.printStackTrace()
      return@coroutineScope CompletionResult(emptyList())
    }
  }

  private fun extractPrefix(content: String, position: com.tom.rv2ide.models.Position): String {
    val lines = content.split("\n")
    if (position.line < 0 || position.line >= lines.size) return ""

    val line = lines[position.line]
    val col = position.column.coerceAtMost(line.length)

    var start = col
    while (
        start > 0 &&
            (line[start - 1].isLetterOrDigit() || line[start - 1] == '_' || line[start - 1] == ':')
    ) {
      start--
    }

    return line.substring(start, col)
  }

  suspend fun findReferences(params: ReferenceParams): ReferenceResult =
      withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<ReferenceResult>()

        documentManager.ensureDocumentOpen(params.file)

        val lspParams =
            JsonObject().apply {
              add(
                  "textDocument",
                  JsonObject().apply { addProperty("uri", params.file.toUri().toString()) },
              )
              add(
                  "position",
                  JsonObject().apply {
                    addProperty("line", params.position.line)
                    addProperty("character", params.position.column)
                  },
              )
              add(
                  "context",
                  JsonObject().apply {
                    addProperty("includeDeclaration", params.includeDeclaration)
                  },
              )
            }

        processManager.sendRequest("textDocument/references", lspParams) { result ->
          val locations = convertToLocations(result)
          deferred.complete(ReferenceResult(locations))
        }

        withTimeoutOrNull(5000) { deferred.await() } ?: ReferenceResult(emptyList())
      }

  suspend fun findDefinition(params: DefinitionParams): DefinitionResult =
      withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<DefinitionResult>()

        documentManager.ensureDocumentOpen(params.file)

        val lspParams =
            JsonObject().apply {
              add(
                  "textDocument",
                  JsonObject().apply { addProperty("uri", params.file.toUri().toString()) },
              )
              add(
                  "position",
                  JsonObject().apply {
                    addProperty("line", params.position.line)
                    addProperty("character", params.position.column)
                  },
              )
            }

        processManager.sendRequest("textDocument/definition", lspParams) { result ->
          val locations = convertToLocations(result)
          deferred.complete(DefinitionResult(locations))
        }

        withTimeoutOrNull(5000) { deferred.await() } ?: DefinitionResult(emptyList())
      }

  suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp =
      withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<SignatureHelp>()

        try {
          documentManager.ensureDocumentOpen(params.file)

          val uri = params.file.toUri().toString()

          // Sync content if provided
          if (params.content != null && params.content!!.isNotEmpty()) {
            val currentVersion = documentManager.getDocumentVersion(uri)
            val newVersion = currentVersion + 1
            documentManager.setDocumentVersion(uri, newVersion)
            documentManager.notifyDocumentChange(params.file, params.content.toString(), newVersion)
            // Small delay to let clangd process the change
            delay(100)
          }

          // Build context with trigger information
          val context =
              JsonObject().apply {
                addProperty("triggerKind", 2) // 2 = TriggerCharacter, 1 = Invoked
                addProperty("isRetrigger", false)

                // Detect trigger character from content
                if (params.content != null) {
                  val content = params.content.toString()
                  val lines = content.split("\n")
                  if (params.position.line >= 0 && params.position.line < lines.size) {
                    val currentLine = lines[params.position.line]
                    val pos = params.position.column

                    if (pos > 0 && pos <= currentLine.length) {
                      val triggerChar = currentLine[pos - 1]
                      if (triggerChar == '(' || triggerChar == ',') {
                        addProperty("triggerCharacter", triggerChar.toString())
                        ClangLogs.debug("Signature help triggered by: '{}'", triggerChar)
                      }
                    }
                  }
                }
              }

          val lspParams =
              JsonObject().apply {
                add(
                    "textDocument",
                    JsonObject().apply { addProperty("uri", params.file.toUri().toString()) },
                )
                add(
                    "position",
                    JsonObject().apply {
                      addProperty("line", params.position.line)
                      addProperty("character", params.position.column)
                    },
                )
                add("context", context)
              }

          ClangLogs.debug(
              "Requesting signature help at {}:{}",
              params.position.line,
              params.position.column,
          )

          processManager.sendRequest("textDocument/signatureHelp", lspParams) { result ->
            val help = convertToSignatureHelp(result)
            ClangLogs.debug("Received {} signature(s)", help.signatures.size)
            deferred.complete(help)
          }

          withTimeoutOrNull(3000) { deferred.await() }
              ?: run {
                ClangLogs.warn("Signature help request timed out")
                SignatureHelp(emptyList(), 0, 0)
              }
        } catch (e: Exception) {
          ClangLogs.error("Error requesting signature help", e)
          deferred.complete(SignatureHelp(emptyList(), 0, 0))
          SignatureHelp(emptyList(), 0, 0)
        }
      }

  suspend fun formatDocument(filePath: Path, params: FormatCodeParams): CodeFormatResult =
      withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<CodeFormatResult>()

        ClangLogs.info("Format request received")
        ClangLogs.info("File path: {}", filePath)

        val fileContent = params.content?.toString()

        if (fileContent == null) {
          ClangLogs.error("No content provided for formatting")
          return@withContext CodeFormatResult(false, mutableListOf())
        }

        val uri = filePath.toUri().toString()
        ClangLogs.info("Document URI: {}", uri)

        if (!documentManager.isDocumentOpen(uri)) {
          ClangLogs.info("Document not open, opening for formatting")
          documentManager.ensureDocumentOpen(filePath, fileContent)
          delay(500)
        } else {
          ClangLogs.info("Document already open, syncing content")
          val currentVersion = documentManager.getDocumentVersion(uri)
          val newVersion = currentVersion + 1
          documentManager.notifyDocumentChange(filePath, fileContent, newVersion)
          documentManager.setDocumentVersion(uri, newVersion)
          delay(300)
        }

        val lspParams =
            JsonObject().apply {
              add(
                  "textDocument",
                  JsonObject().apply { addProperty("uri", uri) },
              )
              add(
                  "options",
                  JsonObject().apply {
                    addProperty("tabSize", 4)
                    addProperty("insertSpaces", true)
                  },
              )
            }

        ClangLogs.info("Sending formatting request to clangd")

        processManager.sendRequest("textDocument/formatting", lspParams) { result ->
          try {
            ClangLogs.info("Formatting response received")

            if (result == null) {
              ClangLogs.warn("Received null result from clangd")
              deferred.complete(CodeFormatResult(false, mutableListOf()))
              return@sendRequest
            }

            val editsArray =
                when {
                  result.isJsonArray -> {
                    ClangLogs.info("Result is directly a JsonArray")
                    result.asJsonArray
                  }
                  result.has("items") -> {
                    val itemsField = result.get("items")
                    when {
                      itemsField.isJsonArray -> {
                        ClangLogs.info("Result has 'items' field with JsonArray")
                        itemsField.asJsonArray
                      }
                      itemsField.isJsonNull -> {
                        ClangLogs.info("Items field is null, no formatting needed")
                        deferred.complete(CodeFormatResult(false, mutableListOf()))
                        return@sendRequest
                      }
                      else -> {
                        ClangLogs.warn("Items field is not an array: {}", itemsField.toString())
                        deferred.complete(CodeFormatResult(false, mutableListOf()))
                        return@sendRequest
                      }
                    }
                  }
                  result.has("result") -> {
                    val resultField = result.get("result")
                    when {
                      resultField.isJsonArray -> {
                        ClangLogs.info("Result has 'result' field with JsonArray")
                        resultField.asJsonArray
                      }
                      resultField.isJsonNull -> {
                        ClangLogs.info("Result field is null, no formatting needed")
                        deferred.complete(CodeFormatResult(false, mutableListOf()))
                        return@sendRequest
                      }
                      else -> {
                        ClangLogs.warn("Result field is not an array: {}", resultField.toString())
                        deferred.complete(CodeFormatResult(false, mutableListOf()))
                        return@sendRequest
                      }
                    }
                  }
                  else -> {
                    ClangLogs.warn(
                        "Result has no 'items' or 'result' field, keys: {}",
                        result.keySet(),
                    )
                    deferred.complete(CodeFormatResult(false, mutableListOf()))
                    return@sendRequest
                  }
                }

            if (editsArray.size() == 0) {
              ClangLogs.info("No formatting changes needed")
              deferred.complete(CodeFormatResult(false, mutableListOf()))
              return@sendRequest
            }

            ClangLogs.info("Received {} formatting edits", editsArray.size())

            val lines = fileContent.split("\n")
            val indexedEdits = mutableListOf<IndexedTextEdit>()

            editsArray.forEach { element ->
              val edit = element.asJsonObject
              val range = edit.getAsJsonObject("range")
              val start = range.getAsJsonObject("start")
              val end = range.getAsJsonObject("end")
              val newText = edit.get("newText")?.asString ?: ""

              val startLine = start.get("line").asInt
              val startChar = start.get("character").asInt
              val endLine = end.get("line").asInt
              val endChar = end.get("character").asInt

              val startOffset = lineColumnToOffset(lines, startLine, startChar)
              val endOffset = lineColumnToOffset(lines, endLine, endChar)

              val indexedEdit = IndexedTextEdit()
              indexedEdit.newText = newText
              indexedEdit.start = startOffset
              indexedEdit.end = endOffset
              indexedEdits.add(indexedEdit)
            }

            val success = indexedEdits.isNotEmpty()
            ClangLogs.info("Formatting successful with {} indexed edits", indexedEdits.size)

            val formatResult = CodeFormatResult(success)
            indexedEdits.forEach { formatResult.indexedTextEdits.add(it) }

            deferred.complete(formatResult)
          } catch (e: Exception) {
            ClangLogs.error("Error processing formatting result", e)
            deferred.complete(CodeFormatResult(false, mutableListOf()))
          }
        }

        withTimeoutOrNull(5000) { deferred.await() } ?: CodeFormatResult(false, mutableListOf())
      }

  private fun lineColumnToOffset(lines: List<String>, line: Int, column: Int): Int {
    var offset = 0

    for (i in 0 until minOf(line, lines.size)) {
      offset += lines[i].length + 1
    }

    if (line < lines.size) {
      offset += minOf(column, lines[line].length)
    } else {
      offset += column
    }

    return offset
  }

  private fun convertToLocations(result: JsonObject?): List<com.tom.rv2ide.models.Location> {
    return result?.asJsonArray?.map { element ->
      val loc = element.asJsonObject
      val range = loc.getAsJsonObject("range")
      val start = range.getAsJsonObject("start")
      val end = range.getAsJsonObject("end")

      com.tom.rv2ide.models.Location(
          file = Paths.get(java.net.URI(loc.get("uri").asString)),
          range =
              com.tom.rv2ide.models.Range(
                  start =
                      com.tom.rv2ide.models.Position(
                          start.get("line").asInt,
                          start.get("character").asInt,
                      ),
                  end =
                      com.tom.rv2ide.models.Position(
                          end.get("line").asInt,
                          end.get("character").asInt,
                      ),
              ),
      )
    } ?: emptyList()
  }

  private fun convertToSignatureHelp(result: JsonObject?): SignatureHelp {
    if (result == null) {
      ClangLogs.debug("Signature help result is null")
      return SignatureHelp(emptyList(), 0, 0)
    }

    try {
      val signatures =
          result.getAsJsonArray("signatures")?.mapNotNull { element ->
            try {
              val sig = element.asJsonObject
              val label = sig.get("label")?.asString ?: return@mapNotNull null

              // Handle documentation (can be string or MarkupContent object)
              val documentation =
                  when {
                    sig.has("documentation") -> {
                      val doc = sig.get("documentation")
                      when {
                        doc.isJsonObject -> {
                          val docObj = doc.asJsonObject
                          MarkupContent(
                              docObj.get("value")?.asString ?: "",
                              if (docObj.get("kind")?.asString == "markdown") MarkupKind.MARKDOWN
                              else MarkupKind.PLAIN,
                          )
                        }
                        doc.isJsonPrimitive -> {
                          MarkupContent(doc.asString, MarkupKind.PLAIN)
                        }
                        else -> MarkupContent("", MarkupKind.PLAIN)
                      }
                    }
                    else -> MarkupContent("", MarkupKind.PLAIN)
                  }

              // Parse parameters
              val parameters =
                  sig.getAsJsonArray("parameters")?.mapNotNull { paramElement ->
                    try {
                      val param = paramElement.asJsonObject
                      val paramLabel = param.get("label")?.asString ?: return@mapNotNull null

                      // Handle parameter documentation
                      val paramDoc =
                          when {
                            param.has("documentation") -> {
                              val doc = param.get("documentation")
                              when {
                                doc.isJsonObject -> {
                                  val docObj = doc.asJsonObject
                                  MarkupContent(
                                      docObj.get("value")?.asString ?: "",
                                      if (docObj.get("kind")?.asString == "markdown")
                                          MarkupKind.MARKDOWN
                                      else MarkupKind.PLAIN,
                                  )
                                }
                                doc.isJsonPrimitive -> MarkupContent(doc.asString, MarkupKind.PLAIN)
                                else -> MarkupContent("", MarkupKind.PLAIN)
                              }
                            }
                            else -> MarkupContent("", MarkupKind.PLAIN)
                          }

                      ParameterInformation(label = paramLabel, documentation = paramDoc)
                    } catch (e: Exception) {
                      ClangLogs.warn("Failed to parse parameter: {}", e.message)
                      null
                    }
                  } ?: emptyList()

              SignatureInformation(
                  label = label,
                  documentation = documentation,
                  parameters = parameters,
              )
            } catch (e: Exception) {
              ClangLogs.warn("Failed to parse signature: {}", e.message)
              null
            }
          } ?: emptyList()

      val activeSignature = result.get("activeSignature")?.asInt ?: 0
      val activeParameter = result.get("activeParameter")?.asInt ?: 0

      ClangLogs.debug(
          "Converted signature help: {} signatures, active: {}/{}",
          signatures.size,
          activeSignature,
          activeParameter,
      )

      return SignatureHelp(signatures, activeSignature, activeParameter)
    } catch (e: Exception) {
      ClangLogs.error("Error converting signature help", e)
      return SignatureHelp(emptyList(), 0, 0)
    }
  }
}
