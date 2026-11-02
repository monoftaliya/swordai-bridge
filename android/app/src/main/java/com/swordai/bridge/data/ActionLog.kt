package com.swordai.bridge.data

import android.content.Context
import com.swordai.bridge.core.JsonCodec
import com.swordai.bridge.core.log.LogBuffer
import com.swordai.bridge.core.log.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Журнал действий на телефоне: в памяти + файл (последние 400 записей). */
class ActionLog(context: Context, private val scope: CoroutineScope) {
    private val buffer = LogBuffer(400)
    private val file = File(context.filesDir, "action_log.json")
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()
    private var saveJob: Job? = null

    init {
        try {
            if (file.exists()) {
                val arr = JsonCodec.parseToJsonElement(file.readText()) as? JsonArray
                val restored = arr?.mapNotNull { (it as? JsonObject)?.let(LogEntry::fromJson) }.orEmpty()
                buffer.restore(restored)
                _entries.value = buffer.snapshot()
            }
        } catch (e: Exception) {
            file.delete()
        }
    }

    fun add(category: String, title: String, detail: String?, status: String) {
        buffer.add(category, title, detail, status, System.currentTimeMillis())
        _entries.value = buffer.snapshot()
        scheduleSave()
    }

    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
        scheduleSave()
    }

    @Synchronized
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1500)
            val arr = JsonArray(buffer.snapshot().map { it.toJson() })
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JsonCodec.encodeToString(JsonArray.serializer(), arr))
            tmp.renameTo(file)
        }
    }
}
