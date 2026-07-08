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

import com.google.gson.JsonArray
import com.tom.rv2ide.lsp.api.describeSnippet
import com.tom.rv2ide.lsp.models.Command
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionItemKind
import com.tom.rv2ide.lsp.models.InsertTextFormat
import com.tom.rv2ide.lsp.models.MatchLevel
import org.slf4j.LoggerFactory

/** @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
class ClangCompletionConverter {

  companion object {
    private val log = LoggerFactory.getLogger(ClangCompletionConverter::class.java)
  }

  private val snippetTransformer = SnippetTransformer()

  fun convert(jsonItems: JsonArray, prefix: String = ""): List<CompletionItem> {
    return jsonItems.mapNotNull { element ->
      try {
        val item = element.asJsonObject

        val label = item.get("label")?.asString
        if (label == null) {
          return@mapNotNull null
        }

        val kind = item.get("kind")?.asInt ?: 1
        val detail = item.get("detail")?.asString ?: ""

        val textEdit = item.getAsJsonObject("textEdit")
        var insertText =
            if (textEdit != null && textEdit.has("newText")) {
              textEdit.get("newText")?.asString ?: label
            } else {
              item.get("insertText")?.asString ?: label
            }

        val insertTextFormatValue = item.get("insertTextFormat")?.asInt

        val isSnippet = insertTextFormatValue == 2
        val snippetMetadata =
            if (isSnippet && insertText != null) {
              prepareSnippetMetadata(insertText, detail, label)
            } else {
              null
            }
        insertText = snippetMetadata?.text ?: insertText

        val insertFormat =
            if (snippetMetadata != null) InsertTextFormat.SNIPPET else InsertTextFormat.PLAIN_TEXT
        val command = snippetMetadata?.command

        CompletionItem(
                ideLabel = label,
                detail = detail,
                insertText = insertText,
                insertTextFormat = insertFormat,
                sortText = item.get("sortText")?.asString ?: label,
                command = command,
                completionKind = convertKind(kind),
                matchLevel = MatchLevel.NO_MATCH,
                additionalTextEdits = null,
                data = null,
            )
            .apply {
              val docElement = item.get("documentation")
              if (docElement != null) {
                if (docElement.isJsonObject) {
                  val docObj = docElement.asJsonObject
                  docObj.get("value")?.asString?.let { doc -> this.desc = doc }
                } else if (docElement.isJsonPrimitive) {
                  docElement.asString?.let { doc -> this.desc = doc }
                }
              }

              snippetMetadata?.let {
                snippetDescription =
                    describeSnippet(prefix, allowCommandExecution = it.allowCommand)
              }
            }
      } catch (e: Exception) {
        ClangLogs.error("Error converting completion item: {}", e.message)
        e.printStackTrace()
        null
      }
    }
  }

  private fun extractParameterNamesFromDetail(detail: String, label: String): List<String> {
    var signature = detail

    if (!detail.contains("(") && label.contains("(")) {
      signature = label
    }

    return snippetTransformer.extractParameterNames(signature)
  }

  private data class SnippetMetadata(
      val text: String,
      val allowCommand: Boolean,
      val command: Command?,
  )

  private fun prepareSnippetMetadata(
      rawSnippet: String,
      detail: String,
      label: String,
  ): SnippetMetadata {
    val parameterNames = extractParameterNamesFromDetail(detail, label)
    var snippetText =
        if (parameterNames.isNotEmpty() && rawSnippet.contains("\${")) {
          snippetTransformer.transformSnippet(rawSnippet, parameterNames)
        } else {
          rawSnippet
        }

    snippetText = simplifyCallSnippet(snippetText) ?: snippetText
    snippetText = ensureTerminalTabStop(snippetText)

    val command = createSignatureCommand(snippetText)
    return SnippetMetadata(text = snippetText, allowCommand = command != null, command = command)
  }

  private fun simplifyCallSnippet(snippet: String): String? {
    val openIndex = snippet.indexOf('(')
    if (openIndex <= 0) return null
    if (snippet[openIndex - 1].isWhitespace()) return null

    val closeIndex = findMatchingParen(snippet, openIndex)
    if (closeIndex == -1) return null

    val inner = snippet.substring(openIndex + 1, closeIndex)
    val placeholderOnly =
        inner
            .replace("""\$\{(\d+)(:[^}]*)?\}""".toRegex(), "")
            .replace("""\$\d+""".toRegex(), "")
            .replace(",", "")
            .replace(" ", "")
            .replace("\t", "")
            .replace("\n", "")
            .isEmpty()

    if (!placeholderOnly) return null

    val afterParen = snippet.substring(closeIndex + 1)
    val cleanedAfterParen =
        afterParen.replaceFirst("""^\s*(\$\{0(?::[^}]*)?\}|\$0)""".toRegex(), "")

    return snippet.substring(0, openIndex + 1) + "\$0)" + cleanedAfterParen
  }

  private fun findMatchingParen(text: String, openIndex: Int): Int {
    var depth = 0
    for (i in openIndex until text.length) {
      val ch = text[i]
      if (ch == '(') {
        depth++
      } else if (ch == ')') {
        depth--
        if (depth == 0) return i
      }
    }
    return -1
  }

  private fun ensureTerminalTabStop(snippet: String): String {
    val hasTerminalTabStop = snippet.contains("\$0") || snippet.contains("\${0")
    return if (hasTerminalTabStop) snippet else snippet + "\$0"
  }

  private fun createSignatureCommand(snippetText: String): Command? {
    return if (snippetText.contains("(")) {
      Command("Trigger Parameter Hints", Command.TRIGGER_PARAMETER_HINTS)
    } else {
      null
    }
  }

  private fun convertKind(lspKind: Int): CompletionItemKind {
    return when (lspKind) {
      2 -> CompletionItemKind.METHOD
      3 -> CompletionItemKind.FUNCTION
      4 -> CompletionItemKind.CONSTRUCTOR
      5 -> CompletionItemKind.FIELD
      6 -> CompletionItemKind.VARIABLE
      7 -> CompletionItemKind.CLASS
      8 -> CompletionItemKind.INTERFACE
      9 -> CompletionItemKind.MODULE
      10 -> CompletionItemKind.PROPERTY
      12 -> CompletionItemKind.VALUE
      13 -> CompletionItemKind.ENUM
      14 -> CompletionItemKind.KEYWORD
      15 -> CompletionItemKind.SNIPPET
      21 -> CompletionItemKind.ENUM_MEMBER
      26 -> CompletionItemKind.TYPE_PARAMETER
      else -> CompletionItemKind.NONE
    }
  }
}
