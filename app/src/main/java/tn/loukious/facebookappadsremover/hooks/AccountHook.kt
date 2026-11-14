package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import android.content.SharedPreferences
import tn.loukious.facebookappadsremover.core.L
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.json.JSONArray
import java.lang.reflect.Modifier
import android.os.SystemClock

/**
 * M3 account tools — port of the original mod's ViewerContext installer
 * (GhTaOHdq4kOQbdazOJvf.qsJGnbanr07zUGNprRk, libnc.so.c 123432–123752, with
 * the capture callback at 108048–109500).
 *
 * The mod's session-read mechanism (fully reversed):
 *
 *     class = XposedHelpers.findClass("com.facebook.auth.viewercontext.ViewerContext", cl)
 *     for (m : class.getDeclaredMethods())
 *         if (!abstract && !static && m.name.equals("equals"))   // DAT_00fd9f18 = "equals"
 *             XposedBridge.hookMethod(m, GhTa$bsrncODWYJ5TNENFG95a)
 *
 * The callback observes every ViewerContext.equals() call (FB invokes it
 * constantly while building requests) and captures four fields off the
 * instance via XposedHelpers.getObjectField, storing each into encrypted
 * prefs through P9vfxZIhzRWfAHHtpfTQ.JdPl0A614BiPGbCvPPk:
 *
 *   mSessionCookiesString → FBFULLCOOKIE   (the JSON cookie array, verbatim;
 *                                           \n/\r/\t sanitized to spaces)
 *   new JSONArray(...)    → FBHTTPCOOKIE   via S7VA.XS11PXyJIvbyHBPk0TZ:
 *                                           for each cookie object,
 *                                           name + "=" + value, joined by "; "
 *   mAuthToken            → FBTOKEN
 *   mUsername             → FBUSERNAME     (pref only — no in-memory static)
 *   mUserId               → FBUSERID
 *
 * The sampled equals() hook is only a wake-up signal. Credentials come from
 * an atomic snapshot of Facebook's current authentication store, fetched
 * through its factory each time; retained ViewerContexts and replaced store
 * objects cannot restore an old account. No persisted credentials are used
 * until the current session is verified. See [AuthenticationStoreSource].
 *
 * Nothing here needs DexKit: ViewerContext is a stable com.facebook.* class
 * in the secondary dex (verified present with identical field names in stock
 * 576.0.0.42.73), so the hook installs as soon as the secondary dexes attach
 * to the app classloader.
 */
object AccountHook {

    private const val TAG = "FBAR.Account"

    /** The module's own prefs file in the FB process. */
    private const val PREFS_NAME = "fbar_account"

    // Pref keys — the mod's app.telegram.bemai3012_* keys in parentheses.
    const val KEY_USER_ID = AccountCaptureState.KEY_USER_ID           // FBUSERID
    const val KEY_USERNAME = AccountCaptureState.KEY_USERNAME         // FBUSERNAME
    const val KEY_AUTH_TOKEN = AccountCaptureState.KEY_AUTH_TOKEN     // FBTOKEN
    const val KEY_COOKIE_JSON = AccountCaptureState.KEY_COOKIE_JSON   // FBFULLCOOKIE
    const val KEY_COOKIE_HTTP = AccountCaptureState.KEY_COOKIE_HTTP   // FBHTTPCOOKIE

    /** Stable secondary-dex class name (no discovery cache needed). */
    const val VIEWER_CONTEXT_CLASS = "com.facebook.auth.viewercontext.ViewerContext"
    private const val LIGHT_PREFS_CLASS = "com.facebook.crudolib.prefs.LightSharedPreferencesImpl"

    private lateinit var prefs: SharedPreferences

    /**
     * The hooked equals() methods. They stay installed for the process
     * lifetime so account switches and token refreshes are picked up; the
     * callback samples at most once per [SAMPLE_INTERVAL_MS].
     */
    private val hookHandles = ArrayList<XposedInterface.HookHandle>()
    private val sourceHookHandles = ArrayList<XposedInterface.HookHandle>()
    private val sessionSource = AuthenticationStoreSource()

    private const val SAMPLE_INTERVAL_MS = 2_000L
    @Volatile private var lastSample: Long? = null

    private val captureState = AccountCaptureState(
        persist = { changed ->
            runCatching {
                val editor = prefs.edit()
                for ((key, value) in changed) {
                    if (value == null) editor.remove(key) else editor.putString(key, value)
                }
                editor.apply()
            }.onFailure { L.w(TAG, "account preference update failed", it) }
        },
        log = { L.i(TAG, it) },
        warn = { L.w(TAG, it) },
        decodeCookies = ::jsonToHttpCookie,
    )

    @Synchronized
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // A previous process's credentials are unverified, including when
        // authentication never existed or disappeared while the app was dead.
        captureState.clear()
        refreshSession()
    }

    /**
     * Install on the bootstrap class before Application.onCreate so the
     * authentication factory is observed even if Facebook caches its context
     * during startup. The getString hook also permits a later installation.
     */
    @Synchronized
    fun installSessionSource(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (sourceHookHandles.isNotEmpty()) return true
        val cls = runCatching { Class.forName(LIGHT_PREFS_CLASS, false, classLoader) }
            .getOrNull() ?: return false
        val nameField = runCatching { cls.getDeclaredField("_name").apply { isAccessible = true } }
            .getOrNull() ?: return false

        // The Provider captures the factory whose String -> preferences
        // getter returns its canonical entry, including replacements.
        for (constructor in cls.declaredConstructors) {
            val providerIndex = constructor.parameterTypes.indexOfFirst { it.name == "javax.inject.Provider" }
            if (providerIndex < 0) continue
            runCatching {
                constructor.isAccessible = true
                sourceHookHandles.add(module.hook(constructor).intercept(object : Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        runCatching {
                            if (nameField.get(chain.thisObject) == "authentication") {
                                chain.args.getOrNull(providerIndex)?.let { sessionSource.observe(it, cls) }
                            }
                        }
                        return result
                    }
                }))
            }.onFailure { L.w(TAG, "authentication constructor hook failed", it) }
        }
        runCatching {
            val getter = cls.getDeclaredMethod("getString", String::class.java, String::class.java)
            sourceHookHandles.add(module.hook(getter).intercept(object : Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    runCatching {
                        val store = chain.thisObject
                        if (store != null && nameField.get(store) == "authentication") {
                            sessionSource.observeStore(store)
                        }
                    }
                    return result
                }
            }))
        }.onFailure { L.w(TAG, "authentication getter hook failed", it) }
        if (sourceHookHandles.isEmpty()) return false
        L.i(TAG, "authentication store observer installed")
        return true
    }

    /**
     * Resolves ViewerContext through the app classloader and hooks its
     * instance equals() — the mod's exact installer. Idempotent: returns
     * false quietly while the secondary dexes are not attached yet, so the
     * caller can retry on the same cadence as the discovery probe.
     */
    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (!installSessionSource(module, classLoader)) return false
        if (hookHandles.isNotEmpty()) return true
        val cls = runCatching {
            Class.forName(VIEWER_CONTEXT_CLASS, false, classLoader)
        }.getOrNull() ?: return false

        // Mod: getDeclaredMethods(), skip abstract, skip static, name == "equals".
        val targets = cls.declaredMethods.filter {
            !Modifier.isAbstract(it.modifiers) &&
                !Modifier.isStatic(it.modifiers) &&
                it.name == "equals"
        }
        if (targets.isEmpty()) {
            L.w(TAG, "no instance equals() on $VIEWER_CONTEXT_CLASS — class shape changed?")
            return false
        }

        var installed = 0
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                val handle = module.hook(m).intercept(CaptureHooker)
                hookHandles.add(handle)
                installed++
                L.i(TAG, "hooked ${cls.name}.${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }.onFailure { L.w(TAG, "hook failed on ${cls.name}.${m.name}", it) }
        }
        if (installed == 0) return false
        return true
    }

    /**
     * bsrncODWYJ5TNENFG95a.beforeHookedMethod port: observe, capture, never
     * alter the original result.
     */
    private object CaptureHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            // equals() runs constantly; sampling keeps the hook cheap while
            // still following account switches within a couple of seconds.
            val now = SystemClock.elapsedRealtime()
            val last = lastSample
            if (last == null || now - last >= SAMPLE_INTERVAL_MS) {
                runCatching { sampleSession(now) }
                    .onFailure { L.w(TAG, "capture failed", it) }
            }
            return chain.proceed()
        }
    }

    /** Serialize sampling with the fresh authority read and persistence. */
    @Synchronized
    private fun sampleSession(now: Long) {
        if (!this::prefs.isInitialized) return
        if (lastSample?.let { now - it < SAMPLE_INTERVAL_MS } == true) return
        lastSample = now
        refreshSession()
    }

    private fun refreshSession(): Boolean {
        if (!this::prefs.isInitialized) return false
        val current = sessionSource.read()
        captureState.capture(current.snapshot, current.session)
        return current.session is AccountCaptureState.Session.SignedIn
    }

    /** Recheck authority at use time; never fall back to unverified prefs. */
    @Synchronized
    fun authToken(): String? = if (refreshSession()) captureState.authToken else null

    /** Downloads must also stop using cached cookies after logout. */
    @Synchronized
    fun httpCookie(): String? = if (refreshSession()) {
        prefs.getString(KEY_COOKIE_HTTP, null)?.takeIf { it.isNotBlank() }
    } else null

    /**
     * S7VA.XS11PXyJIvbyHBPk0TZ(JSONArray) port (libnc.so.c 684239–684517):
     * for each cookie object take `name` and `value` (DAT_0105aed8/af18) and
     * join them as `name=value` separated by "; " (DAT_0105af98/af58) — a
     * standard HTTP Cookie header.
     */
    fun jsonToHttpCookie(json: String): String? = runCatching {
        val arr = JSONArray(json)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            if (i != 0) sb.append("; ")
            sb.append(c.getString("name")).append('=').append(c.getString("value"))
        }
        sb.toString().takeIf { it.isNotEmpty() }
    }.getOrNull()

}
