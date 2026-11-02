package com.swordai.bridge.core

import com.swordai.bridge.core.commands.ArgsException
import com.swordai.bridge.core.commands.BackArgs
import com.swordai.bridge.core.commands.InputArgs
import com.swordai.bridge.core.commands.OpenAppArgs
import com.swordai.bridge.core.commands.SequenceArgs
import com.swordai.bridge.core.commands.SwipeArgs
import com.swordai.bridge.core.commands.TapArgs
import com.swordai.bridge.core.commands.ToolArgsParser
import com.swordai.bridge.core.commands.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolArgsTest {
    private fun parse(tool: String, json: String) = ToolArgsParser.parse(tool, parseJsonObject(json)!!)

    private fun rejects(tool: String, json: String) {
        try {
            parse(tool, json)
            fail("должно быть отклонено: $tool $json")
        } catch (e: ArgsException) {
            // ожидаемо
        }
    }

    @Test
    fun parsesServerNormalizedArgs() {
        // Аргументы в том виде, в каком их нормализует сервер (с умолчаниями)
        val tap = parse(Tools.TAP, """{"element_id":"e12","snapshot_id":"s_abc123","exact":false,"index":0,"long_press":false}""") as TapArgs
        assertEquals("e12", tap.elementId)
        assertEquals("s_abc123", tap.snapshotId)
        val xy = parse(Tools.TAP, """{"x":10,"y":20,"exact":false,"index":0,"long_press":true}""") as TapArgs
        assertEquals(10, xy.x)
        assertTrue(xy.longPress)
        val swipe = parse(Tools.SWIPE, """{"direction":"up","distance":0.5,"duration_ms":300}""") as SwipeArgs
        assertEquals(0.5, swipe.distance, 0.0)
        val input = parse(Tools.INPUT, """{"text":"строка 1\nстрока 2","mode":"append","submit":false}""") as InputArgs
        assertEquals("append", input.mode)
        assertEquals(BackArgs, parse(Tools.BACK, "{}"))
        val open = parse(Tools.OPEN_APP, """{"app_name":"Камера"}""") as OpenAppArgs
        assertEquals("Камера", open.appName)
    }

    @Test
    fun parsesSequence() {
        val seq = parse(
            Tools.SEQUENCE,
            """{"steps":[{"tool":"open_app","args":{"package_name":"org.telegram.messenger"},"wait_ms":800},
               {"tool":"tap_element","args":{"text":"Поиск","exact":false,"index":0,"long_press":false},"wait_ms":0}],
               "stop_on_error":true}""",
        ) as SequenceArgs
        assertEquals(2, seq.steps.size)
        assertEquals(800, seq.steps[0].waitMs)
        assertTrue(seq.steps[1].args is TapArgs)
    }

    @Test
    fun rejectsInvalidArgs() {
        rejects(Tools.TAP, "{}")
        rejects(Tools.TAP, """{"element_id":"e1","text":"OK"}""")
        rejects(Tools.TAP, """{"x":5}""")
        rejects(Tools.TAP, """{"element_id":"e1; rm"}""")
        rejects(Tools.TAP, """{"text":"OK","evil":true}""")
        rejects(Tools.TAP, """{"text":"OK","index":"0"}""")
        rejects(Tools.INPUT, """{"text":"a\u0000b"}""")
        rejects(Tools.INPUT, """{"text":5}""")
        rejects(Tools.OPEN_APP, """{"package_name":"bad","app_name":"x"}""")
        rejects(Tools.OPEN_APP, """{"package_name":"notapackage"}""")
        rejects(Tools.SWIPE, """{"direction":"diagonal"}""")
        rejects(Tools.SWIPE, """{"direction":"up","distance":5}""")
        rejects(Tools.CAPTURE, """{"quality":99}""")
        rejects(Tools.OPEN_FILE, """{"file_id":"../../etc/passwd"}""")
        rejects(Tools.SEQUENCE, """{"steps":[]}""")
        rejects(Tools.SEQUENCE, """{"steps":[{"tool":"capture_screen"}]}""")
        rejects(Tools.SEQUENCE, """{"steps":[{"tool":"run_sequence","args":{}}]}""")
        rejects(Tools.SEQUENCE, """{"steps":[{"tool":"tap_element","args":{}}]}""")
        rejects(Tools.SEQUENCE, """{"steps":[{"tool":"press_back","extra":1}]}""")
        rejects("format_disk", "{}")
    }
}
