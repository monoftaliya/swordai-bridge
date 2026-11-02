package com.swordai.bridge.core.commands

import com.swordai.bridge.core.Ids
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Имена инструментов. Набор фиксирован: произвольный код от сервера не принимается. */
object Tools {
    const val DEVICE_STATUS = "get_device_status"
    const val SCREEN_INFO = "get_screen_info"
    const val CAPTURE = "capture_screen"
    const val NOTIFICATIONS = "get_notifications"
    const val SEARCH_FILES = "search_files"
    const val OPEN_APP = "open_app"
    const val TAP = "tap_element"
    const val SWIPE = "swipe_screen"
    const val INPUT = "input_text"
    const val BACK = "press_back"
    const val HOME = "press_home"
    const val OPEN_FILE = "open_file"
    const val SEQUENCE = "run_sequence"

    val ALL = listOf(
        DEVICE_STATUS, SCREEN_INFO, CAPTURE, NOTIFICATIONS, SEARCH_FILES, OPEN_APP, TAP, SWIPE, INPUT,
        BACK, HOME, OPEN_FILE, SEQUENCE,
    )
    val READ_ONLY = setOf(DEVICE_STATUS, SCREEN_INFO, CAPTURE, NOTIFICATIONS, SEARCH_FILES)
    val SEQUENCE_STEPS = setOf(OPEN_APP, TAP, SWIPE, INPUT, BACK, HOME, SCREEN_INFO)
    /** Инструменты, взаимодействующие с экраном: требуют включённый и разблокированный экран. */
    val NEEDS_SCREEN = setOf(SCREEN_INFO, CAPTURE, OPEN_APP, TAP, SWIPE, INPUT, BACK, HOME, OPEN_FILE, SEQUENCE)
}

class ArgsException(message: String) : Exception(message)

sealed interface ToolArgs

data class DeviceStatusArgs(val includeApps: Boolean) : ToolArgs
data class ScreenInfoArgs(val maxElements: Int, val includeInvisible: Boolean) : ToolArgs
data class CaptureArgs(val maxDimension: Int, val quality: Int, val format: String) : ToolArgs
data class NotificationsArgs(val limit: Int, val packageName: String?, val includeOngoing: Boolean) : ToolArgs
data class SearchFilesArgs(val query: String, val folder: String?, val maxResults: Int, val mimePrefix: String?) : ToolArgs
data class OpenAppArgs(val packageName: String?, val appName: String?) : ToolArgs
data class TapArgs(
    val elementId: String?,
    val snapshotId: String?,
    val text: String?,
    val exact: Boolean,
    val index: Int,
    val x: Int?,
    val y: Int?,
    val longPress: Boolean,
) : ToolArgs
data class SwipeArgs(val direction: String, val distance: Double, val durationMs: Int, val elementId: String?) : ToolArgs
data class InputArgs(val text: String, val elementId: String?, val mode: String, val submit: Boolean) : ToolArgs
data object BackArgs : ToolArgs
data object HomeArgs : ToolArgs
data class OpenFileArgs(val fileId: String) : ToolArgs
data class SequenceStep(val tool: String, val args: ToolArgs, val waitMs: Int)
data class SequenceArgs(val steps: List<SequenceStep>, val stopOnError: Boolean) : ToolArgs

/** Строгий разбор аргументов: неизвестные поля, неверные типы и выход за границы — ошибка. */
private class Reader(private val obj: JsonObject) {
    private val used = HashSet<String>()

    private fun primitive(key: String): JsonPrimitive? {
        used.add(key)
        val element = obj[key] ?: return null
        if (element is JsonPrimitive && element.content == "null" && !element.isString) return null
        return element as? JsonPrimitive ?: throw ArgsException("$key: ожидается простое значение")
    }

    fun string(key: String, minLen: Int = 0, maxLen: Int, pattern: Regex? = null, allowNewlines: Boolean = false): String? {
        val p = primitive(key) ?: return null
        if (!p.isString) throw ArgsException("$key: ожидается строка")
        val value = p.content
        if (value.length < minLen || value.length > maxLen) throw ArgsException("$key: длина вне диапазона $minLen..$maxLen")
        if (pattern != null && !pattern.matches(value)) throw ArgsException("$key: неверный формат")
        for (ch in value) {
            if (ch.code == 0 || (ch.code < 32 && !(allowNewlines && (ch == '\n' || ch == '\t')))) {
                throw ArgsException("$key: недопустимые управляющие символы")
            }
        }
        return value
    }

    fun requiredString(key: String, minLen: Int = 0, maxLen: Int, pattern: Regex? = null, allowNewlines: Boolean = false): String =
        string(key, minLen, maxLen, pattern, allowNewlines) ?: throw ArgsException("$key: обязательный аргумент")

    fun int(key: String, default: Int?, min: Int, max: Int): Int? {
        val p = primitive(key) ?: return default
        if (p.isString) throw ArgsException("$key: ожидается целое число")
        val value = p.longOrNull ?: throw ArgsException("$key: ожидается целое число")
        if (value < min || value > max) throw ArgsException("$key: значение вне диапазона $min..$max")
        return value.toInt()
    }

    fun double(key: String, default: Double, min: Double, max: Double): Double {
        val p = primitive(key) ?: return default
        if (p.isString) throw ArgsException("$key: ожидается число")
        val value = p.doubleOrNull ?: throw ArgsException("$key: ожидается число")
        if (value.isNaN() || value < min || value > max) throw ArgsException("$key: значение вне диапазона $min..$max")
        return value
    }

    fun bool(key: String, default: Boolean): Boolean {
        val p = primitive(key) ?: return default
        if (p.isString) throw ArgsException("$key: ожидается true/false")
        return p.booleanOrNull ?: throw ArgsException("$key: ожидается true/false")
    }

    fun choice(key: String, allowed: Set<String>, default: String?): String? {
        val value = string(key, maxLen = 40) ?: return default
        if (value !in allowed) throw ArgsException("$key: допустимо ${allowed.joinToString()}")
        return value
    }

    fun array(key: String): JsonArray? {
        used.add(key)
        val element = obj[key] ?: return null
        return element as? JsonArray ?: throw ArgsException("$key: ожидается массив")
    }

    fun objectValue(key: String): JsonObject? {
        used.add(key)
        val element = obj[key] ?: return null
        return element as? JsonObject ?: throw ArgsException("$key: ожидается объект")
    }

    fun finish() {
        val unknown = obj.keys - used
        if (unknown.isNotEmpty()) throw ArgsException("неизвестные аргументы: ${unknown.sorted().joinToString()}")
    }
}

object ToolArgsParser {
    fun parse(tool: String, args: JsonObject, nested: Boolean = false): ToolArgs {
        val r = Reader(args)
        val result: ToolArgs = when (tool) {
            Tools.DEVICE_STATUS -> DeviceStatusArgs(r.bool("include_apps", false))
            Tools.SCREEN_INFO -> ScreenInfoArgs(r.int("max_elements", 150, 10, 400)!!, r.bool("include_invisible", false))
            Tools.CAPTURE -> CaptureArgs(
                r.int("max_dimension", 1280, 320, 2560)!!,
                r.int("quality", 70, 30, 95)!!,
                r.choice("format", setOf("jpeg", "png"), "jpeg")!!,
            )
            Tools.NOTIFICATIONS -> NotificationsArgs(
                r.int("limit", 20, 1, 100)!!,
                r.string("package_name", maxLen = 200, pattern = Ids.PACKAGE_NAME),
                r.bool("include_ongoing", false),
            )
            Tools.SEARCH_FILES -> SearchFilesArgs(
                r.requiredString("query", minLen = 1, maxLen = 200),
                r.string("folder", minLen = 1, maxLen = 200),
                r.int("max_results", 30, 1, 100)!!,
                r.string("mime_prefix", maxLen = 60, pattern = Ids.MIME_PREFIX),
            )
            Tools.OPEN_APP -> {
                val pkg = r.string("package_name", maxLen = 200, pattern = Ids.PACKAGE_NAME)
                val name = r.string("app_name", minLen = 1, maxLen = 100)
                if ((pkg == null) == (name == null)) throw ArgsException("укажите ровно одно: package_name или app_name")
                OpenAppArgs(pkg, name)
            }
            Tools.TAP -> {
                val elementId = r.string("element_id", maxLen = 6, pattern = Ids.ELEMENT_ID)
                val snapshotId = r.string("snapshot_id", maxLen = 40, pattern = Ids.SNAPSHOT_ID)
                val text = r.string("text", minLen = 1, maxLen = 200)
                val x = r.int("x", null, 0, 10000)
                val y = r.int("y", null, 0, 10000)
                val targets = listOf(elementId != null, text != null, x != null || y != null).count { it }
                if (targets != 1) throw ArgsException("укажите ровно одну цель: element_id, text или x+y")
                if ((x == null) != (y == null)) throw ArgsException("x и y указываются вместе")
                if (snapshotId != null && elementId == null) throw ArgsException("snapshot_id допустим только с element_id")
                TapArgs(elementId, snapshotId, text, r.bool("exact", false), r.int("index", 0, 0, 50)!!, x, y, r.bool("long_press", false))
            }
            Tools.SWIPE -> SwipeArgs(
                r.choice("direction", setOf("up", "down", "left", "right"), null)
                    ?: throw ArgsException("direction: обязательный аргумент"),
                r.double("distance", 0.5, 0.1, 1.0),
                r.int("duration_ms", 300, 50, 3000)!!,
                r.string("element_id", maxLen = 6, pattern = Ids.ELEMENT_ID),
            )
            Tools.INPUT -> InputArgs(
                r.requiredString("text", maxLen = 2000, allowNewlines = true),
                r.string("element_id", maxLen = 6, pattern = Ids.ELEMENT_ID),
                r.choice("mode", setOf("replace", "append"), "replace")!!,
                r.bool("submit", false),
            )
            Tools.BACK -> BackArgs
            Tools.HOME -> HomeArgs
            Tools.OPEN_FILE -> OpenFileArgs(r.requiredString("file_id", maxLen = 44, pattern = Ids.FILE_ID))
            Tools.SEQUENCE -> {
                if (nested) throw ArgsException("вложенные последовательности запрещены")
                val raw = r.array("steps") ?: throw ArgsException("steps: обязательный аргумент")
                if (raw.isEmpty() || raw.size > 10) throw ArgsException("steps: от 1 до 10 шагов")
                val steps = raw.mapIndexed { index, element ->
                    val step = element as? JsonObject ?: throw ArgsException("steps[$index]: ожидается объект")
                    val sr = Reader(step)
                    val stepTool = sr.requiredString("tool", maxLen = 40)
                    if (stepTool !in Tools.SEQUENCE_STEPS) throw ArgsException("steps[$index]: инструмент $stepTool недопустим в последовательности")
                    val waitMs = sr.int("wait_ms", 0, 0, 5000)!!
                    val stepArgs = sr.objectValue("args") ?: JsonObject(emptyMap())
                    sr.finish()
                    val parsed = try {
                        parse(stepTool, stepArgs, nested = true)
                    } catch (e: ArgsException) {
                        throw ArgsException("steps[$index]: ${e.message}")
                    }
                    SequenceStep(stepTool, parsed, waitMs)
                }
                SequenceArgs(steps, r.bool("stop_on_error", true))
            }
            else -> throw ArgsException("неизвестный инструмент: $tool")
        }
        r.finish()
        return result
    }
}
