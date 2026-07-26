package com.opencode.android.runtime.local

import com.opencode.android.core.api.OpenCodeFileContent
import com.opencode.android.core.api.OpenCodeFileNode
import com.opencode.android.core.api.OpenCodeSearchMatch
import com.opencode.android.core.api.OpenCodeSearchSubmatch
import com.opencode.android.core.api.OpenCodeSearchText
import java.io.File

/**
 * File access for the Claude Code runtime.
 *
 * OpenCode answers the explorer's file questions over HTTP; Claude Code has no such server. It does
 * not need one: `/workspace` inside the sandbox is a plain directory on the device, so these read it
 * directly. Without this the explorer throws "unsupported" the moment a Claude session is open.
 */
class ClaudeWorkspaceFiles(private val workspaceHostDir: File) {
    fun list(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> {
        val root = resolveRoot(directory)
        val target = resolve(root, path) ?: return emptyList()
        val children = target.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        return children.map { child ->
            OpenCodeFileNode(
                name = child.name,
                path = child.relativeTo(root).path,
                absolute = sandboxPath(directory, child.relativeTo(root).path),
                type = if (child.isDirectory) "directory" else "file",
                ignored = child.name.startsWith("."),
            )
        }
    }

    fun read(
        directory: String,
        path: String,
    ): OpenCodeFileContent {
        val root = resolveRoot(directory)
        val file = resolve(root, path)
        require(file != null && file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_READ_BYTES) { "File is too large to open" }
        val bytes = file.readBytes()
        // A NUL byte in the first block is the usual signal that this is not text.
        val binary = bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }
        return OpenCodeFileContent(
            type = if (binary) "binary" else "text",
            content = if (binary) "" else String(bytes),
        )
    }

    fun find(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        limit: Int?,
    ): List<String> {
        val root = resolveRoot(directory)
        if (query.isBlank()) return emptyList()
        return walk(root)
            .filter { includeDirectories == true || it.isFile }
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { it.relativeTo(root).path }
            .take(limit ?: DEFAULT_LIMIT)
            .toList()
    }

    fun search(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> {
        val root = resolveRoot(directory)
        if (pattern.isBlank()) return emptyList()
        val matches = mutableListOf<OpenCodeSearchMatch>()
        for (file in walk(root).filter { it.isFile && it.length() <= MAX_READ_BYTES }) {
            if (matches.size >= DEFAULT_LIMIT) break
            val relative = file.relativeTo(root).path
            runCatching {
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (matches.size >= DEFAULT_LIMIT) return@forEachIndexed
                        val column = line.indexOf(pattern, ignoreCase = true)
                        if (column < 0) return@forEachIndexed
                        matches +=
                            OpenCodeSearchMatch(
                                path = OpenCodeSearchText(relative),
                                lines = OpenCodeSearchText(line.take(MAX_LINE_CHARS)),
                                lineNumber = index + 1,
                                absoluteOffset = column,
                                submatches = listOf(OpenCodeSearchSubmatch(OpenCodeSearchText(pattern), column, column + pattern.length)),
                            )
                    }
                }
            }
        }
        return matches
    }

    private fun walk(root: File) =
        root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "node_modules" }
            .maxDepth(MAX_DEPTH)

    /**
     * Host directory backing [directory].
     *
     * Sessions record sandbox paths such as `/workspace/project`; everything under `/workspace` maps
     * into the app's own workspace directory.
     */
    private fun resolveRoot(directory: String): File {
        val relative = directory.removePrefix("/workspace").trim('/')
        return if (relative.isEmpty()) workspaceHostDir else File(workspaceHostDir, relative)
    }

    private fun sandboxPath(
        directory: String,
        relative: String,
    ): String = directory.trimEnd('/') + "/" + relative

    /** Null when [path] escapes [root]; the explorer must not reach outside the workspace. */
    private fun resolve(
        root: File,
        path: String,
    ): File? {
        val candidate = if (path.isBlank() || path == ".") root else File(root, path)
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it == canonicalRoot || it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    private companion object {
        const val MAX_READ_BYTES = 2L * 1024 * 1024
        const val BINARY_SNIFF_BYTES = 1024
        const val DEFAULT_LIMIT = 200
        const val MAX_DEPTH = 12
        const val MAX_LINE_CHARS = 400
    }
}
