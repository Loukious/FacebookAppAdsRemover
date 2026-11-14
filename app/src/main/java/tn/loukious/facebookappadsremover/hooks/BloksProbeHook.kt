package tn.loukious.facebookappadsremover.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * RESEARCH ONLY (feature/menu-hiding): dumps parsed Bloks trees that look
 * like the side menu, so the item -> bookmark id layout can be mapped.
 * Hooks the Bloks payload parser of Facebook 587 by name (X.051T.A00).
 * Not meant to ship.
 */
object BloksProbeHook {
    private const val TAG = "FBAR.BloksProbe"
    private val dumps = AtomicInteger()
    private val calls = AtomicInteger()
    @Volatile private var installed = false

    private object BookmarkCtor : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val r = chain.proceed()
            L.i(TAG, "BookmarkData ${chain.args.joinToString(" | ")}")
            return r
        }
    }

    fun installBookmarkData(module: XposedInterface, classLoader: ClassLoader): Boolean = runCatching {
        val c = Class.forName("com.facebook.bookmark.model.BookmarkData", false, classLoader)
        var n = 0
        for (k in c.declaredConstructors) { module.hook(k).intercept(BookmarkCtor); n++ }
        L.i(TAG, "BookmarkData ctor hooks: $n (methods=${c.declaredMethods.size})")
        n > 0
    }.getOrElse { L.w(TAG, "BookmarkData hook failed", it); false }

    /** Every 3 s: if the top window shows the side menu, dump each row's views + handlers once. */
    fun startViewScan() {
        Thread {
            var done = false
            var attempts = 0
            Thread.sleep(32_000)
            // Bounded: never spin. At most 5 tries, 3 s apart.
            while (!done && attempts++ < 5) {
                if (attempts > 1) Thread.sleep(3000)
                runCatching {
                    val wmg = Class.forName("android.view.WindowManagerGlobal")
                    val inst = wmg.getMethod("getInstance").invoke(null)
                    val views = wmg.getDeclaredField("mRoots").apply { isAccessible = true }.get(inst) as List<*>
                    val roots = views.mapNotNull { it?.javaClass?.getMethod("getView")?.invoke(it) as? android.view.View }
                    if (roots.isEmpty()) return@runCatching
                    val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.content.Context
                    val dir = File(app.cacheDir, "fbar_bloks").apply { mkdirs() }
                    // Every window: the menu may live in its own one.
                    val fresh = (wmg.getDeclaredField("mRoots").apply { isAccessible = true }.get(inst) as List<*>)
                        .mapNotNull { it?.javaClass?.getMethod("getView")?.invoke(it) as? android.view.View }
                    // Small, targeted: only the menu tiles, found by their label.
                    val labels = setOf("Marketplace", "Сохраненное", "Группы", "Ленты", "Воспоминания")
                    val hits = ArrayList<android.view.View>()
                    fun find(v: android.view.View) {
                        if (v.contentDescription?.toString() in labels) hits += v
                        if (v is android.view.ViewGroup) for (k in 0 until v.childCount) find(v.getChildAt(k))
                    }
                    fresh.forEach { find(it) }
                    val out = StringBuilder()
                    out.append("tiles found: ").append(hits.size).append('\n')
                    for (v in hits.take(5)) {
                        out.append("== ").append(v.contentDescription).append(' ').append(v.javaClass.name)
                            .append(" clickable=").append(v.isClickable).append(" children=")
                            .append((v as? android.view.ViewGroup)?.childCount).append('\n')
                        var p: Any? = v.parent
                        var n = 0
                        while (p is android.view.View && n++ < 8) { out.append("  parent ").append(p.javaClass.name).append('\n'); p = p.parent }
                        runCatching {
                            val li = android.view.View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(v)
                            val l = li?.javaClass?.getDeclaredField("mOnClickListener")?.apply { isAccessible = true }?.get(li)
                            out.append("  onClick:\n")
                            dump(l, 2, out, IdentityHashMap(), intArrayOf(0))
                        }
                        if (out.length > 2_000_000) break
                    }
                    File(dir, "tiles.txt").writeText(out.toString())
                    L.i(TAG, "tile scan: ${hits.size} tiles (${out.length} chars)")
                    // PROTOTYPE: hide Marketplace + Meta AI by bookmark id.
                    val hide = setOf(1606854132932955L, 1120393419403920L)
                    val all = ArrayList<android.view.View>()
                    fun collect(v: android.view.View) {
                        if (v.hasOnClickListeners()) all += v
                        if (v is android.view.ViewGroup) for (k in 0 until v.childCount) collect(v.getChildAt(k))
                    }
                    fresh.forEach { collect(it) }
                    val targets = all.filter { bmidOf(it) in hide }
                    L.i(TAG, "prototype: ${all.size} clickable views, hiding ${targets.size}")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        targets.forEach { it.visibility = android.view.View.GONE }
                    }
                    done = true
                }.onFailure { L.w(TAG, "view scan failed (attempt $attempts)", it) }
            }
        }.apply { name = "fbar-view-scan"; isDaemon = true }.start()
    }

    private val BMID = Regex("\"bmid\":(\\d+)")

    /** Bookmark id carried by a menu tile's click handler (logging JSON), or null. */
    fun bmidOf(v: android.view.View): Long? = runCatching {
        val li = android.view.View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(v)
            ?: return null
        val l = li.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }.get(li) ?: return null
        findBmid(l, IdentityHashMap(), intArrayOf(0), 0)
    }.getOrNull()

    private fun findBmid(o: Any?, seen: IdentityHashMap<Any, Boolean>, budget: IntArray, depth: Int): Long? {
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
                f.isAccessible = true
                findBmid(runCatching { f.get(o) }.getOrNull(), seen, budget, depth + 1)?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    private fun containsText(v: android.view.View, t: String): Boolean {
        if ((v.contentDescription?.toString() ?: "").contains(t)) return true
        if (v is android.widget.TextView && v.text.toString().contains(t)) return true
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (containsText(v.getChildAt(i), t)) return true
        return false
    }

    private fun scanView(v: android.view.View, depth: Int, out: StringBuilder) {
        if (depth > 60) return
        val pad = " ".repeat(depth)
        out.append(pad).append(v.javaClass.name).append(" desc=").append(v.contentDescription)
            .append(" clickable=").append(v.isClickable)
        if (v is android.widget.TextView) out.append(" text=").append(v.text)
        out.append('\n')
        if (v.isClickable || v.hasOnClickListeners()) {
            runCatching {
                val li = android.view.View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(v)
                val l = li?.javaClass?.getDeclaredField("mOnClickListener")?.apply { isAccessible = true }?.get(li)
                val tl = li?.javaClass?.getDeclaredField("mOnTouchListener")?.apply { isAccessible = true }?.get(li)
                for (h in listOfNotNull(l, tl)) {
                    out.append(pad).append(" handler:\n")
                    dump(h, depth + 2, out, IdentityHashMap(), intArrayOf(0))
                }
            }
        }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) scanView(v.getChildAt(i), depth + 1, out)
    }

    /** Finds the parser by its log strings with DexKit (real code lives in the secondary dex). */
    fun install(module: XposedInterface, classLoader: ClassLoader, sources: List<String>): Boolean {
        if (installed) return true
        installed = true
        Thread {
            for (src in sources) {
                val bridge = runCatching { org.luckypray.dexkit.DexKitBridge.create(src) }.getOrNull() ?: continue
                try {
                    val hits = bridge.findMethod { matcher { usingStrings("BloksParser", "unknown bloks data type") } }
                    L.i(TAG, "$src: ${hits.size} parser candidate(s): ${hits.take(5).joinToString { it.descriptor }}")
                    var count = 0
                    for (hit in hits) {
                        val m = runCatching { hit.getMethodInstance(classLoader) }.getOrNull() ?: continue
                        runCatching {
                            m.isAccessible = true
                            module.hook(m).intercept(Probe)
                            count++
                        }.onFailure { L.w(TAG, "hook $m failed", it) }
                    }
                    L.i(TAG, "bloks probe armed on $count method(s)")
                    if (count > 0) return@Thread
                } finally {
                    bridge.close()
                }
            }
        }.apply { name = "fbar-bloks-probe" }.start()
        return true
    }

    private object Probe : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (result != null) runCatching { inspect(result) }.onFailure { L.w(TAG, "dump failed", it) }
            return result
        }
    }

    private fun inspect(root: Any) {
        val text = StringBuilder()
        dump(root, 0, text, IdentityHashMap(), intArrayOf(0))
        val s = text.toString()
        val call = calls.incrementAndGet()
        if (call <= 60) {
            val strings = Regex("\"([^\"]{3,60})\"").findAll(s).map { it.groupValues[1] }.take(6).toList()
            L.i(TAG, "parse #$call ${root.javaClass.name} len=${s.length} strings=$strings")
        }
        val hit = Regex("\\b(24838|18571|18573)\\b").containsMatchIn(s) || "Marketplace" in s
        if (!hit) return
        val n = dumps.incrementAndGet()
        if (n > 200) return
        val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
            as android.content.Context
        val dir = File(app.cacheDir, "fbar_bloks").apply { mkdirs() }
        File(dir, "dump_$n.txt").writeText(s)
        L.i(TAG, "dumped bloks tree #$n (${s.length} chars)")
    }

    private fun dump(o: Any?, depth: Int, out: StringBuilder, seen: IdentityHashMap<Any, Boolean>, budget: IntArray) {
        if (budget[0]++ > 4_000 || depth > 16) return
        val pad = "  ".repeat(depth)
        when (o) {
            null -> { out.append(pad).append("null\n"); return }
            is String -> { out.append(pad).append('"').append(o.take(200)).append("\"\n"); return }
            is Number, is Boolean, is Char -> { out.append(pad).append(o).append('\n'); return }
            is Enum<*> -> { out.append(pad).append("enum ").append(o.name).append('\n'); return }
        }
        if (seen.put(o!!, true) != null) { out.append(pad).append("<seen ").append(o.javaClass.name).append(">\n"); return }
        when (o) {
            // Facebook has collections whose iterator() throws: fall back to fields.
            is Collection<*> -> if (runCatching {
                    out.append(pad).append("list[").append(o.size).append("]\n")
                    o.forEach { dump(it, depth + 1, out, seen, budget) }
                }.isSuccess) return
            is Map<*, *> -> if (runCatching {
                    out.append(pad).append("map[").append(o.size).append("]\n")
                    o.forEach { (k, v) ->
                        out.append(pad).append(" key ").append(runCatching { k.toString().take(120) }.getOrDefault("?")).append('\n')
                        dump(v, depth + 1, out, seen, budget)
                    }
                }.isSuccess) return
            is Array<*> -> {
                out.append(pad).append("array[").append(o.size).append("]\n")
                o.forEach { dump(it, depth + 1, out, seen, budget) }
                return
            }
            is IntArray -> { out.append(pad).append("int[]").append(o.take(400)).append('\n'); return }
            is LongArray -> { out.append(pad).append("long[]").append(o.take(20)).append('\n'); return }
        }
        val cls = o.javaClass
        if (cls.name.startsWith("android.") || cls.name.startsWith("java.") && cls.name != "java.lang.Object") {
            out.append(pad).append("<").append(cls.name).append(">\n")
            return
        }
        out.append(pad).append(cls.name).append('\n')
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = runCatching { f.get(o) }.getOrNull()
                out.append(pad).append(" .").append(f.name).append(":\n")
                dump(v, depth + 1, out, seen, budget)
            }
            c = c.superclass
        }
    }
}
