package com.jel.handgesture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.Region
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs

/**
 * 방향키식 요소 이동: 화면에서 누를 수 있는 요소를 모아
 * 현재 요소에서 지정한 방향으로 가장 가까운 요소를 고른다.
 * 위에 다른 창이 덮고 있는 요소는 제외한다.
 */
class FocusNavigator(private val svc: AccessibilityService) {
    private class Item(val rect: Rect, val node: AccessibilityNodeInfo)

    var current: Rect? = null
        private set
    private var currentNode: AccessibilityNodeInfo? = null

    fun reset() { current = null; currentNode = null }

    private fun collect(): List<Item> {
        val out = ArrayList<Item>()
        val covered = Region()
        val wins = try { svc.windows.sortedByDescending { it.layer } } catch (e: Exception) { emptyList() }
        val roots = if (wins.isEmpty()) listOfNotNull(svc.rootInActiveWindow?.let { it to Rect(0, 0, 100000, 100000) })
        else wins.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .mapNotNull { w -> val b = Rect(); w.getBoundsInScreen(b); w.root?.let { it to b } }
        for ((root, bounds) in roots) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.addLast(root)
            var visited = 0
            while (q.isNotEmpty() && visited < 2500) {
                val n = q.removeFirst(); visited++
                if (!n.isVisibleToUser) continue
                if (n.isClickable || n.isCheckable || n.isEditable) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    if (r.width() > 8 && r.height() > 8 && !covered.contains(r.centerX(), r.centerY())) out += Item(r, n)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
            }
            covered.op(bounds, Region.Op.UNION)
        }
        // 다른 요소를 통째로 감싸는 큰 컨테이너는 제외 (안쪽 요소를 우선)
        return out.filter { a -> out.none { b -> b !== a && a.rect.contains(b.rect) && a.rect != b.rect } || out.size < 3 }
    }

    /** 화면 중앙에 가장 가까운 요소 선택 */
    fun enter(screenW: Int, screenH: Int): Rect? {
        val items = collect()
        val cx = screenW / 2; val cy = screenH / 3
        val best = items.minByOrNull { abs(it.rect.centerX() - cx) + abs(it.rect.centerY() - cy) } ?: return null
        current = Rect(best.rect); currentNode = best.node
        return current
    }

    /** 방향(dx, dy ∈ -1..1)으로 다음 요소. 없으면 null */
    fun move(dx: Int, dy: Int): Rect? {
        val cur = current ?: return null
        val items = collect()
        val cx = cur.exactCenterX(); val cy = cur.exactCenterY()
        var best: Item? = null
        var bestScore = Float.MAX_VALUE
        for (it in items) {
            if (it.rect == cur) continue
            val vx = it.rect.exactCenterX() - cx
            val vy = it.rect.exactCenterY() - cy
            val primary = vx * dx + vy * dy
            if (primary <= 6f) continue
            val ortho = abs(vx * dy) + abs(vy * dx)
            val score = primary + 2.5f * ortho
            if (score < bestScore) { bestScore = score; best = it }
        }
        val b = best ?: return null
        current = Rect(b.rect); currentNode = b.node
        return current
    }

    /** 스크롤 후 다시 찾기: 이전 위치에서 가장 가까운 요소 */
    fun relocate(): Rect? {
        val cur = current ?: return null
        val best = collect().minByOrNull { abs(it.rect.centerX() - cur.centerX()) + abs(it.rect.centerY() - cur.centerY()) } ?: return null
        current = Rect(best.rect); currentNode = best.node
        return current
    }

    /** 선택된 요소 클릭 (실패하면 null 반환 → 호출 측에서 탭) */
    fun click(): Boolean {
        val n = currentNode ?: return false
        return try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (e: Exception) { false }
    }
}
