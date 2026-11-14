package tn.loukious.facebookappadsremover.hooks

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Hides side-menu ("bookmarks") entries by their stable bookmark id.
 *
 * Menu entries are Litho ComponentHost views. Their click listener graph holds
 * Facebook's logging JSON `{"bmid":<id>,...}`, so the id is read from there
 * (labels are localised, ids are not). After Litho lays a host out, hidden
 * entries are collapsed and the remaining entries are moved into the slots in
 * reading order, so the grid/list has no holes. Runs on every layout pass, so
 * it survives re-renders and recycled hosts.
 *
 * This covers the tile-grid menu. The list-style menu is filtered earlier, in
 * the server response: see [MenuResponseHook].
 */
object MenuHideHook {
    private const val TAG = "FBAR.Menu"
    private val HOST_CLASSES = listOf("com.facebook.litho.ComponentHost", "com.facebook.litho.LithoView")
    private val BMID = Regex("\"bmid\":(\\d+)")
    private const val NONE = -1L

    /** listener object -> bookmark id (or NONE). Keyed by listener, not view: Litho recycles hosts. */
    private val ids = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    @Volatile private var installed = false

    private val listenerInfoField: Field? by lazy {
        runCatching { View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true } }.getOrNull()
    }
    private var clickField: Field? = null

    fun key(id: Long) = Settings.MENU_HIDE_PREFIX + id

    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (installed) return true
        var count = 0
        for (name in HOST_CLASSES) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            for (m in cls.declaredMethods) {
                if (m.name != "onLayout" && m.name != "performLayout") continue
                val p = m.parameterTypes
                if (p.size != 5 || p[0] != Boolean::class.javaPrimitiveType) continue
                if (Modifier.isAbstract(m.modifiers)) continue
                runCatching {
                    m.isAccessible = true
                    module.hook(m).intercept(LayoutHooker)
                    count++
                }.onFailure { L.w(TAG, "hook ${cls.simpleName}.${m.name} failed", it) }
            }
        }
        installed = count > 0
        if (installed) L.i(TAG, "menu hide: $count layout hooks")
        return installed
    }

    private object LayoutHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val host = chain.thisObject as? ViewGroup ?: return result
            runCatching { compact(host) }.onFailure { L.w(TAG, "compact failed", it) }
            return result
        }
    }

    private fun compact(host: ViewGroup) {
        val hasEntry = hasEntry(host, 0)
        if (!hasEntry) return
        // Each entry may be its own LithoView cell of a list/grid: reflow the container.
        val parent = host.parent as? ViewGroup
        val root = if (parent != null && host.javaClass.name == "com.facebook.litho.LithoView") parent else host
        if (root.getTag(TAG_PENDING) == true) return
        root.setTag(TAG_PENDING, true)
        // After Litho finishes mounting (it re-lays children after onLayout).
        root.post {
            root.setTag(TAG_PENDING, null)
            runCatching { reflow(root) }.onFailure { L.w(TAG, "reflow failed", it) }
        }
    }

    private fun hasEntry(v: View, depth: Int): Boolean {
        if (depth > 0 && v.isClickable && bmidOf(v) != null) return true
        if (v !is ViewGroup || depth >= 3) return false
        for (i in 0 until v.childCount) if (hasEntry(v.getChildAt(i), depth + 1)) return true
        return false
    }

    private class Entry(val view: View, val cell: View, val id: Long, val x: Int, val y: Int)

    private fun collect(cell: View, v: View, x: Int, y: Int, depth: Int, out: MutableList<Entry>) {
        if (v.isClickable) {
            val id = bmidOf(v)
            if (id != null) { out.add(Entry(v, cell, id, x, y)); return }
        }
        if (v !is ViewGroup || depth > 4) return
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            collect(cell, c, x + c.left, y + c.top, depth + 1, out)
        }
    }

    private fun move(v: View, dx: Float, dy: Float) {
        if (v.translationX != dx) v.translationX = dx
        if (v.translationY != dy) v.translationY = dy
        v.setTag(TAG_MOVED, if (dx != 0f || dy != 0f) true else null)
    }

    private fun reflow(root: ViewGroup) {
        val all = ArrayList<Entry>()
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            collect(c, c, c.left, c.top, 0, all)
        }
        for (e in all) {
            val hide = Settings.getBoolean(key(e.id), false)
            if (hide) { e.view.visibility = View.GONE; e.view.setTag(TAG_HIDDEN, true) }
            else if (e.view.getTag(TAG_HIDDEN) == true) { e.view.visibility = View.VISIBLE; e.view.setTag(TAG_HIDDEN, null) }
        }
        // Undo previous moves; positions below are recomputed from Litho's layout.
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c.getTag(TAG_MOVED) == true) move(c, 0f, 0f)
        }
        val hidden = all.filter { it.view.getTag(TAG_HIDDEN) == true && it.view.height > 0 }
        if (hidden.isEmpty()) return
        // The block = same-size entries in consecutive rows around the hidden ones.
        val ref = hidden.first().view
        val same = all.filter { it.view.width == ref.width && it.view.height == ref.height }
            .sortedWith(compareBy({ it.y }, { it.x }))
        val rowH = ref.height
        val block = ArrayList<Entry>()
        for (e in same) {
            if (block.isNotEmpty() && e.y - block.last().y > rowH * 3 / 2) {
                if (block.any { it.view.getTag(TAG_HIDDEN) == true }) break
                block.clear()
            }
            block.add(e)
        }
        if (block.none { it.view.getTag(TAG_HIDDEN) == true }) return
        val slots = block.map { it.x to it.y }
        var slot = 0
        var usedBottom = block.first().y
        for (e in block) {
            if (e.view.getTag(TAG_HIDDEN) == true) continue
            val (sx, sy) = slots[slot++]
            move(e.cell, (sx - e.x).toFloat(), (sy - e.y).toFloat())
            usedBottom = maxOf(usedBottom, sy + rowH)
        }
        if (slot == 0) usedBottom = block.first().y
        val blockBottom = block.maxOf { it.y + rowH }
        val delta = (blockBottom - usedBottom).toFloat()
        if (delta <= 0f) return
        val blockCells = block.mapTo(HashSet()) { it.cell }
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c !in blockCells && c.top >= blockBottom - 2) move(c, 0f, -delta)
        }
    }

    private val TAG_MOVED = 0x7f0fba03
    private val TAG_PENDING = 0x7f0fba02
    private val TAG_HIDDEN = 0x7f0fba01

    private fun bmidOf(v: View): Long? {
        val li = listenerInfoField?.get(v) ?: return null
        val f = clickField ?: runCatching {
            li.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
        }.getOrNull()?.also { clickField = it } ?: return null
        val listener = f.get(li) ?: return null
        ids[listener]?.let { return if (it == NONE) null else it }
        val id = runCatching { find(listener, IdentityHashMap(), intArrayOf(0), 0) }.getOrNull()
        ids[listener] = id ?: NONE
        if (id != null) L.i(TAG, "menu entry $id")
        return id
    }

    /** Bounded walk of the listener object graph looking for the logging JSON. */
    private fun find(o: Any?, seen: IdentityHashMap<Any, Boolean>, budget: IntArray, depth: Int): Long? {
        if (o == null || budget[0]++ > 600 || depth > 10) return null
        if (o is String) return if (o.startsWith("{\"bmid\":")) BMID.find(o)?.groupValues?.get(1)?.toLongOrNull() else null
        if (o is Number || o is Boolean || o is Char || o is Enum<*>) return null
        if (seen.put(o, true) != null) return null
        val cls = o.javaClass
        if (cls.isArray || o is Collection<*> || o is Map<*, *>) return null
        if (cls.name.startsWith("android.") || cls.name.startsWith("java.")) return null
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                val value = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
                find(value, seen, budget, depth + 1)?.let { return it }
            }
            c = c.superclass
        }
        return null
    }
}
