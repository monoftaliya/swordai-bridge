package com.swordai.bridge.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.swordai.bridge.accessibility.AgentAccessibilityService
import com.swordai.bridge.agent.ToolOutcome
import com.swordai.bridge.core.Ids
import com.swordai.bridge.core.commands.SearchFilesArgs
import com.swordai.bridge.core.files.NameMatcher
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.data.FolderGrant
import com.swordai.bridge.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Поиск и открытие файлов только в папках, которые пользователь явно выбрал
 * через системный диалог (Storage Access Framework). Доступ — только на чтение.
 */
class FileGateway(private val context: Context, private val settings: Settings) {

    data class FileRef(val uri: Uri, val name: String, val mime: String?)

    private val refs = object : LinkedHashMap<String, FileRef>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileRef>?): Boolean = size > 500
    }

    fun addFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = displayName(treeUri) ?: treeUri.lastPathSegment ?: "Папка"
        settings.update { s ->
            if (s.folders.any { it.uri == treeUri.toString() }) s
            else s.copy(folders = s.folders + FolderGrant(treeUri.toString(), name))
        }
    }

    fun removeFolder(folder: FolderGrant) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // разрешение уже отозвано
        }
        settings.update { s -> s.copy(folders = s.folders.filterNot { it.uri == folder.uri }) }
    }

    private fun displayName(treeUri: Uri): String? = try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    suspend fun search(args: SearchFilesArgs): ToolOutcome = withContext(Dispatchers.IO) {
        val folders = settings.current.folders.filter { folder ->
            args.folder == null || folder.name.equals(args.folder, ignoreCase = true) ||
                folder.name.contains(args.folder, ignoreCase = true)
        }
        if (settings.current.folders.isEmpty()) {
            return@withContext ToolOutcome.Failure(
                "no_folders",
                "Пользователь не разрешил ни одной папки. В приложении: Разрешения → Папки для поиска.",
            )
        }
        if (folders.isEmpty()) {
            return@withContext ToolOutcome.Failure(
                "folder_not_found",
                "Нет разрешённой папки «${args.folder}». Доступные: ${settings.current.folders.joinToString { it.name }}",
            )
        }
        val matcher = NameMatcher(args.query)
        val deadline = System.currentTimeMillis() + 8_000
        val results = ArrayList<Map<String, Any?>>()
        val skipped = ArrayList<String>()
        var visited = 0
        var truncated = false
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        outer@ for (folder in folders) {
            val treeUri = Uri.parse(folder.uri)
            val rootId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (e: Exception) {
                skipped.add(folder.name)
                continue
            }
            val queue = ArrayDeque<Triple<String, String, Int>>()
            queue.addLast(Triple(rootId, "", 0))
            while (queue.isNotEmpty()) {
                if (results.size >= args.maxResults || visited >= 5_000 || System.currentTimeMillis() > deadline) {
                    truncated = true
                    break@outer
                }
                val (docId, relPath, depth) = queue.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                try {
                    context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            visited++
                            val id = cursor.getString(0) ?: continue
                            val name = cursor.getString(1) ?: continue
                            val mime = cursor.getString(2)
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (depth < 10) queue.addLast(Triple(id, "$relPath/$name", depth + 1))
                                continue
                            }
                            if (!matcher.matches(name)) continue
                            if (args.mimePrefix != null && mime?.startsWith(args.mimePrefix) != true) continue
                            val fileId = Ids.file()
                            synchronized(refs) {
                                refs[fileId] = FileRef(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, mime)
                            }
                            results.add(
                                mapOf(
                                    "file_id" to fileId,
                                    "name" to name,
                                    "folder" to folder.name,
                                    "path" to "$relPath/$name".trimStart('/'),
                                    "mime" to mime,
                                    "size" to if (cursor.isNull(3)) null else cursor.getLong(3),
                                    "modified" to if (cursor.isNull(4)) null else cursor.getLong(4),
                                ),
                            )
                            if (results.size >= args.maxResults) break
                        }
                    }
                } catch (e: SecurityException) {
                    skipped.add(folder.name)
                    continue@outer
                }
            }
        }
        ToolOutcome.Success(
            jsonOf(
                "results" to results,
                "count" to results.size,
                "truncated" to truncated,
                "folders_searched" to folders.map { it.name },
                "unavailable_folders" to skipped.ifEmpty { null },
            ),
        )
    }

    fun open(fileId: String): ToolOutcome {
        val ref = synchronized(refs) { refs[fileId] }
            ?: return ToolOutcome.Failure("file_not_found", "file_id не найден — выполните search_files заново")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ref.uri, ref.mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val launcher: Context = AgentAccessibilityService.instance ?: context
        return try {
            launcher.startActivity(intent)
            ToolOutcome.Success(jsonOf("opened" to ref.name, "mime" to ref.mime))
        } catch (e: ActivityNotFoundException) {
            ToolOutcome.Failure("no_app", "Нет приложения для открытия файла типа ${ref.mime ?: "неизвестен"}")
        } catch (e: SecurityException) {
            ToolOutcome.Failure("permission_lost", "Доступ к папке был отозван — добавьте её заново")
        }
    }
}
