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

import org.slf4j.LoggerFactory

/** @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
class SnippetTransformer {

  companion object {
    private val log = LoggerFactory.getLogger(SnippetTransformer::class.java)

    private val SNIPPET_PLACEHOLDER_REGEX = """\$\{(\d+):([^}]+)\}""".toRegex()
  }

  fun transformSnippet(insertText: String, parameterNames: List<String>?): String {
    return SNIPPET_PLACEHOLDER_REGEX.replace(insertText) { matchResult ->
      val tabstop = matchResult.groupValues[1]
      val placeholder = matchResult.groupValues[2]

      val replacement =
          when {
            isBlockPlaceholder(placeholder) -> "\n"
            parameterNames.isNullOrEmpty() -> placeholder
            else -> {
              val index = tabstop.toIntOrNull()?.minus(1) ?: -1
              if (index in parameterNames.indices) parameterNames[index] else placeholder
            }
          }

      "\${$tabstop:${escapePlaceholder(replacement)}}"
    }
  }

  private fun escapePlaceholder(text: String): String {
    return text.replace("\\", "\\\\").replace("$", "\\$").replace("}", "\\}")
  }

  private fun isBlockPlaceholder(placeholder: String): Boolean {
    return placeholder.matches(Regex("(block|lambda|action|init|body|builder)"))
  }

  fun extractParameterNames(signature: String): List<String> {
    val paramNames = mutableListOf<String>()

    val paramsMatch = """\(([^)]*)\)""".toRegex().find(signature)
    val paramsContent = paramsMatch?.groupValues?.get(1) ?: return emptyList()

    if (paramsContent.isBlank()) return emptyList()

    paramsContent.split(",").forEach { param ->
      val trimmed = param.trim()
      val paramName = trimmed.substringBefore(":").trim()
      if (paramName.isNotEmpty()) {
        paramNames.add(paramName)
      }
    }

    return paramNames
  }
}
