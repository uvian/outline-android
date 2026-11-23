package com.outline.notes.data.backup

import com.outline.notes.data.local.CollectionEntity
import com.outline.notes.data.local.DocumentEntity
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BackupExporter {

    /**
     * Exports all cached notes as clean Markdown files grouped by collection folders inside a ZIP archive.
     */
    fun writeMarkdownZip(
        collections: List<CollectionEntity>,
        documents: List<DocumentEntity>,
        outputStream: OutputStream,
    ): Int {
        val colNameById = collections.associate { it.id to sanitizeFileName(it.name) }
        val usedPaths = mutableSetOf<String>()
        var exportedCount = 0

        ZipOutputStream(outputStream).use { zip ->
            for (doc in documents) {
                if (doc.isDeleted) continue
                val folder = when {
                    doc.isArchived -> "_Archived"
                    doc.isDraft -> "_Drafts"
                    !doc.collectionId.isNullOrBlank() -> colNameById[doc.collectionId] ?: "_Uncategorized"
                    else -> "_Uncategorized"
                }
                val baseTitle = sanitizeFileName(doc.title.ifBlank { "Untitled" })
                var entryPath = "$folder/$baseTitle.md"
                var suffix = 2
                while (!usedPaths.add(entryPath)) {
                    entryPath = "$folder/${baseTitle}_$suffix.md"
                    suffix++
                }

                val content = buildString {
                    append("# ")
                    append(doc.title.ifBlank { "无标题笔记" })
                    append("\n\n")
                    append(doc.text)
                    if (!doc.text.endsWith("\n")) append("\n")
                }

                zip.putNextEntry(ZipEntry(entryPath))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                exportedCount++
            }
        }
        return exportedCount
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(64)
            .ifBlank { "Untitled" }
    }
}
