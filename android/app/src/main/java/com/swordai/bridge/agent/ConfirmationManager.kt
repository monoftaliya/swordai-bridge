package com.swordai.bridge.agent

import android.content.Context
import com.swordai.bridge.service.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Подтверждение значимых действий человеком на телефоне.
 * Запрос показывается уведомлением с кнопками (для «Разрешить» на Android 12+ требуется
 * разблокировка устройства) и карточкой на главном экране приложения.
 */
class ConfirmationManager(private val context: Context) {

    enum class Outcome { APPROVED, DENIED, TIMEOUT }

    data class Request(
        val id: String,
        val title: String,
        val detail: String,
        val client: String,
        val createdAt: Long,
        val expiresAt: Long,
    )

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    suspend fun request(id: String, title: String, detail: String, client: String, timeoutMs: Long = TIMEOUT_MS): Outcome {
        val deferred = CompletableDeferred<Boolean>()
        val now = System.currentTimeMillis()
        val request = Request(id, title, detail, client, now, now + timeoutMs)
        waiting[id] = deferred
        _pending.update { it + request }
        Notifications.showConfirmation(context, request)
        try {
            val approved = withTimeoutOrNull(timeoutMs) { deferred.await() }
            return when (approved) {
                null -> Outcome.TIMEOUT
                true -> Outcome.APPROVED
                false -> Outcome.DENIED
            }
        } finally {
            waiting.remove(id)
            _pending.update { list -> list.filterNot { it.id == id } }
            Notifications.cancelConfirmation(context, id)
        }
    }

    fun resolve(id: String, approve: Boolean) {
        waiting[id]?.complete(approve)
    }

    /** Отклонить все ожидающие запросы (аварийная остановка). */
    fun denyAll() {
        waiting.values.forEach { it.complete(false) }
    }

    companion object {
        const val TIMEOUT_MS = 90_000L
    }
}
