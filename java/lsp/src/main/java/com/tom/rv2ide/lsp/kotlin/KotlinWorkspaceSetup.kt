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

package com.tom.rv2ide.lsp.kotlin

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.lsp.kotlin.compiler.KotlinCompilerService
import com.tom.rv2ide.lsp.kotlin.etc.LspFeatures
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projectdata.state.lsp.Index
import com.tom.rv2ide.projectdata.logs.LogStream
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class KotlinWorkspaceSetup(private val context: Context, private val workspace: IWorkspace) {

  companion object {
    private val log = LoggerFactory.getLogger(KotlinWorkspaceSetup::class.java)
  }

  private var compilerService: KotlinCompilerService? = null
  private val classpathProvider = KotlinClasspathProvider()

  // Use project directory path for cache identification
  private val indexCache = KotlinIndexCache(workspace.getProjectDir().absolutePath)

  private var buildWatcher: WatchService? = null
  private var watcherJob: Job? = null
  private val watchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private fun sendScriptConfiguration(processManager: KotlinServerProcessManager) {
    KslLogs.info("Sending script configuration...")

    val scriptConfigParams =
        JsonObject().apply {
          add(
              "settings",
              JsonObject().apply {
                add(
                    "kotlin",
                    JsonObject().apply {
                      add(
                          "scripts",
                          JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("buildScriptsEnabled", true)

                            // Add script templates
                            add(
                                "templates",
                                JsonArray().apply {
                                  add("kotlin.script.templates.standard.ScriptTemplateWithArgs")
                                },
                            )

                            // Add script classpath (same as regular Kotlin)
                            val classpathList = classpathProvider.getClasspathList()
                            add(
                                "classpath",
                                JsonArray().apply { classpathList.forEach { path -> add(path) } },
                            )
                          },
                      )
                    },
                )
              },
          )
        }

    processManager.sendNotification("workspace/didChangeConfiguration", scriptConfigParams)
    KslLogs.info(
        "Script configuration sent with {} classpath entries",
        classpathProvider.getClasspathList().size,
    )
  }

  fun setup(processManager: KotlinServerProcessManager) {
    val workspaceRoot = workspace.getProjectDir().toURI().toString()
    KslLogs.info("Setting up workspace with root: {}", workspaceRoot)
    Index.setIsIndexing(true)
    LogStream.emitLineBlocking("Setting up workspace...")

    LspFeatures.setProcessManager(processManager)
    initializeCompilerService()
    classpathProvider.initialize(compilerService)

    startBuildWatcher(processManager)

    val currentClasspath = classpathProvider.getClasspathList()
    val currentHash = indexCache.computeClasspathHash(currentClasspath)
    val cacheValid = indexCache.isCacheValid(currentHash)

    KslLogs.info("Cache status: {}", if (cacheValid) "VALID" else "INVALID/MISSING")
    KslLogs.info(indexCache.getCacheStats())

    processManager.startServer(classpathProvider)

    val initParams = createInitParams(workspaceRoot)

    KslLogs.info("Sending initialize request...")

    processManager.sendRequest("initialize", initParams) { result ->
      KslLogs.info("Server initialized successfully")
      processManager.sendNotification("initialized", JsonObject())

      // *** FIX: Send script-specific configuration ***
      sendScriptConfiguration(processManager)

      if (cacheValid) {
        restoreCachedIndex(processManager)
      } else {
        triggerIndexing(processManager, workspaceRoot, currentHash)
      }
    }
  }

  private fun startBuildWatcher(processManager: KotlinServerProcessManager) {
    try {
      buildWatcher = FileSystems.getDefault().newWatchService()

      // Watch all Android module build directories
      val modulesToWatch = mutableListOf<File>()
      workspace.getSubProjects().filterIsInstance<AndroidModule>().forEach { module ->
        val buildDir = File(module.path, "build")
        if (buildDir.exists()) {
          modulesToWatch.add(buildDir)
        }
      }

      if (modulesToWatch.isEmpty()) {
        KslLogs.warn("No build directories found to watch")
        return
      }

      // Register directories to watch
      val watchKeys = mutableMapOf<WatchKey, File>()
      modulesToWatch.forEach { buildDir ->
        try {
          val generatedDir = File(buildDir, "generated")
          if (generatedDir.exists()) {
            val key =
                generatedDir
                    .toPath()
                    .register(
                        buildWatcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE,
                    )
            watchKeys[key] = generatedDir
            KslLogs.info("Watching for build changes: {}", generatedDir.absolutePath)
          }
        } catch (e: Exception) {
          KslLogs.warn("Failed to watch directory: {}", buildDir.absolutePath, e)
        }
      }

      // Start watcher coroutine
      watcherJob =
          watchScope.launch {
            var lastReloadTime = 250L
            val reloadDebounceMs = 5000L // Wait 5 seconds after last change

            while (isActive) {
              try {
                val key = buildWatcher?.poll(1, TimeUnit.SECONDS) ?: continue

                val events = key.pollEvents()
                if (events.isNotEmpty()) {
                  val now = System.currentTimeMillis()

                  // Log the changes
                  events.forEach { event ->
                    val kind = event.kind()
                    val filename = event.context()
                    KslLogs.debug("Build change detected: {} - {}", kind.name(), filename)
                  }

                  // Debounce: only reload after changes stop for 5 seconds
                  if (now - lastReloadTime > reloadDebounceMs) {
                    delay(reloadDebounceMs)

                    // Double check no new changes came in during delay
                    val checkKey = buildWatcher?.poll(100, TimeUnit.MILLISECONDS)
                    if (checkKey == null) {
                      // No new changes, safe to reload
                      KslLogs.info("Build changes detected, reloading classpath and index...")
                      reloadClasspathAndIndex(processManager)
                      lastReloadTime = System.currentTimeMillis()
                    } else {
                      // New changes came in, reset timer
                      checkKey.reset()
                    }
                  }
                }

                key.reset()
              } catch (e: Exception) {
                if (e is CancellationException) break
                KslLogs.warn("Error in build watcher", e)
              }
            }
          }

      KslLogs.info("Build watcher started successfully")
    } catch (e: Exception) {
      KslLogs.error("Failed to start build watcher", e)
    }
  }

  private suspend fun reloadClasspathAndIndex(processManager: KotlinServerProcessManager) {
    withContext(Dispatchers.IO) {
      try {
        Index.setIsIndexing(true)  // Set flag when reload starts
        KslLogs.info("=== RELOADING CLASSPATH AND INDEX ===")

        // Invalidate classpath cache
        classpathProvider.invalidateCache()

        // Recreate classpath script with new paths
        // createKlsClasspathScript()

        // Clear index cache
        indexCache.clearCache()

        // Compute new classpath hash
        val currentClasspath = classpathProvider.getClasspathList()
        val currentHash = indexCache.computeClasspathHash(currentClasspath)

        // Trigger reindexing (this will also manage the Index flag)
        val workspaceRoot = workspace.getProjectDir().toURI().toString()
        triggerIndexing(processManager, workspaceRoot, currentHash)

        KslLogs.info("Classpath and index reloaded successfully")
      } catch (e: Exception) {
        KslLogs.error("Failed to reload classpath and index", e)
        Index.setIsIndexing(false)  // Reset flag on error
      }
    }
  }

  private fun restoreCachedIndex(processManager: KotlinServerProcessManager) {
    KslLogs.info("Restoring index from cache...")
    Index.setIsIndexing(true)  // Set indexing flag when starting cache restoration

    val cachedSymbols = indexCache.loadCache()
    if (cachedSymbols != null && cachedSymbols.size() > 0) {
      // Send cached configuration
      val configParams =
          JsonObject().apply {
            add(
                "settings",
                JsonObject().apply {
                  // add("kotlin", JsonObject().apply {
                  // addProperty("indexing", "cached")
                  // add("completion", JsonObject().apply {
                  // add("snippets", JsonObject().apply {
                  // addProperty("enabled", true)
                  // })
                  // })
                  // })
                },
            )
          }

      processManager.sendNotification("workspace/didChangeConfiguration", configParams)
      KslLogs.info("Cache restored with {} symbols - indexing skipped", cachedSymbols.size())
      Index.setIsIndexing(false)  // Reset flag after cache restoration
    } else {
      // Cache load failed, trigger fresh indexing
      // Index flag will be managed by triggerIndexing
      val currentClasspath = classpathProvider.getClasspathList()
      val currentHash = indexCache.computeClasspathHash(currentClasspath)
      triggerIndexing(processManager, workspace.getProjectDir().toURI().toString(), currentHash)
    }
  }

  private fun triggerIndexing(
      processManager: KotlinServerProcessManager,
      workspaceRoot: String,
      classpathHash: String,
  ) {
    KslLogs.info("Triggering classpath indexing...")
    Index.setIsIndexing(true)  // Set indexing flag when starting

    val configParams =
        JsonObject().apply {
          add(
              "settings",
              JsonObject().apply {
                // add("kotlin", JsonObject().apply {
                // addProperty("indexing", "enable")
                // add("completion", JsonObject().apply {
                // add("snippets", JsonObject().apply {
                // addProperty("enabled", true)
                // })
                // })
                // })
              },
          )
        }

    processManager.sendNotification("workspace/didChangeConfiguration", configParams)

    // Request symbols to warm up and cache the index
    val symbolParams = JsonObject().apply { addProperty("query", "") }

    processManager.sendRequest("workspace/symbol", symbolParams) { result ->
      try {
        val symbols = result?.getAsJsonArray("symbols") ?: JsonArray()
        val symbolCount = symbols.size()
        KslLogs.info("Indexing complete, found {} symbols", symbolCount)

        // Save to cache
        if (symbolCount > 0) {
          indexCache.saveCache(symbols, classpathHash)
        }
      } finally {
        // Always reset the flag when indexing completes (success or failure)
        Index.setIsIndexing(false)
      }
    }
  }

  fun cleanup() {
    try {
      // Stop build watcher
      watcherJob?.cancel()
      buildWatcher?.close()
      watchScope.cancel()

      compilerService?.destroy()
      KotlinCompilerProvider.getInstance().destroy()
      com.tom.rv2ide.lsp.kotlin.compiler.KotlinSourceFileManager.clearCache()
    } catch (e: Exception) {
      KslLogs.warn("Error cleaning up compiler service", e)
    }
  }

  // Add method to get cache instance for manual operations
  fun getIndexCache(): KotlinIndexCache = indexCache

  // Add method to manually trigger reload
  fun manualReloadClasspath(processManager: KotlinServerProcessManager) {
    watchScope.launch { reloadClasspathAndIndex(processManager) }
  }

  private fun initializeCompilerService() {
    try {
      val mainModule = findMainAndroidModule()
      if (mainModule != null) {
        compilerService = KotlinCompilerProvider.get(mainModule)
        KslLogs.info("Initialized compiler service for: {}", mainModule.path)
        Index.setIsIndexing(true)
        LogStream.emitLineBlocking("Initialized compiler service for: ${mainModule.path}")
      } else {
        KslLogs.warn("No Android module found, using default compiler")
        compilerService = KotlinCompilerService.NO_MODULE_COMPILER
      }
    } catch (e: Exception) {
      KslLogs.error("Failed to initialize compiler service", e)
      compilerService = KotlinCompilerService.NO_MODULE_COMPILER
    }
  }

  private fun findMainAndroidModule(): ModuleProject? {
    val subProjects = workspace.getSubProjects()

    for (subProject in subProjects) {
      if (subProject is AndroidModule && subProject.isApplication) {
        return subProject
      }
    }

    for (subProject in subProjects) {
      if (subProject is AndroidModule) {
        return subProject
      }
    }

    return null
  }

  private fun createInitParams(workspaceRoot: String): JsonObject {
    KslLogs.info("=== CREATING INIT PARAMS ===")

    val params =
        JsonObject().apply {
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
                                        add("markdown")
                                        add("plaintext")
                                      },
                                  )
                                  addProperty("deprecatedSupport", true)
                                  addProperty("preselectSupport", true)

                                  add(
                                      "resolveSupport",
                                      JsonObject().apply {
                                        add(
                                            "properties",
                                            JsonArray().apply {
                                              add("documentation")
                                              add("detail")
                                              add("additionalTextEdits")
                                            },
                                        )
                                      },
                                  )
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
                                  add("markdown")
                                  add("plaintext")
                                },
                            )
                          },
                      )
                      add("definition", JsonObject().apply { addProperty("linkSupport", true) })
                      add("references", JsonObject())
                      add("signatureHelp", JsonObject())
                    },
                )

                add(
                    "workspace",
                    JsonObject().apply {
                      addProperty("applyEdit", true)
                      add(
                          "workspaceEdit",
                          JsonObject().apply { addProperty("documentChanges", true) },
                      )
                      add(
                          "didChangeConfiguration",
                          JsonObject().apply { addProperty("dynamicRegistration", true) },
                      )
                      add("symbol", JsonObject().apply { addProperty("dynamicRegistration", true) })
                    },
                )
              },
          )

          compilerService?.let { service ->
            val allClassPaths = service.getFileManager().getAllClassPaths()
            val classpathArray = JsonArray()

            allClassPaths.forEach { file -> classpathArray.add(file.absolutePath) }

            val initOptions =
                JsonObject().apply {
                  addProperty("storagePath", workspace.getProjectDir().resolve(".acside").absolutePath)

                  addProperty("indexing", "auto")
                  addProperty("externalSources", "auto")

                  add(
                      "completion",
                      JsonObject().apply {
                        add("snippets", JsonObject().apply { addProperty("enabled", true) })
                      },
                  )

                  add(
                      "scripts",
                      JsonObject().apply {
                        addProperty("enabled", true)
                        // Define script definition templates
                        add(
                            "templates",
                            JsonArray().apply {
                              add("kotlin.script.templates.standard.ScriptTemplateWithArgs")
                            },
                        )
                      },
                  )

                  // Classpath settings
                  addProperty("usePredefinedClasspath", true)
                  addProperty("disableDependencyResolution", true)
                  add("classpath", classpathArray)
                }

            add("initializationOptions", initOptions)

            KslLogs.info("Configured KLS with {} classpath entries", allClassPaths.size)
          }
        }

    KslLogs.info("Full init params created with script support and formatting")
    return params
  }
}