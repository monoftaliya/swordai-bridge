package com.swordai.bridge.core

import com.swordai.bridge.core.commands.Tools
import com.swordai.bridge.core.files.NameMatcher
import com.swordai.bridge.core.net.Backoff
import com.swordai.bridge.core.protocol.Command
import com.swordai.bridge.core.security.ActionContext
import com.swordai.bridge.core.security.ConfirmMode
import com.swordai.bridge.core.security.ProtectedApps
import com.swordai.bridge.core.security.Redactor
import com.swordai.bridge.core.security.ReplayGuard
import com.swordai.bridge.core.security.Risk
import com.swordai.bridge.core.security.SecurityPolicy
import com.swordai.bridge.core.security.SensitiveWords
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SecurityCoreTest {
    private fun cmd(id: String, seq: Long, issued: Double = 1000.0, expires: Double = 1120.0) =
        Command(id, "dev_TestDevice0001", seq, "press_back", JsonObject(emptyMap()), issued, expires, "mcp", "ChatGPT")

    @Test
    fun replayGuardRules() {
        val saved = mutableListOf<ReplayGuard.Snapshot>()
        val guard = ReplayGuard(object : ReplayGuard.Persistence {
            override fun load(): ReplayGuard.Snapshot? = null
            override fun save(snapshot: ReplayGuard.Snapshot) { saved.add(snapshot) }
        })
        val first = cmd("cmd_AAAAAAAA0001", 1)
        assertEquals(ReplayGuard.Verdict.ACCEPT, guard.check(first, 1010.0))
        guard.accept(first)
        assertEquals(ReplayGuard.Verdict.DUPLICATE, guard.check(first, 1010.0))
        assertEquals(ReplayGuard.Verdict.REPLAYED, guard.check(cmd("cmd_AAAAAAAA0002", 1), 1010.0))
        assertEquals(ReplayGuard.Verdict.EXPIRED, guard.check(cmd("cmd_AAAAAAAA0003", 2), 2000.0))
        assertEquals(ReplayGuard.Verdict.FROM_FUTURE, guard.check(cmd("cmd_AAAAAAAA0004", 3, issued = 5000.0, expires = 6000.0), 1010.0))
        assertEquals(1L, saved.last().lastSeq)
        // Состояние переживает перезапуск
        val restored = ReplayGuard(object : ReplayGuard.Persistence {
            override fun load(): ReplayGuard.Snapshot = saved.last()
            override fun save(snapshot: ReplayGuard.Snapshot) {}
        })
        assertEquals(ReplayGuard.Verdict.DUPLICATE, restored.check(first, 1010.0))
        assertEquals(ReplayGuard.Verdict.ACCEPT, restored.check(cmd("cmd_AAAAAAAA0005", 2), 1010.0))
    }

    private val own = "com.swordai.bridge"
    private fun policy(mode: ConfirmMode = ConfirmMode.SIGNIFICANT, userBlocked: Set<String> = emptySet()) =
        SecurityPolicy(own, { it in userBlocked || ProtectedApps.isProtectedByDefault(it) }, mode)

    @Test
    fun policyBlocksProtectedAppsAndPasswords() {
        val p = policy()
        assertFalse(p.evaluate(ActionContext(Tools.OPEN_APP, "com.android.launcher", targetPackage = "ru.sberbankmobile")).allowed)
        assertFalse(p.evaluate(ActionContext(Tools.OPEN_APP, null, targetPackage = "com.example.mybank.app")).allowed)
        assertFalse(p.evaluate(ActionContext(Tools.TAP, "com.idamob.tinkoff.android", targetLabel = "Перевести")).allowed)
        assertFalse(p.evaluate(ActionContext(Tools.SCREEN_INFO, "com.x8bit.bitwarden")).allowed)
        assertFalse(p.evaluate(ActionContext(Tools.TAP, own, targetLabel = "Настройки")).allowed)
        val pwd = p.evaluate(ActionContext(Tools.INPUT, "com.example.app", targetIsPassword = true))
        assertFalse(pwd.allowed)
        assertEquals("password_field", pwd.code)
        // Назад/Домой разрешены всегда — это способ уйти с экрана
        assertTrue(p.evaluate(ActionContext(Tools.BACK, "ru.sberbankmobile")).allowed)
        assertTrue(p.evaluate(ActionContext(Tools.HOME, "ru.sberbankmobile")).allowed)
    }

    @Test
    fun policyRequiresConfirmationForSignificantActions() {
        val p = policy()
        val send = p.evaluate(ActionContext(Tools.TAP, "org.telegram.messenger", targetLabel = "Отправить"))
        assertTrue(send.allowed && send.needsConfirmation && send.risk == Risk.SIGNIFICANT)
        assertTrue(p.evaluate(ActionContext(Tools.TAP, "com.whatsapp", targetLabel = "Send")).needsConfirmation)
        assertTrue(p.evaluate(ActionContext(Tools.TAP, "com.example", targetLabel = "Delete chat")).needsConfirmation)
        assertTrue(p.evaluate(ActionContext(Tools.TAP, "com.example", targetKnown = false)).needsConfirmation)
        assertTrue(p.evaluate(ActionContext(Tools.TAP, "com.android.settings", targetLabel = "Wi-Fi")).needsConfirmation)
        assertTrue(p.evaluate(ActionContext(Tools.INPUT, "com.example", submit = true)).needsConfirmation)
        val search = p.evaluate(ActionContext(Tools.TAP, "com.android.chrome", targetLabel = "Поиск"))
        assertTrue(search.allowed && !search.needsConfirmation)
        assertFalse(p.evaluate(ActionContext(Tools.SCREEN_INFO, "com.android.chrome")).needsConfirmation)
    }

    @Test
    fun confirmModesEscalate() {
        val all = policy(ConfirmMode.ALL_ACTIONS)
        assertTrue(all.evaluate(ActionContext(Tools.TAP, "com.android.chrome", targetLabel = "Поиск")).needsConfirmation)
        assertFalse(all.evaluate(ActionContext(Tools.SCREEN_INFO, "com.android.chrome")).needsConfirmation)
        val everything = policy(ConfirmMode.EVERYTHING)
        assertTrue(everything.evaluate(ActionContext(Tools.SCREEN_INFO, "com.android.chrome")).needsConfirmation)
        assertEquals(ConfirmMode.SIGNIFICANT, ConfirmMode.fromKey("bogus"))
    }

    @Test
    fun sensitiveWords() {
        for (label in listOf("Отправить", "отправка", "Удалить чат", "Оплатить 500 ₽", "Купить", "Подтвердить", "OK", "Send",
                "Delete", "Pay", "Buy now", "Log out", "Выйти из аккаунта", "Install", "Allow")) {
            assertTrue(label, SensitiveWords.isSignificant(label))
        }
        for (label in listOf("Поиск", "Назад", "Search", "Settings", "Чаты", "Профиль", "Камера")) {
            assertFalse(label, SensitiveWords.isSignificant(label))
        }
        assertFalse(SensitiveWords.isSignificant(null))
    }

    @Test
    fun redactorMasksCodes() {
        assertEquals("Ваш код: ••••••", Redactor.redact("Ваш код: 482913"))
        assertEquals("Code 12-34-56 for login".replace(Regex("\\d"), "•"), Redactor.redact("Code 12-34-56 for login"))
        assertEquals("Карта •••• •••• •••• ••••", Redactor.redact("Карта 4276 3800 1234 1234"))
        assertEquals("Встреча в 1530", Redactor.redact("Встреча в 1530"))
        assertTrue(Redactor.redact("Списание 4276380012341234")!!.contains("••••••••"))
    }

    @Test
    fun backoffGrowsAndCaps() {
        val backoff = Backoff(random = Random(42))
        val delays = List(10) { backoff.nextDelayMs() }
        assertTrue(delays.first() in 800..1200)
        assertTrue(delays.last() <= 60_000)
        assertTrue(delays[4] > delays[1])
        backoff.reset()
        assertTrue(backoff.nextDelayMs() <= 1200)
    }

    @Test
    fun nameMatcher() {
        assertTrue(NameMatcher("*.pdf").matches("Report 2026.PDF"))
        assertFalse(NameMatcher("*.pdf").matches("report.pdf.txt"))
        assertTrue(NameMatcher("отч").matches("Отчёт.docx"))
        assertTrue(NameMatcher("IMG_????.jpg").matches("img_1234.JPG"))
        assertTrue(NameMatcher("a(b)").matches("xa(b)y"))
    }
}
