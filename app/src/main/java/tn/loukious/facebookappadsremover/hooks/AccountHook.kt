package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import android.content.SharedPreferences
import tn.loukious.facebookappadsremover.core.L
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.json.JSONArray
import java.io.File
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
 * The original mod unhooked after its first capture. This port keeps the
 * (sampled) hook so it follows account switches and token refreshes, stores
 * token and user ID only as a pair (see AccountCaptureState), skips page
 * contexts, and clears everything once Facebook's session file is gone
 * (logout). Cookies and username are best-effort; unchanged values produce
 * no preference writes or update logs.
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

    /**
     * The hooked equals() methods. They stay installed for the process
     * lifetime so account switches and token refreshes are picked up; the
     * callback samples at most once per [SAMPLE_INTERVAL_MS].
     */
    private val hookHandles = ArrayList<XposedInterface.HookHandle>()

    private const val SAMPLE_INTERVAL_MS = 2_000L
    private const val LOGOUT_CHECK_INTERVAL_MS = 30_000L
    @Volatile private var lastSample = 0L
    @Volatile private var lastLogoutCheck = 0L

    /** Facebook's active-session file; missing or empty means logged out. */
    private var authFile: File? = null

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

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        authFile = runCatching {
            File(context.dataDir, "app_light_prefs").walkTopDown().maxDepth(2)
                .firstOrNull { it.isFile && it.name == "authentication" }
        }.getOrNull()
        // Credentials captured before a logout that happened while the
        // process was dead must not survive into this session.
        clearIfLoggedOut(force = true)
    }

    private fun clearIfLoggedOut(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLogoutCheck < LOGOUT_CHECK_INTERVAL_MS) return
        lastLogoutCheck = now
        val loggedOut = authFile?.let { !it.isFile || it.length() == 0L }
            // Never seen a session file: nothing to compare against.
            ?: return
        if (!loggedOut) return
        captureState.clear()
        if (this::prefs.isInitialized) {
            // Also drop values persisted by an earlier process.
            runCatching { prefs.edit().clear().apply() }
        }
    }

    /**
     * Resolves ViewerContext through the app classloader and hooks its
     * instance equals() — the mod's exact installer. Idempotent: returns
     * false quietly while the secondary dexes are not attached yet, so the
     * caller can retry on the same cadence as the discovery probe.
     */
    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader): Boolean {
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

        val fields = HashMap<String, Field>(4)
        for (name in listOf("mSessionCookiesString", "mAuthToken", "mUsername", "mUserId")) {
            runCatching {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                fields[name] = f
            }.onFailure { L.w(TAG, "field missing: $name", it) }
        }
        // Optional: page ("act as page") contexts carry the page's ID/token
        // and must not replace the user's account.
        runCatching {
            cls.getDeclaredField(PAGE_CONTEXT_FIELD).also { it.isAccessible = true }
        }.onSuccess { fields[PAGE_CONTEXT_FIELD] = it }
            .onFailure { L.w(TAG, "no $PAGE_CONTEXT_FIELD; page contexts can't be filtered") }

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
            // equals() runs constantly; sampling keeps the hook cheap while
            // still following account switches within a couple of seconds.
            val now = System.currentTimeMillis()
            if (now - lastSample >= SAMPLE_INTERVAL_MS) {
                lastSample = now
                runCatching { capture(chain.thisObject) }
                    .onFailure { L.w(TAG, "capture failed", it) }
            }
            return chain.proceed()
        }
    }

    private const val PAGE_CONTEXT_FIELD = "mIsPageContext"

    /** Capture changed fields of the signed-in user's ViewerContext. */
    @Synchronized
    private fun capture(viewerContext: Any?) {
        clearIfLoggedOut()
        if (viewerContext == null) return
        val isPage = fields[PAGE_CONTEXT_FIELD]?.let { runCatching { it.getBoolean(viewerContext) }.getOrNull() }
        if (isPage == true) return
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
