package tn.loukious.facebookappadsremover.core

import android.content.Context
import android.os.Build
import tn.loukious.facebookappadsremover.BuildConfig
import java.lang.reflect.Method

/**
 * Persistent discovery cache — the same trick the original mod used:
 * a DexKit/dexplore scan over Facebook's ~148MB secondary-dex archive takes
 * minutes on every cold start, and during that window NO hooks are installed
 * (ads slip straight through). The mod cached its dex-search results in
 * SharedPreferences and re-scanned only when the app version changed; this
 * does the same, so after the first launch of a given FB build all hooks
 * install within ~1s of the secondary dexes attaching to the classloader.
 */
object MethodCache {

    private const val PREFS = "fbar_discovery_cache"
    private const val KEY_VERSION = "version"
    /** Bump when resolver semantics/serialized cache contracts change, even
     * if the developer keeps the module's public versionCode unchanged.
     */
    private const val KEY_SCHEMA = "schema"
    private const val KEY_MODULE_VERSION = "moduleVersion"
    private const val CACHE_SCHEMA = 4 // Removed shopping guard + split ad families.
    private const val KEY_TARGETS = "targets" // "key=Class#m:p,p;Class#m:p;\n..." per line
    private const val KEY_CLASS_PREFIX = "class:"

    /** Cached hook methods for the current FB version, or null on miss. */
    fun loadMethods(context: Context, classLoader: ClassLoader): Map<String, List<Method>>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostVersion = versionCode(context)
        if (hostVersion <= 0 || prefs.getInt(KEY_VERSION, -1) != hostVersion) return null
        if (prefs.getInt(KEY_SCHEMA, -1) != CACHE_SCHEMA) return null
        if (prefs.getInt(KEY_MODULE_VERSION, -1) != BuildConfig.VERSION_CODE) return null
        val all = prefs.getString(KEY_TARGETS, null) ?: return null
        val out = mutableMapOf<String, List<Method>>()
        for (line in all.split('\n')) {
            if (line.isBlank()) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val methods = mutableListOf<Method>()
            for (spec in line.substring(idx + 1).split(';')) {
                if (spec.isBlank()) continue
                val method = resolveMethod(spec, classLoader)
                if (method == null) {
                    // An obfuscated name/parameter change must not silently
                    // degrade a 4-method hook family to an unrelated subset.
                    android.util.Log.w("FBAR.Cache", "Stale cached method: " +
                        line.substring(0, idx) + " (forcing rediscovery)")
                    return null
                }
                methods.add(method)
            }
            if (methods.isNotEmpty()) out[line.substring(0, idx)] = methods
        }
        return if (out.isEmpty()) null else out
    }

    /** Cached auxiliary class name (e.g. the newsfeed filter Runnable). */
    fun loadClass(context: Context, key: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostVersion = versionCode(context)
        if (hostVersion <= 0 || prefs.getInt(KEY_VERSION, -1) != hostVersion) return null
        if (prefs.getInt(KEY_SCHEMA, -1) != CACHE_SCHEMA) return null
        if (prefs.getInt(KEY_MODULE_VERSION, -1) != BuildConfig.VERSION_CODE) return null
        return prefs.getString(KEY_CLASS_PREFIX + key, null)?.takeIf { it.isNotBlank() }
    }

    /** Drop only a failed hook's auxiliary state; keep other cached targets. */
    fun invalidateClass(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_CLASS_PREFIX + key).apply()
    }

    fun store(context: Context, methods: Map<String, List<Method>>, classes: Map<String, String>) {
        val hostVersion = versionCode(context)
        if (hostVersion <= 0) {
            android.util.Log.w("FBAR.Cache", "Unknown Facebook version; skipping cache write")
            return
        }
        val sb = StringBuilder()
        for ((key, list) in methods) {
            if (list.isEmpty()) continue
            sb.append(key).append('=')
            for (m in list) {
                sb.append(m.declaringClass.name).append('#').append(m.name)
                sb.append(':').append(m.parameterTypes.joinToString(",") { it.name })
                sb.append(';')
            }
            sb.append('\n')
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            // A complete discovery snapshot replaces any obsolete role keys.
            // Partial overwrites previously let retired class:* keys survive.
            .clear()
            .putInt(KEY_VERSION, hostVersion)
            .putInt(KEY_SCHEMA, CACHE_SCHEMA)
            .putInt(KEY_MODULE_VERSION, BuildConfig.VERSION_CODE)
            .putString(KEY_TARGETS, sb.toString())
        for ((k, v) in classes) editor.putString(KEY_CLASS_PREFIX + k, v)
        editor.apply()
    }

    /** "Class#name:param,param" -> Method via the app classloader. */
    private fun resolveMethod(spec: String, cl: ClassLoader): Method? = runCatching {
        val hash = spec.indexOf('#')
        val colon = spec.indexOf(':', hash)
        if (hash <= 0 || colon < 0) return@runCatching null
        val cls = Class.forName(spec.substring(0, hash), false, cl)
        val name = spec.substring(hash + 1, colon)
        val paramNames = spec.substring(colon + 1).split(',').filter { it.isNotBlank() }
        val params = paramNames.mapNotNull { resolveType(it, cl) }
        if (params.size != paramNames.size) return@runCatching null
        cls.getDeclaredMethod(name, *params.toTypedArray())
    }.getOrNull()

    private fun resolveType(name: String, cl: ClassLoader): Class<*>? = when (name) {
        "void" -> java.lang.Void.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        "byte" -> java.lang.Byte.TYPE
        "short" -> java.lang.Short.TYPE
        "int" -> java.lang.Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        "double" -> java.lang.Double.TYPE
        "char" -> java.lang.Character.TYPE
        else -> runCatching { Class.forName(name, false, cl) }.getOrNull()
    }

    private fun versionCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(-1)
}
