package com.swordai.bridge.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.swordai.bridge.AppGraph
import com.swordai.bridge.core.Ids
import com.swordai.bridge.core.screen.ClassNames
import com.swordai.bridge.core.screen.ScreenSnapshotInfo
import com.swordai.bridge.core.screen.UiElement
import kotlin.math.abs
import kotlin.math.min

/** Ссылка на узел экрана вместе с его описанием. */
data class NodeRef(
    val node: AccessibilityNodeInfo,
    val element: UiElement,
    val isPassword: Boolean,
)

/**
 * Чтение дерева специальных возможностей в компактный список элементов.
 * Тексты полей паролей не читаются никогда.
 */
object ScreenReader {
    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 80
    private const val KEEP_SNAPSHOTS = 3

    private val snapshots = LinkedHashMap<String, Map<String, NodeRef>>()
    private var latestSnapshotId: String? = null

    private class Candidate(val node: AccessibilityNodeInfo, val order: Int, val interactive: Boolean)

    @Synchronized
    fun snapshot(service: AgentAccessibilityService, maxElements: Int, includeInvisible: Boolean): ScreenSnapshotInfo? {
        val root = service.rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString()
        val candidates = ArrayList<Candidate>()
        traverse(root, includeInvisible) { node ->
            if (isInteresting(node) && hasArea(node)) {
                candidates.add(Candidate(node, candidates.size, isInteractive(node)))
            }
        }
        // При переполнении приоритет — интерактивным элементам; порядок чтения сохраняется.
        val selected = if (candidates.size <= maxElements) {
            candidates
        } else {
            val interactive = candidates.filter { it.interactive }.take(maxElements)
            val rest = candidates.filter { !it.interactive }.take(maxElements - interactive.size)
            (interactive + rest).sortedBy { it.order }
        }
        val refs = LinkedHashMap<String, NodeRef>()
        val elements = ArrayList<UiElement>()
        for (candidate in selected) {
            val id = "e${elements.size + 1}"
            val ref = describe(candidate.node, id) ?: continue
            refs[id] = ref
            elements.add(ref.element)
        }
        val snapshotId = Ids.snapshot()
        snapshots[snapshotId] = refs
        latestSnapshotId = snapshotId
        while (snapshots.size > KEEP_SNAPSHOTS) snapshots.remove(snapshots.keys.first())

        val metrics = ScreenMetrics.get(service)
        val keyboard = try {
            service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            false
        }
        val activity = if (service.lastWindowPackage == packageName) {
            service.lastWindowClass?.let { ClassNames.short(it) }
        } else {
            null
        }
        return ScreenSnapshotInfo(
            snapshotId = snapshotId,
            packageName = packageName,
            appLabel = packageName?.let { AppGraph.apps.label(it) },
            activity = activity,
            width = metrics.width,
            height = metrics.height,
            rotation = metrics.rotation,
            keyboardVisible = keyboard,
            elements = elements,
            totalCandidates = candidates.size,
        )
    }

    /** Найти элемент из сохранённого снимка; если экран изменился — попытаться найти тот же элемент заново. */
    @Synchronized
    fun resolve(service: AgentAccessibilityService, elementId: String, snapshotId: String?): NodeRef? {
        val snapshot = (snapshotId ?: latestSnapshotId)?.let { snapshots[it] } ?: return null
        val ref = snapshot[elementId] ?: return null
        if (ref.node.refresh()) {
            describe(ref.node, ref.element.id)?.let { return it }
        }
        return refind(service, ref)
    }

    private fun refind(service: AgentAccessibilityService, old: NodeRef): NodeRef? {
        val root = service.rootInActiveWindow ?: return null
        var best: NodeRef? = null
        var bestDistance = Int.MAX_VALUE
        traverse(root, includeInvisible = false) { node ->
            if (ClassNames.short(node.className) != old.element.cls) return@traverse
            val rid = node.viewIdResourceName?.substringAfter(":id/")
            if (old.element.resourceId != null && rid != old.element.resourceId) return@traverse
            val text = if (node.isPassword) null else ClassNames.clip(node.text)
            val desc = ClassNames.clip(node.contentDescription)
            if (text != old.element.text || desc != old.element.desc) return@traverse
            val ref = describe(node, old.element.id) ?: return@traverse
            val distance = abs(ref.element.centerX - old.element.centerX) + abs(ref.element.centerY - old.element.centerY)
            if (distance < bestDistance) {
                best = ref
                bestDistance = distance
            }
        }
        return best
    }

    /** Поиск по тексту или описанию (без учёта регистра); [index] — номер совпадения сверху вниз. */
    fun findByText(service: AgentAccessibilityService, query: String, exact: Boolean, index: Int): Pair<NodeRef?, Int> {
        val root = service.rootInActiveWindow ?: return null to 0
        val needle = query.trim().lowercase()
        val matches = ArrayList<NodeRef>()
        traverse(root, includeInvisible = false) { node ->
            if (node.isPassword) return@traverse
            val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).map { it.trim().lowercase() }
            val hit = values.any { if (exact) it == needle else it.contains(needle) }
            if (hit) describe(node, "t${matches.size + 1}")?.let { matches.add(it) }
        }
        matches.sortWith(compareBy({ it.element.top }, { it.element.left }))
        return matches.getOrNull(index) to matches.size
    }

    /** Самый вложенный видимый элемент, содержащий точку. */
    fun nodeAt(service: AgentAccessibilityService, x: Int, y: Int): NodeRef? {
        val root = service.rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val rect = Rect()
        traverse(root, includeInvisible = false) { node ->
            node.getBoundsInScreen(rect)
            if (rect.contains(x, y)) {
                val area = rect.width().toLong() * rect.height().toLong()
                if (area in 1 until bestArea) {
                    best = node
                    bestArea = area
                }
            }
        }
        return best?.let { describe(it, "p1") }
    }

    /** Подпись цели для политики безопасности: текст, описание, id ресурса, соседние подписи. */
    fun labelFor(ref: NodeRef): String? {
        val parts = LinkedHashSet<String>()
        fun add(value: CharSequence?) {
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it.take(80)) }
        }
        val node = ref.node
        if (!node.isPassword) add(node.text)
        add(node.contentDescription)
        ref.element.resourceId?.let { add(it) }
        for (i in 0 until min(node.childCount, 6)) {
            val child = node.getChild(i) ?: continue
            if (!child.isPassword) add(child.text)
            add(child.contentDescription)
            for (j in 0 until min(child.childCount, 4)) {
                val grand = child.getChild(j) ?: continue
                if (!grand.isPassword) add(grand.text)
                add(grand.contentDescription)
            }
        }
        var parent = node.parent
        var level = 0
        while (parent != null && level < 3) {
            if (parent.isClickable) add(parent.contentDescription)
            parent = parent.parent
            level++
        }
        return parts.joinToString(" | ").take(240).ifEmpty { null }
    }

    fun describe(node: AccessibilityNodeInfo, id: String): NodeRef? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val password = node.isPassword
        val showingHint = node.isShowingHintText
        val text = if (password || showingHint) null else ClassNames.clip(node.text)
        var desc = ClassNames.clip(node.contentDescription)
        if (desc == null && showingHint) desc = ClassNames.clip(node.hintText)?.let { "подсказка: $it" }
        val flags = ArrayList<String>(6)
        if (node.isClickable) flags.add("click")
        if (node.isLongClickable) flags.add("longclick")
        if (node.isEditable) flags.add("edit")
        if (node.isScrollable) flags.add("scroll")
        if (node.isCheckable) flags.add(if (node.isChecked) "checked" else "unchecked")
        if (node.isSelected) flags.add("selected")
        if (node.isFocused) flags.add("focused")
        if (!node.isEnabled) flags.add("disabled")
        if (password) flags.add("password")
        val element = UiElement(
            id = id,
            cls = ClassNames.short(node.className),
            text = text,
            desc = desc,
            resourceId = node.viewIdResourceName?.substringAfter(":id/")?.take(80),
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            flags = flags,
        )
        return NodeRef(node, element, password)
    }

    private fun isInteractive(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable || node.isCheckable

    private fun isInteresting(node: AccessibilityNodeInfo): Boolean =
        isInteractive(node) || !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

    private fun hasArea(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width() > 0 && rect.height() > 0
    }

    /** Обход дерева в глубину в порядке чтения, с ограничениями размера и глубины. */
    fun traverse(root: AccessibilityNodeInfo, includeInvisible: Boolean, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++
            if (!includeInvisible && !node.isVisibleToUser) continue
            visit(node)
            if (depth < MAX_DEPTH) {
                for (i in node.childCount - 1 downTo 0) {
                    node.getChild(i)?.let { stack.addLast(it to depth + 1) }
                }
            }
        }
    }
}
