package tn.loukious.facebookappadsremover.hooks

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Field
import java.lang.reflect.Method
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
 * are mapped to the same ids and section keys the list-style menu uses. A row
 * that opens no link (Report a problem) holds its id as a number instead.
 *
 * After Litho lays a host out, hidden tiles are collapsed and the remaining
 * ones are moved into the slots in reading order, so the grid has no holes.
 * A hidden row, section header or the Your shortcuts block is taken out of the
 * list's own layout instead: its cell is measured shorter, see [Cut]. Runs on
 * every layout pass, so it survives re-renders and recycled hosts.
 *
 * This covers the tile-grid menu. The list-style menu is filtered earlier, in
 * the server response: see [MenuResponseHook].
 */
object MenuHideHook {
    private const val TAG = "FBAR.Menu"
    private const val LITHO_VIEW = "com.facebook.litho.LithoView"
    private const val COMPONENT_TREE = "com.facebook.litho.ComponentTree"
    private const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"
    private const val SHORTCUTS = "shortcut"
    private const val SEE_MORE = "see_more_test_key"
    /** How long the list takes to show or drop the tiles after See more is pressed. */
    private const val EXPAND_MS = 700L
    /** How deep and how far the walk for the See more button goes. */
    private const val DEEP = 13
    private const val DEEP_BUDGET = 4000
    private val HOST_CLASSES = listOf("com.facebook.litho.ComponentHost", LITHO_VIEW)
    private val BMID = Regex("\"bmid\":(\\d+)")

    /**
     * What a clickable view of the menu is: a bookmark ([id] != 0), the header
     * of a [section] or the See more button ([more]).
     */
    private class Hit(val id: Long, val section: String?, val strong: Boolean, val more: Boolean = false) {
        val hidden: Boolean
            get() = (id != 0L && Settings.getBoolean(key(id), false)) ||
                (section != null && Settings.getBoolean(Settings.MENU_SECTION_PREFIX + section, false))
    }
    private val NONE = Hit(0, null, false)
    private val MORE = Hit(0, null, true, more = true)

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
        for (id in longArrayOf(401948974882678, 342556368906156, 1546306509211461, 124024574287414, 217974574879787,
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

    /**
     * A cell of the menu list that is laid out shorter than Litho measures it:
     * [from] is Litho's height, [to] the height the list gets (0 for a hidden
     * row). The list then places the rows below by itself, so nothing has to be
     * moved and no empty space is left at the end.
     */
    private class Cut(val from: Int, val to: Int)

    /** Litho's tree of a cell -> its cut. Keyed by the tree, not the view: a list reuses its cells. */
    private val cuts = Collections.synchronizedMap(WeakHashMap<Any, Cut>())
    private var treeFields: List<Field>? = null

    /** Litho's tree of a tile -> how far it is moved from where the list puts it. */
    private val moves = Collections.synchronizedMap(WeakHashMap<Any, Pair<Int, Int>>())
    private val setMeasured: Method? by lazy {
        runCatching {
            View::class.java.getDeclaredMethod(
                "setMeasuredDimension", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    /** listener object -> whether the deeper walk found it to be the See more button. */
    private val mores = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

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
                val p = m.parameterTypes
                val hooker = when {
                    Modifier.isAbstract(m.modifiers) -> null
                    (m.name == "onLayout" || m.name == "performLayout") && p.size == 5 &&
                        p[0] == Boolean::class.javaPrimitiveType -> LayoutHooker
                    m.name == "onMeasure" && p.size == 2 && name == LITHO_VIEW -> MeasureHooker
                    else -> null
                } ?: continue
                runCatching {
                    m.isAccessible = true
                    module.hook(m).intercept(hooker)
                    if (hooker === LayoutHooker) count++
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

    private object MeasureHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (cuts.isEmpty()) return result
            val v = chain.thisObject as? View ?: return result
            runCatching { shorten(v) }.onFailure { L.w(TAG, "shorten failed", it) }
            return result
        }
    }

    private fun shorten(v: View) {
        val tree = treeOf(v) ?: return
        val cut = cuts[tree] ?: return
        when (v.measuredHeight) {
            cut.from -> setMeasured?.invoke(v, v.measuredWidth, cut.to)
            // The list asking for the very height it has given the cell changes nothing.
            cut.to -> {}
            // The cell's content has changed since: it is looked at again.
            else -> {
                cuts.remove(tree)
                (v.parent as? ViewGroup)?.let { schedule(it) }
            }
        }
    }

    /** Litho's tree shown in [cell], found by its type: the field names are obfuscated. */
    private fun treeOf(cell: View): Any? {
        if (cell.javaClass.name != LITHO_VIEW) return null
        val fields = treeFields ?: cell.javaClass.declaredFields
            .filter { it.type.name == COMPONENT_TREE && !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
            .also { treeFields = it }
        for (f in fields) f.get(cell)?.let { return it }
        return null
    }

    /** Lays [cell] out [to] pixels high from now on. False if that is not possible for this view. */
    private fun cut(cell: View, to: Int): Boolean {
        if (setMeasured == null) return false
        val tree = treeOf(cell) ?: return false
        val cut = cuts[tree]
        if (cut == null && cell.height > to) {
            cuts[tree] = Cut(cell.height, to)
            remeasure(cell)
        } else if (cut != null && cell.height == cut.from && cut.from != cut.to) {
            // Still as high as before: the request to measure it again got lost.
            remeasure(cell)
        }
        return true
    }

    /**
     * Has the list measure [cell] again. Not through the cell's own
     * requestLayout: a Litho host drops that while it is being laid out.
     */
    private fun remeasure(cell: View) {
        cell.forceLayout()
        (cell.parent as? View)?.requestLayout()
    }

    private fun compact(host: ViewGroup) {
        // Each entry may be its own LithoView cell of a list/grid: reflow the container.
        val parent = host.parent as? ViewGroup
        val cell = parent != null && host.javaClass.name == LITHO_VIEW
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
        if (Settings.getBoolean(Settings.MENU_SECTION_PREFIX + SHORTCUTS, false)) cutShortcuts(root)
        if (Settings.getBoolean(Settings.MENU_EXPAND, false)) expand(root, all)
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

    /**
     * Shows all tiles: presses See more once, then takes the button out of the
     * list. The button says nowhere which way it goes, so the tiles are counted
     * before and after: if pressing it left fewer, the menu was open already
     * and it is pressed again.
     */
    private fun expand(root: ViewGroup, all: List<Entry>) {
        val button = all.firstOrNull { it.hit.more } ?: seeMore(root) ?: return
        val tiles = all.count { it.hit.id != 0L && it.hit.section == null }
        when (val state = root.getTag(TAG_MORE)) {
            null -> press(root, button, Pressed(tiles, SystemClock.uptimeMillis(), false))
            is Pressed -> when {
                SystemClock.uptimeMillis() - state.at < EXPAND_MS -> {}
                tiles < state.tiles && !state.again ->
                    press(root, button, Pressed(tiles, SystemClock.uptimeMillis(), true))
                else -> { root.setTag(TAG_MORE, true); cut(button.cell, 0) }
            }
            else -> cut(button.cell, 0)
        }
    }

    /**
     * The See more button among the full-width cells of [root]. Its test key
     * lies deeper than the walk every clickable view gets, so the views that
     * walk told nothing about are looked into further here, in a menu only.
     */
    private fun seeMore(root: ViewGroup): Entry? {
        for (i in 0 until root.childCount) {
            val cell = root.getChildAt(i)
            if (cell.width * 2 <= root.width) continue
            val view = seeMoreIn(cell, 0) ?: continue
            return Entry(view, cell, MORE, cell.left, cell.top)
        }
        return null
    }

    private fun seeMoreIn(v: View, depth: Int): View? {
        if (v.isClickable && hitOf(v) == null) {
            val listener = listenerOf(v)
            if (listener != null && mores.getOrPut(listener) {
                    val found = Found()
                    runCatching { find(listener, IdentityHashMap(), intArrayOf(600 - DEEP_BUDGET), 0, found, DEEP) }
                    found.more
                }) return v
        }
        if (v !is ViewGroup || depth > 4) return null
        for (i in 0 until v.childCount) seeMoreIn(v.getChildAt(i), depth + 1)?.let { return it }
        return null
    }

    /** See more has been pressed [at] this time with [tiles] tiles on screen; [again] for the second time. */
    private class Pressed(val tiles: Int, val at: Long, val again: Boolean)

    private fun press(root: ViewGroup, button: Entry, state: Pressed) {
        root.setTag(TAG_MORE, state)
        button.view.performClick()
        root.postDelayed({ schedule(root) }, EXPAND_MS + 50)
    }

    /**
     * Your shortcuts is no cell of its own: it is the end of the first cell,
     * under the profile card, and the only thing there that scrolls sideways.
     * That cell is cut right after the last button above the block, keeping
     * the margin the cell ends with.
     */
    private fun cutShortcuts(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val cell = root.getChildAt(i) as? ViewGroup ?: continue
            if (cell.height <= 0 || cell.width * 2 <= root.width) continue
            val tree = treeOf(cell) ?: continue
            if (cuts[tree] != null) { cut(cell, cuts[tree]?.to ?: continue); continue }
            val list = IntArray(2)
            if (!findList(cell, 0, 0, list)) continue
            // Only a margin may follow the block: anything more would be cut off with it.
            if (cell.height - list[1] > (list[1] - list[0]) / 4) continue
            val above = buttonsBottom(cell, 0, list[0], 0)
            val to = if (above > 0) above + (cell.height - list[1]).coerceAtLeast(0) else 0
            if (to < cell.height && cut(cell, to)) L.i(TAG, "menu shortcuts cut ${cell.height} -> $to")
        }
    }

    /** Finds a list nested in [v]; [out] gets its top and bottom, [y] being the top of [v]. */
    private fun findList(v: ViewGroup, y: Int, depth: Int, out: IntArray): Boolean {
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i) as? ViewGroup ?: continue
            if (isList(c)) { out[0] = y + c.top; out[1] = y + c.bottom; return true }
            if (depth < 4 && findList(c, y + c.top, depth + 1, out)) return true
        }
        return false
    }

    private fun isList(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        while (c != null) { if (c.name == RECYCLER_VIEW) return true; c = c.superclass }
        return false
    }

    /** The lowest bottom of a button in [v] that ends above [limit]; [y] is the top of [v]. */
    private fun buttonsBottom(v: ViewGroup, y: Int, limit: Int, depth: Int): Int {
        var bottom = 0
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (isList(c)) continue
            if (c.isClickable && y + c.bottom <= limit) bottom = maxOf(bottom, y + c.bottom)
            if (c is ViewGroup && depth < 6) bottom = maxOf(bottom, buttonsBottom(c, y + c.top, limit, depth + 1))
        }
        return bottom
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
        /** Hidden cells the list itself closes up: see [Cut]. */
        val cutCells = HashSet<View>()
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c in hiddenCells) {
                // A cell with a row to itself (a section's header, row or promo card) can go
                // out of the list's layout; a tile shares its row with another one.
                val alone = c.width * 2 > root.width || byCell.getValue(c)[0].hit.section != null
                if (alone && cut(c, 0)) {
                    cutCells += c
                    // The hairline above a section header goes with it.
                    val line = if (i > 0 && c.isClickable) root.getChildAt(i - 1) else null
                    if (line != null && line.height in 1..4) cut(line, 0)
                } else if (c.visibility == View.VISIBLE) { c.visibility = View.INVISIBLE; c.setTag(TAG_HIDDEN, true) }
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
            .filter { (it.view.height > 0 || it.hit.hidden) && it.cell !in cutCells }
        // One column (section headers, the rows of a section) where a cell could not be cut:
        // a hidden cell frees its own height. Rows the module does not know may sit in
        // between, so nothing is re-slotted here.
        val wide = cells.filter { it.view.width * 2 > root.width }
        for (e in wide) {
            if (e.hit.hidden && e.cell.height > 0) freed += e.cell.bottom to e.cell.height.toFloat()
        }
        val single = cells - wide.toSet()
        /** Height the tile blocks freed. */
        var tiles = 0
        // Tiles: the cells of consecutive rows form a block, and its visible ones are placed
        // anew from the first hidden one on. Tiles differ in height, so places are not swapped.
        val sorted = single.sortedWith(compareBy({ it.cell.top }, { it.cell.left }))
        var start = 0
        var bottom = 0
        for (i in 0..sorted.size) {
            if (i < sorted.size && (i == start || sorted[i].cell.top <= bottom + 4)) {
                bottom = maxOf(if (i == start) 0 else bottom, sorted[i].cell.bottom)
                continue
            }
            val block = sorted.subList(start, i)
            val blockBottom = bottom
            start = i
            if (i < sorted.size) bottom = sorted[i].cell.bottom
            if (block.isEmpty()) continue
            val first = block[0].cell
            val cw = maxOf(1, first.width)
            val columns = maxOf(1, root.width / cw)
            val x0 = first.left - (first.left / cw).coerceIn(0, columns - 1) * cw
            // A list keeps only the cells on screen. With the block's first tiles scrolled away
            // the hidden ones among them are not known: the tiles then keep the move that was
            // worked out while those were there.
            val whole = first.top > 0 || (0 until root.childCount).any { root.getChildAt(it).top < first.top }
            if (whole && block.none { it.hit.hidden }) {
                for (e in block) treeOf(e.cell)?.let { moves.remove(it) }
                continue
            }
            /** Where each column ends so far; unknown until a tile has been placed in it. */
            val ends = IntArray(columns) { if (whole) first.top else Int.MIN_VALUE }
            var moving = false
            for (e in block) {
                val c = e.cell
                val tree = treeOf(c)
                if (e.hit.hidden) { moving = true; continue }
                val own = ((c.left - x0) / cw).coerceIn(0, columns - 1)
                val known = if (whole) null else moves[tree]
                val column: Int
                val y: Int
                if (known != null) {
                    column = ((c.left + known.first - x0) / cw).coerceIn(0, columns - 1)
                    y = c.top + known.second
                } else if ((whole && !moving) || ends.any { it == Int.MIN_VALUE }) {
                    // Before the first hidden tile, or with nothing to go by: it stays.
                    column = own
                    y = c.top
                } else {
                    // The list puts a tile into the column that ends higher: so does this.
                    column = ends.indices.minByOrNull { ends[it] } ?: own
                    y = ends[column]
                }
                ends[column] = maxOf(ends[column], y + c.height)
                val dx = x0 + column * cw - c.left
                val dy = y - c.top
                if (tree != null) moves[tree] = dx to dy
                if (dx != 0 || dy != 0) slotMove[c] = dx.toFloat() to dy.toFloat()
            }
            val usedBottom = ends.maxOrNull()?.takeIf { it != Int.MIN_VALUE } ?: blockBottom
            if (blockBottom > usedBottom) {
                freed += blockBottom to (blockBottom - usedBottom).toFloat()
                tiles += blockBottom - usedBottom
            }
        }
        if (isList(root)) {
            // With no tile on screen the rows they freed are not known: keep the last count.
            if (single.isEmpty()) {
                tiles = root.getTag(TAG_FREED) as? Int ?: 0
                if (tiles > 0) freed += Int.MIN_VALUE to tiles.toFloat()
            } else root.setTag(TAG_FREED, tiles)
            // The rows below are only moved up, the list still counts the freed height: let it
            // lay out and scroll that much further, or its end would be empty space.
            if (root.paddingBottom != -tiles && (tiles > 0 || root.paddingBottom < 0)) {
                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, -tiles)
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

    private val TAG_MORE = 0x7f0fba07
    private val TAG_FREED = 0x7f0fba06
    private val TAG_WATCH = 0x7f0fba05
    private val TAG_AGAIN = 0x7f0fba04
    private val TAG_MOVED = 0x7f0fba03
    private val TAG_PENDING = 0x7f0fba02
    private val TAG_HIDDEN = 0x7f0fba01

    private fun listenerOf(v: View): Any? {
        val li = listenerInfoField?.get(v) ?: return null
        val f = clickField ?: runCatching {
            li.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
        }.getOrNull()?.also { clickField = it } ?: return null
        return f.get(li)
    }

    private fun hitOf(v: View): Hit? {
        val listener = listenerOf(v) ?: return null
        hits[listener]?.let { return if (it === NONE) null else it }
        val found = Found()
        runCatching { find(listener, IdentityHashMap(), intArrayOf(0), 0, found) }
        val id = found.id
        val row = found.row
        val link = found.link
        val hit = when {
            id != null -> Hit(id, SECTION_OF[id], true)
            found.header != null -> Hit(0, found.header, true)
            found.more -> MORE
            row != null -> Hit(row, SECTION_OF[row], false)
            link != null -> Hit(link, SECTION_OF[link], false)
            else -> null
        }
        hits[listener] = hit ?: NONE
        if (hit != null) L.i(TAG, "menu entry " + when {
            hit.id != 0L -> hit.id.toString()
            hit.more -> "see more"
            else -> "section " + hit.section
        })
        return hit
    }

    /** What the walk has seen. The logging JSON settles it; a header key outranks a row's id or link. */
    private class Found {
        var id: Long? = null
        var header: String? = null
        var more = false
        /** The id of a section row, held as a number by the row itself. */
        var row: Long? = null
        var link: Long? = null
    }

    /** Bounded walk of the listener object graph looking for the logging JSON, a header key, a row id or a link. */
    private fun find(
        o: Any?, seen: IdentityHashMap<Any, Boolean>, budget: IntArray, depth: Int, out: Found, limit: Int = 10,
    ) {
        if (o == null || out.id != null || out.more || budget[0]++ > 600 || depth > limit) return
        if (o is String) {
            if (o.startsWith("{\"bmid\":")) {
                out.id = BMID.find(o)?.groupValues?.get(1)?.toLongOrNull()
            } else if (out.header == null && o.endsWith("_test_key")) {
                out.header = HEADERS[o]
                if (o == SEE_MORE) out.more = true
            } else if (out.link == null && (o.startsWith("fb") || o.startsWith("http"))) {
                out.link = LINKS.firstOrNull { o.startsWith(it.first) }?.second
            }
            return
        }
        if (o is Long) { if (out.row == null && o in SECTION_OF) out.row = o; return }
        if (o is Number || o is Boolean || o is Char || o is Enum<*>) return
        if (seen.put(o, true) != null) return
        val cls = o.javaClass
        if (cls.isArray || o is Collection<*> || o is Map<*, *>) return
        if (cls.name.startsWith("android.") || cls.name.startsWith("java.")) return
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive) {
                    // A row's id is a plain long; looking at it costs none of the budget.
                    if (f.type == Long::class.javaPrimitiveType && out.row == null) {
                        val n = runCatching { f.isAccessible = true; f.getLong(o) }.getOrNull()
                        if (n != null && n in SECTION_OF) out.row = n
                    }
                    continue
                }
                val value = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
                find(value, seen, budget, depth + 1, out, limit)
                if (out.id != null || out.more) return
            }
            c = c.superclass
        }
    }
}
