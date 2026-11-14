package tn.loukious.facebookappadsremover.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * Hides side-menu entries in the list-style (Bloks) menu.
 *
 * The menu is a Bloks screen. Its server response (fresh or from the disk
 * cache) reaches Java as `RawComponentPayload.Json(text)`, and the Bloks parser
 * reads it through `tokenizer()`. Both names are stable: the class is
 * Serializable. The hook hands the parser a copy whose text has the hidden
 * rows cut out ([BloksMenuFilter]); the original object, and so Facebook's
 * disk cache, stays untouched.
 */
object MenuResponseHook {
    private const val TAG = "FBAR.Menu"
    private const val JSON_PAYLOAD = "com.instagram.common.bloks.payload.RawComponentPayload\$Json"

    @Volatile private var installed = false
    private lateinit var textField: Field
    private lateinit var ctor: Constructor<*>

    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (installed) return true
        val cls = runCatching { Class.forName(JSON_PAYLOAD, false, classLoader) }.getOrNull() ?: return false
        runCatching {
            textField = cls.declaredFields.single { it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .apply { isAccessible = true }
            ctor = cls.getDeclaredConstructor(String::class.java).apply { isAccessible = true }
            val tokenizer = cls.declaredMethods.single { it.name == "tokenizer" && it.parameterCount == 0 }
                .apply { isAccessible = true }
            module.hook(tokenizer).intercept(object : Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val copy = runCatching { filteredCopy(chain.thisObject) }
                        .onFailure { L.w(TAG, "menu response filter failed", it) }.getOrNull()
                        ?: return chain.proceed()
                    return chain.executable.let { it as java.lang.reflect.Method }.invoke(copy)
                }
            })
            installed = true
            L.i(TAG, "menu response hook installed")
        }.onFailure { L.w(TAG, "menu response hook failed", it) }
        return installed
    }

    /**
     * A payload without the hidden rows, or null when [payload] needs no change
     * (which is also what the copy itself gets when its tokenizer is called).
     */
    private fun filteredCopy(payload: Any?): Any? {
        val text = payload?.let { textField.get(it) } as? String ?: return null
        val out = BloksMenuFilter.filter(text) { id -> Settings.getBoolean(MenuHideHook.key(id), false) }
        if (out === text) return null
        L.i(TAG, "menu response: ${text.length} -> ${out.length} chars")
        return ctor.newInstance(out)
    }
}
