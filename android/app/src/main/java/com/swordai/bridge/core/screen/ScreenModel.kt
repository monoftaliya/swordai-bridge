package com.swordai.bridge.core.screen

import com.swordai.bridge.core.jsonOf
import kotlinx.serialization.json.JsonObject

/** Элемент экрана в компактном виде для ИИ. */
data class UiElement(
    val id: String,
    val cls: String,
    val text: String?,
    val desc: String?,
    val resourceId: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val flags: List<String>,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun toJson(): JsonObject = jsonOf(
        "id" to id,
        "cls" to cls,
        "text" to text,
        "desc" to desc,
        "rid" to resourceId,
        "b" to intArrayOf(left, top, right, bottom),
        "f" to flags,
    )
}

data class ScreenSnapshotInfo(
    val snapshotId: String,
    val packageName: String?,
    val appLabel: String?,
    val activity: String?,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val keyboardVisible: Boolean,
    val elements: List<UiElement>,
    val totalCandidates: Int,
) {
    val truncated: Boolean get() = totalCandidates > elements.size

    fun toJson(): JsonObject = jsonOf(
        "snapshot_id" to snapshotId,
        "package" to packageName,
        "app_label" to appLabel,
        "activity" to activity,
        "screen" to jsonOf("w" to width, "h" to height, "rotation" to rotation),
        "keyboard" to keyboardVisible,
        "elements" to elements.map { it.toJson() },
        "element_count" to totalCandidates,
        "truncated" to truncated,
    )
}

object ClassNames {
    /** android.widget.Button → Button; androidx.compose.ui.platform.ComposeView → ComposeView */
    fun short(className: CharSequence?): String {
        val name = className?.toString().orEmpty()
        if (name.isEmpty()) return "View"
        return name.substringAfterLast('.').substringAfterLast('$').ifEmpty { "View" }
    }

    fun clip(text: CharSequence?, max: Int = 200): String? {
        val value = text?.toString()?.replace('\n', ' ')?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.length <= max) value else value.take(max - 1) + "…"
    }
}
