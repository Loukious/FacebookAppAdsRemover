package tn.loukious.facebookappadsremover.hooks

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
 * (labels are localised, ids are not). Rows of the sections under the tiles
 * (Help & support, Settings & privacy, Other Meta products) carry no id there,
 * only the link they open, and a section header carries its test key: those
 * are mapped to the same ids and section keys the list-style menu uses. After Litho lays a host out, hidden
 * entries are collapsed and the remaining entries are moved into the slots in
 * reading order, so the grid/list has no holes. Runs on every layout pass, so
 * it survives re-renders and recycled hosts.
 *
 * This covers the tile-grid menu. The list-style menu is filtered earlier, in
 * the server response: see [MenuResponseHook]. Two section rows open no link
 * (Report a problem, Link history), so here they go only with their section.
 */
object MenuHideHook {
    private const val TAG = "FBAR.Menu"
    private val HOST_CLASSES = listOf("com.facebook.litho.ComponentHost", "com.facebook.litho.LithoView")
    private val BMID = Regex("\"bmid\":(\\d+)")

    /** What a clickable view of the menu is: a bookmark ([id] != 0) or the header of a [section]. */
    private class Hit(val id: Long, val section: String?, val strong: Boolean) {
        val hidden: Boolean
            get() = (id != 0L && Settings.getBoolean(key(id), false)) ||
                (section != null && Settings.getBoolean(Settings.MENU_SECTION_PREFIX + section, false))
    }
    private val NONE = Hit(0, null, false)

    /** Section header test key -> section key, as in the list-style menu's response. */
    private val HEADERS = mapOf(
        "help_header_test_key" to "help",
        "settings_header_test_key" to "settings",
        "products_from_facebook_test_key" to "apps_from_facebook",
        "upsell_bookmark_section_test_key" to "upsell",
    )

    /** Bookmarks that live in a section: the section's switch hides them too. */
    private val SECTION_OF: Map<Long, String> = HashMap<Long, String>().apply {
        for (id in longArrayOf(1188298679798386, 847392651283475, 580614790498088, 528235453869350,
            943638756156161, 1407107379423635)) put(id, "help")
        for (id in longArrayOf(295535957817803, 413854060266105, 528216224033913, 280033845760645,
            2805739562851096, 938806990723633)) put(id, "settings")
        for (id in longArrayOf(342556368906156, 1546306509211461, 124024574287414, 217974574879787,
            603734381593708)) put(id, "apps_from_facebook")
        put(2650596328428891, "upsell")
    }

    /** Section rows have no id in Java, only the link they open (the same link in both menu styles). */
    private val LINKS: List<Pair<String, Long>> = listOf(
        "fb://maisa_native_chat" to 1188298679798386,
        "https://www.facebook.com/scam-protection-center" to 847392651283475,
        "fb://supporthome" to 580614790498088,
        "fb://bugreport" to 528235453869350,
        "fb://safety" to 943638756156161,
        "fb://policies" to 1407107379423635,
        "fb://settings" to 295535957817803,
        "fb://privacy_center" to 413854060266105,
        "fb://device_requests" to 528216224033913,
        "fb://ads_reconsideration_hub" to 280033845760645,
        "fbinternal://transaction_hub" to 2805739562851096,
        "fb://bloks_screen/?bloks_screen_id=com.bloks.www.bloks.browser_history" to 938806990723633,
        "fb://basel" to 342556368906156,
        "fb://threads" to 1546306509211461,
        "fb://instagram" to 124024574287414,
        "fb://messaging" to 217974574879787,
        "fb://whatsapp" to 603734381593708,
    )

    /** listener object -> what it is (or NONE). Keyed by listener, not view: Litho recycles hosts. */
    private val hits = Collections.synchronizedMap(WeakHashMap<Any, Hit>())
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
        // Each entry may be its own LithoView cell of a list/grid: reflow the container.
        val parent = host.parent as? ViewGroup
        val cell = parent != null && host.javaClass.name == "com.facebook.litho.LithoView"
        // A section header is such a cell and is itself the clickable view.
        if (!(cell && host.isClickable && hitOf(host) != null) && !hasEntry(host, 0)) return
        val root = if (cell) parent!! else host
        schedule(root)
    }

    private fun schedule(root: ViewGroup) {
        if (root.getTag(TAG_PENDING) == true) return
        root.setTag(TAG_PENDING, true)
        // After Litho finishes mounting (it re-lays children after onLayout).
        root.post {
            root.setTag(TAG_PENDING, null)
            runCatching { reflow(root) }.onFailure { L.w(TAG, "reflow failed", it) }
        }
    }

    /**
     * A scrolling list brings rows in without laying out anything this module
     * hooks, so a menu list is also looked at again whenever it scrolls.
     */
    private fun watch(root: ViewGroup) {
        if (root.getTag(TAG_WATCH) != null || !root.isAttachedToWindow) return
        val onScroll = ViewTreeObserver.OnScrollChangedListener { schedule(root) }
        root.setTag(TAG_WATCH, onScroll)
        root.viewTreeObserver.addOnScrollChangedListener(onScroll)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                runCatching { v.viewTreeObserver.removeOnScrollChangedListener(onScroll) }
                v.setTag(TAG_WATCH, null)
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun hasEntry(v: View, depth: Int): Boolean {
        if (depth > 0 && v.isClickable && hitOf(v) != null) return true
        if (v !is ViewGroup || depth >= 3) return false
        for (i in 0 until v.childCount) if (hasEntry(v.getChildAt(i), depth + 1)) return true
        return false
    }

    private class Entry(val view: View, val cell: View, val hit: Hit, val x: Int, val y: Int)

    private fun collect(cell: View, v: View, x: Int, y: Int, depth: Int, out: MutableList<Entry>) {
        if (v.isClickable) {
            val hit = hitOf(v)
            if (hit != null) { out.add(Entry(v, cell, hit, x, y)); return }
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
        // A link alone could be any button of the app: it counts only next to real menu entries.
        if (all.none { it.hit.strong }) return
        watch(root)
        val freed = level(root, all, 0)
        // A list animates its rows with the same translation when a section opens or closes,
        // and finishes by resetting it: look again once that is over.
        if (freed > 0f && root.getTag(TAG_AGAIN) != true) {
            root.setTag(TAG_AGAIN, true)
            root.postDelayed({
                root.setTag(TAG_AGAIN, null)
                runCatching { level(root, collectAll(root), 0) }.onFailure { L.w(TAG, "reflow failed", it) }
            }, 500)
        }
    }

    private fun collectAll(root: ViewGroup): List<Entry> {
        val all = ArrayList<Entry>()
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            collect(c, c, c.left, c.top, 0, all)
        }
        return all
    }

    /**
     * Hides the hidden entries among [root]'s children and closes the gaps
     * they leave. Returns the height freed at the bottom of [root].
     *
     * A child that holds one entry is a cell: cells of one size in consecutive
     * rows form a block (the tile grid, the rows of a section), and the visible
     * ones are moved into the block's slots in reading order. A child that
     * holds several entries is a container (a section with its rows) and is
     * compacted the same way one level down. Whatever is below a block or a
     * container moves up by the height it freed.
     */
    private fun level(root: ViewGroup, all: List<Entry>, depth: Int): Float {
        val byCell = all.groupBy { it.cell }
        // A cell with one entry is hidden as a whole: Litho reuses the views inside a cell for
        // other rows, and a hidden one would turn up in a row that should be visible.
        val hiddenCells = HashSet<View>()
        for (e in all) {
            if (byCell.getValue(e.cell).size == 1) {
                if (e.hit.hidden) hiddenCells += e.cell
            } else if (e.hit.hidden) {
                e.view.visibility = View.GONE; e.view.setTag(TAG_HIDDEN, true)
            } else if (e.view.getTag(TAG_HIDDEN) == true) {
                e.view.visibility = View.VISIBLE; e.view.setTag(TAG_HIDDEN, null)
            }
        }
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c in hiddenCells) {
                if (c.visibility == View.VISIBLE) { c.visibility = View.INVISIBLE; c.setTag(TAG_HIDDEN, true) }
            } else if (c.getTag(TAG_HIDDEN) == true) {
                c.visibility = View.VISIBLE; c.setTag(TAG_HIDDEN, null)
            }
            // Undo previous moves; positions below are recomputed from Litho's layout.
            if (c.getTag(TAG_MOVED) == true) move(c, 0f, 0f)
        }
        /** Freed heights: (y from which everything moves up, how far). */
        val freed = ArrayList<Pair<Int, Float>>()
        val slotMove = HashMap<View, Pair<Float, Float>>()

        for ((cell, entries) in byCell) {
            if (entries.size < 2 || cell !is ViewGroup || depth >= 4) continue
            val inner = ArrayList<Entry>()
            for (i in 0 until cell.childCount) {
                val c = cell.getChildAt(i)
                collect(c, c, c.left, c.top, 0, inner)
            }
            val d = level(cell, inner, depth + 1)
            if (d > 0f) freed += cell.bottom to d
        }

        val cells = byCell.values.filter { it.size == 1 }.map { it[0] }
            .filter { it.view.height > 0 || it.hit.hidden }
        // One column (section headers, the rows of a section): a hidden cell frees its own height.
        // Rows the module does not know may sit in between, so nothing is re-slotted here.
        val wide = cells.filter { it.view.width * 2 > root.width }
        for (e in wide) {
            if (e.hit.hidden && e.cell.height > 0) freed += e.cell.bottom to e.cell.height.toFloat()
        }
        val single = cells - wide.toSet()
        for (group in single.groupBy { it.view.width to it.view.height }.values) {
            val rowH = group[0].view.height
            if (rowH <= 0) continue
            val sorted = group.sortedWith(compareBy({ it.y }, { it.x }))
            var start = 0
            for (i in 1..sorted.size) {
                if (i < sorted.size && sorted[i].y - sorted[i - 1].y <= rowH * 3 / 2) continue
                val block = sorted.subList(start, i)
                start = i
                if (block.none { it.hit.hidden }) continue
                val slots = block.map { it.x to it.y }
                var slot = 0
                var usedBottom = block.first().y
                for (e in block) {
                    if (e.hit.hidden) continue
                    val (sx, sy) = slots[slot++]
                    slotMove[e.cell] = (sx - e.x).toFloat() to (sy - e.y).toFloat()
                    usedBottom = maxOf(usedBottom, sy + rowH)
                }
                val blockBottom = block.maxOf { it.y + rowH }
                val delta = (blockBottom - usedBottom).toFloat()
                // A lone hidden cell (a promo card) frees all of itself, margins included.
                if (block.size == 1) freed += block[0].cell.bottom to block[0].cell.height.toFloat()
                else if (delta > 0f) freed += blockBottom to delta
            }
        }
        if (freed.isEmpty() && slotMove.isEmpty()) return 0f
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            val own = slotMove[c]
            // A block's own cells sit above its bottom, so its freed height never applies to them.
            val up = freed.sumOf { (from, d) -> if (c.top >= from - 2) d.toDouble() else 0.0 }.toFloat()
            move(c, own?.first ?: 0f, (own?.second ?: 0f) - up)
        }
        return freed.sumOf { it.second.toDouble() }.toFloat()
    }

    private val TAG_WATCH = 0x7f0fba05
    private val TAG_AGAIN = 0x7f0fba04
    private val TAG_MOVED = 0x7f0fba03
    private val TAG_PENDING = 0x7f0fba02
    private val TAG_HIDDEN = 0x7f0fba01

    private fun hitOf(v: View): Hit? {
        val li = listenerInfoField?.get(v) ?: return null
        val f = clickField ?: runCatching {
            li.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
        }.getOrNull()?.also { clickField = it } ?: return null
        val listener = f.get(li) ?: return null
        hits[listener]?.let { return if (it === NONE) null else it }
        val found = Found()
        runCatching { find(listener, IdentityHashMap(), intArrayOf(0), 0, found) }
        val id = found.id
        val link = found.link
        val hit = when {
            id != null -> Hit(id, SECTION_OF[id], true)
            found.header != null -> Hit(0, found.header, true)
            link != null -> Hit(link, SECTION_OF[link], false)
            else -> null
        }
        hits[listener] = hit ?: NONE
        if (hit != null) L.i(TAG, "menu entry ${if (hit.id != 0L) hit.id.toString() else "section " + hit.section}")
        return hit
    }

    /** What the walk has seen. The logging JSON settles it; a header key outranks a link. */
    private class Found {
        var id: Long? = null
        var header: String? = null
        var link: Long? = null
    }

    /** Bounded walk of the listener object graph looking for the logging JSON, a header key or a link. */
    private fun find(o: Any?, seen: IdentityHashMap<Any, Boolean>, budget: IntArray, depth: Int, out: Found) {
        if (o == null || out.id != null || budget[0]++ > 600 || depth > 10) return
        if (o is String) {
            if (o.startsWith("{\"bmid\":")) {
                out.id = BMID.find(o)?.groupValues?.get(1)?.toLongOrNull()
            } else if (out.header == null && o.endsWith("_test_key")) {
                out.header = HEADERS[o]
            } else if (out.link == null && (o.startsWith("fb") || o.startsWith("http"))) {
                out.link = LINKS.firstOrNull { o.startsWith(it.first) }?.second
            }
            return
        }
        if (o is Number || o is Boolean || o is Char || o is Enum<*>) return
        if (seen.put(o, true) != null) return
        val cls = o.javaClass
        if (cls.isArray || o is Collection<*> || o is Map<*, *>) return
        if (cls.name.startsWith("android.") || cls.name.startsWith("java.")) return
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                val value = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
                find(value, seen, budget, depth + 1, out)
                if (out.id != null) return
            }
            c = c.superclass
        }
    }
}
