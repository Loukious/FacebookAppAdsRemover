package tn.loukious.facebookappadsremover.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Hides side-menu entries in the list-style (Bloks) menu.
 *
 * The menu is a Bloks screen. Its server response (fresh or from the disk
 * cache) reaches Java as `RawComponentPayload.Json(text)`, and the Bloks parser
 * reads it through `tokenizer()`. The class name is stable (the class is
 * Serializable). The hook hands the parser a copy whose text has the hidden
 * rows cut out ([BloksMenuFilter]); the original object, and so Facebook's
 * disk cache, stays untouched.
 *
 * Built to fail open: whenever something about the class or the response is
 * not as expected, Facebook gets its own data unchanged and a line in the log
 * says why hiding is off.
 */
object MenuResponseHook {
    private const val TAG = "FBAR.Menu"
    private const val JSON_PAYLOAD = "com.instagram.common.bloks.payload.RawComponentPayload\$Json"

    @Volatile private var installed = false
    @Volatile private var gaveUp = false
    private lateinit var textField: Field
    private lateinit var ctor: Constructor<*>

    /** Set while the parser reads a copy this hook made: nothing left to do for it. */
    private val inCopy = ThreadLocal<Boolean>()
    @Volatile private var warnedFormat = false

    /** Returns true when there is nothing more to try (installed, or the class is not what we know). */
    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (installed || gaveUp) return true
        // Not there yet: Facebook attaches its secondary dexes a little after start.
        val cls = runCatching { Class.forName(JSON_PAYLOAD, false, classLoader) }.getOrNull() ?: return false
        runCatching {
            textField = cls.declaredFields.single { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
                .apply { isAccessible = true }
            ctor = cls.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
            val tokenizer = tokenizerOf(cls).apply { isAccessible = true }
            module.hook(tokenizer).intercept(object : Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (inCopy.get() == true) return chain.proceed()
                    val copy = try { filteredCopy(chain.thisObject) } catch (t: Throwable) {
                        L.w(TAG, "menu response filter failed", t); null
                    } ?: return chain.proceed()
                    inCopy.set(true)
                    try {
                        return tokenizer.invoke(copy)
                    } catch (t: Throwable) {
                        // Whatever went wrong with the copy, the original still works.
                        L.w(TAG, "menu response copy rejected", t)
                        return chain.proceed()
                    } finally {
                        inCopy.set(false)
                    }
                }
            })
            installed = true
            L.i(TAG, "menu response hook installed (${tokenizer.name})")
        }.onFailure {
            gaveUp = true
            L.w(TAG, "menu response hook unavailable: list-style menu entries stay visible", it)
        }
        return true
    }

    /**
     * The method the parser reads the payload through. Normally `tokenizer`;
     * if it is ever renamed, it is still the only no-argument instance method
     * that returns an object of Facebook's own (not a String, not the payload).
     */
    private fun tokenizerOf(cls: Class<*>): Method {
        val candidates = cls.declaredMethods.filter {
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }
        return candidates.firstOrNull { it.name == "tokenizer" }
            ?: candidates.single {
                !it.returnType.isPrimitive && it.returnType != String::class.java &&
                    !it.returnType.isAssignableFrom(cls) && !cls.isAssignableFrom(it.returnType)
            }
    }

    /** A payload without the hidden rows, or null when [payload] needs no change. */
    private fun filteredCopy(payload: Any?): Any? {
        val text = payload?.let { textField.get(it) } as? String ?: return null
        val hidden = { id: Long -> Settings.getBoolean(MenuHideHook.key(id), false) }
        val out = BloksMenuFilter.filter(text, BloksMenuFilter.Rules(
            bookmark = hidden,
            section = { key -> Settings.getBoolean(Settings.MENU_SECTION_PREFIX + key, false) },
            expand = Settings.getBoolean(Settings.MENU_EXPAND, false),
        ))
        if (out === text) {
            if (!warnedFormat && BloksMenuFilter.namesHidden(text, hidden)) {
                warnedFormat = true
                L.w(TAG, "menu response names hidden entries but no row matched: response format changed?")
            }
            return null
        }
        L.i(TAG, "menu response: ${text.length} -> ${out.length} chars")
        return ctor.newInstance(out)
    }
}
