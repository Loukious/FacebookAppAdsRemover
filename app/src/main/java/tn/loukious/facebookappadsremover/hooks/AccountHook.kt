package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import android.content.SharedPreferences
import tn.loukious.facebookappadsremover.core.L
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.json.JSONArray
import java.lang.reflect.Field
import java.lang.reflect.Modifier

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
 * The original mod waited for cookies, token and user ID before unhooking.
 * Some Facebook builds never provide cookies, so this port finishes after
 * capturing token/user ID and treats cookies and username as best-effort.
 * Unchanged values produce no preference writes or update logs.
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

    private lateinit var prefs: SharedPreferences

    /** The hooked equals() methods — unhooked once the capture is complete. */
    private val hookHandles = ArrayList<XposedInterface.HookHandle>()

    private val captureState = AccountCaptureState(
        persist = { changed ->
            runCatching {
                val editor = prefs.edit()
                for ((key, value) in changed) editor.putString(key, value)
                editor.apply()
            }.onFailure { L.w(TAG, "account preference update failed", it) }
        },
        log = { L.i(TAG, it) },
        warn = { L.w(TAG, it) },
        decodeCookies = ::jsonToHttpCookie,
        onComplete = {
            for (handle in hookHandles) {
                runCatching { handle.unhook() }
                    .onFailure { L.w(TAG, "Could not detach account hook; capture is disabled", it) }
            }
            hookHandles.clear()
        },
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Resolves ViewerContext through the app classloader and hooks its
     * instance equals() — the mod's exact installer. Idempotent: returns
     * false quietly while the secondary dexes are not attached yet, so the
     * caller can retry on the same cadence as the discovery probe.
     */
    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (captureState.completed || hookHandles.isNotEmpty()) return true
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

        val fields = HashMap<String, Field>(4)
        for (name in listOf("mSessionCookiesString", "mAuthToken", "mUsername", "mUserId")) {
            runCatching {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                fields[name] = f
            }.onFailure { L.w(TAG, "field missing: $name", it) }
        }

        // Publish fields before enabling any callback. Installation and
        // capture share a lock so completion cannot race handle registration.
        this.fields = fields
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

    private var fields: Map<String, Field> = emptyMap()

    /**
     * bsrncODWYJ5TNENFG95a.beforeHookedMethod port: observe, capture, never
     * alter the original result.
     */
    private object CaptureHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            runCatching { capture(chain.thisObject) }
                .onFailure { L.w(TAG, "capture failed", it) }
            return chain.proceed()
        }
    }

    /** Capture changed fields and detach once the required credentials exist. */
    @Synchronized
    private fun capture(viewerContext: Any?) {
        if (viewerContext == null || captureState.completed) return
        captureState.capture(AccountCaptureState.Snapshot(
            token = stringField(viewerContext, "mAuthToken"),
            userId = stringField(viewerContext, "mUserId"),
            username = stringField(viewerContext, "mUsername"),
            cookies = stringField(viewerContext, "mSessionCookiesString"),
        ))
    }

    /**
     * The captured Graph user token, for repost/page calls — in-memory static
     * first, pref fallback (mod: pO4TjBxQp2IFO1UyrtSF.oA72A8FsG9IKF8BK7Lb,
     * libnc.so.c 1319475–1319736 — static GhTa.UuCRUjLnvPR7SP3GNGo, then the
     * FBTOKEN pref). Blank/null = "no token" to callers.
     */
    fun authToken(): String? =
        captureState.authToken?.takeIf { it.isNotBlank() }
            ?: if (this::prefs.isInitialized) {
                runCatching { prefs.getString(KEY_AUTH_TOKEN, "") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
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

    private fun stringField(obj: Any, name: String): String? = runCatching {
        val f = fields[name] ?: return@runCatching null
        (f.get(obj) as? String)
    }.getOrNull()

}
