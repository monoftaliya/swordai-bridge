package com.swordai.bridge.core.log

import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.core.longOrNull
import com.swordai.bridge.core.stringOrNull
import kotlinx.serialization.json.JsonObject

/** Запись журнала действий на телефоне. */
data class LogEntry(
    val id: Long,
    val time: Long,
    val category: String,
    val title: String,
    val detail: String?,
    val status: String,
) {
    fun toJson(): JsonObject = jsonOf(
        "id" to id,
        "time" to time,
        "category" to category,
        "title" to title,
        "detail" to detail,
        "status" to status,
    )

    companion object {
        const val CAT_COMMAND = "command"
        const val CAT_SECURITY = "security"
        const val CAT_CONNECTION = "connection"
        const val CAT_SYSTEM = "system"

        const val ST_OK = "ok"
        const val ST_ERROR = "error"
        const val ST_WARN = "warn"
        const val ST_INFO = "info"
        const val ST_PENDING = "pending"

        fun fromJson(obj: JsonObject): LogEntry? {
            val id = obj.longOrNull("id") ?: return null
            val time = obj.longOrNull("time") ?: return null
            return LogEntry(
                id = id,
                time = time,
                category = obj.stringOrNull("category") ?: CAT_SYSTEM,
                title = obj.stringOrNull("title") ?: return null,
                detail = obj.stringOrNull("detail"),
                status = obj.stringOrNull("status") ?: ST_INFO,
            )
        }
    }
}

/** Кольцевой буфер последних записей (новые — в начале). */
class LogBuffer(private val capacity: Int = 400) {
    private val items = ArrayDeque<LogEntry>()
    private var nextId = 1L

    @Synchronized
    fun add(category: String, title: String, detail: String?, status: String, time: Long): LogEntry {
        val entry = LogEntry(nextId++, time, category, title.take(200), detail?.take(1000), status)
        items.addFirst(entry)
        while (items.size > capacity) items.removeLast()
        return entry
    }

    @Synchronized
    fun restore(entries: List<LogEntry>) {
        items.clear()
        entries.sortedByDescending { it.id }.take(capacity).forEach { items.addLast(it) }
        nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = items.toList()

    @Synchronized
    fun clear() {
        items.clear()
    }
}
