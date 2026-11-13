package tn.loukious.facebookappadsremover.core

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import org.json.JSONObject
import tn.loukious.facebookappadsremover.hooks.AccountHook
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session export/import — port of the original mod's ShareAccounts feature
 * (native class cLYrLXHZXc92RKg5r98A, fully reversed):
 *
 * The mod copied Facebook's two live session files — named exactly
 * "authentication" and "logged_in" (TLS-decrypted from EQRQ, DAT_010b4f58 /
 * DAT_010b4f98) — into named account dirs under a shared-storage folder of
 * its own (getShareAccountsDir, DAT_010b5918), staged through a ".ShareAccountsTemp"
 * marker dir (JAvH), with a nullish "null" guard (Rn0), sort-by-name
 * comparator and UI-thread toasts. Import copied them back over FB's session
 * store.
 *
 * Stock 576 moved past that pair: "authentication" survives, but "logged_in"
 * split per-account (logged_in_<uid> profile data + dbl_local_auth_<uid>
 * credentials), plus an app_config_experiment/logged_in_user_ids registry
 * listing every signed-in uid. A working restore needs the whole set, so the
 * export captures every session-named file under FB's data dir, keyed by its
 * data-dir-relative path.
 *
 * The port also drops the shared-storage dir — Android 11+ scoped storage
 * means FB cannot write arbitrary /sdcard paths. Instead the FB process
 * round-trips a single JSON through MediaStore Downloads
 * (Download/FacebookAppAdsRemover/): an app can always read back the files
 * it contributed, so no permissions are needed in either direction. The JSON
 * additionally carries the captured ViewerContext session (AccountHook's
 * fbar_account store) for reference.
 *
 * Triggered by broadcasts from the module app because the FB process is the
 * only side that can reach both the session files and the captured cookies.
 */
object SessionBackup {

    private const val TAG = "FBAR.Session"

    /** Broadcast actions the module app sends (target com.facebook.katana). */
    const val ACTION_EXPORT = "tn.loukious.facebookappadsremover.action.SESSION_EXPORT"
    const val ACTION_IMPORT = "tn.loukious.facebookappadsremover.action.SESSION_IMPORT"

    /**
     * Import a caller-supplied export (the module app's file browser): the
     * picked JSON travels in the broadcast extra [EXTRA_DATA] instead of
     * being re-read from MediaStore — the FB process cannot open another
     * app's content URIs without a grant, and the payload (~15 KB) is far
     * under the 1 MB binder transaction cap.
     */
    const val ACTION_IMPORT_DATA = "tn.loukious.facebookappadsremover.action.SESSION_IMPORT_DATA"
    const val EXTRA_DATA = "data"

    /** Signature permission (declared by the module app) required to send the actions above. */
    const val SENDER_PERMISSION = "tn.loukious.facebookappadsremover.permission.SESSION_BACKUP"

    /**
     * Result action sent back to the module app (foreground while the buttons
     * are pressed, so its toasts actually display — FB is backgrounded and
     * Android 11+ suppresses background toasts).
     */
    const val ACTION_RESULT = "tn.loukious.facebookappadsremover.action.SESSION_RESULT"
    const val EXTRA_OK = "ok"
    const val EXTRA_MESSAGE = "message"

    /** Export file naming — FBAR-Session-<username>-<timestamp>.json. */
    const val EXPORT_PREFIX = "FBAR-Session-"
    private const val EXPORT_DIR = "Download/FacebookAppAdsRemover"

    /**
     * Session-file name predicates (stock 576 layout, verified on-device):
     * app_light_prefs/.../authentication          — active token + uid
     * app_light_prefs/.../logged_in_<uid>         — per-account profile data
     * app_light_prefs/.../dbl_local_auth_<uid>    — per-account credentials
     * app_config_experiment/logged_in_user_ids    — registry of signed-in uids
     */

    /** Session-file search bound: FB's data dir is deep, but not that deep. */
    private const val MAX_WALK_DEPTH = 6

    /** Per-request token from the module app, echoed in [ACTION_RESULT]. */
    const val EXTRA_NONCE = "nonce"
    private val requestNonce = ThreadLocal<String?>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val app = ctx.applicationContext
            // Echoed back with the result so the module app only shows replies
            // to requests it actually sent.
            val nonce = intent.getStringExtra(EXTRA_NONCE)
            fun run(name: String, work: () -> Unit) = Thread {
                requestNonce.set(nonce)
                work()
            }.apply { this.name = name }.start()
            when (intent.action) {
                ACTION_EXPORT -> run("fbar-session-export") { exportSession(app) }
                ACTION_IMPORT -> run("fbar-session-import") { importLatest(app) }
                ACTION_IMPORT_DATA -> run("fbar-session-import") {
                    importData(app, intent.getStringExtra(EXTRA_DATA))
                }
            }
        }
    }

    /** Registers the actions — call from onAppCreated (FB process). */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(context: Context) {
        runCatching {
            val filter = IntentFilter(ACTION_EXPORT).apply {
                addAction(ACTION_IMPORT)
                addAction(ACTION_IMPORT_DATA)
            }
            // The receiver lives in Facebook's process and can read or overwrite
            // its session: only senders holding the module's signature
            // permission (i.e. the module app itself) may trigger it.
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, SENDER_PERMISSION, null)
            }
            L.i(TAG, "session backup receiver registered")
        }.onFailure { L.e(TAG, "session backup receiver registration failed", it) }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private fun exportSession(context: Context) {
        // Every session-named file must be captured before anything is written.
        val files = findSessionFiles(context)
        if (files.keys.none { it == AUTH_KEY || it.endsWith("/$AUTH_KEY") }) {
            L.e(TAG, "session files not found: ${files.keys.joinToString()}")
            toast(context, false, "Couldn't find Facebook's session files — are you logged in?")
            return
        }

        val account = context.getSharedPreferences("fbar_account", Context.MODE_PRIVATE)
        val username = account.getString(AccountHook.KEY_USERNAME, null)?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "$EXPORT_PREFIX${username ?: "account"}-$stamp.json"

        val payload = JSONObject()
        for ((rel, file) in files) {
            payload.put(rel, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }

        val json = JSONObject()
            .put("format", 2)
            .put("exported_at", System.currentTimeMillis())
            .put("user_id", account.getString(AccountHook.KEY_USER_ID, null) ?: JSONObject.NULL)
            .put("username", account.getString(AccountHook.KEY_USERNAME, null) ?: JSONObject.NULL)
            // The captured token/cookies are not written: import never reads
            // them, and a file in shared Downloads should not carry more
            // credentials than the session files themselves.
            // relative-path -> base64 content; import writes each back verbatim.
            .put("files", payload)

        val ok = writeMediaStore(context, name, json.toString().toByteArray())
        if (ok) {
            L.i(TAG, "session exported: $name (${files.size} files: ${files.keys.joinToString()})")
            toast(context, true, "Session exported to Download/FacebookAppAdsRemover/$name")
        } else {
            toast(context, false, "Export failed — check the module log")
        }
    }

    // ------------------------------------------------------------------
    // Import (most recent export, or a caller-supplied one)
    // ------------------------------------------------------------------

    private fun importLatest(context: Context) {
        val uri = latestExport(context)
        if (uri == null) {
            toast(context, false, "No export found in Download/FacebookAppAdsRemover")
            return
        }
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text == null) {
            L.e(TAG, "export read failed: $uri")
            toast(context, false, "Couldn't read the export file")
            return
        }
        doImport(context, text, uri.lastPathSegment ?: "latest")
    }

    private fun importData(context: Context, text: String?) {
        if (text.isNullOrBlank()) {
            toast(context, false, "No session data received")
            return
        }
        doImport(context, text, "picked file")
    }

    private fun doImport(context: Context, text: String, label: String) {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val files = json?.optJSONObject("files")
        if (json == null || files == null || files.length() == 0) {
            toast(context, false, "Not a valid session export")
            return
        }

        // Write every recorded file back to its recorded data-dir-relative
        // path. A path whose parent dir no longer exists (FB layout change
        // between export and import) is skipped, not fatal.
        val dataDir = context.dataDir
        var written = 0
        val keys = files.keys()
        while (keys.hasNext()) {
            val rel = keys.next()
            val target = SessionPaths.sessionTarget(dataDir, rel)
            if (target == null) {
                L.e(TAG, "import skip (not a session file path): $rel")
                continue
            }
            if (target.parentFile?.exists() != true) {
                L.e(TAG, "import skip (no parent dir): $rel")
                continue
            }
            val bytes = runCatching { Base64.decode(files.getString(rel), Base64.NO_WRAP) }.getOrNull()
            if (bytes == null || !atomicWrite(target, bytes)) {
                L.e(TAG, "import write failed: $rel")
            } else {
                written++
            }
        }

        if (written > 0) {
            L.i(TAG, "session imported: $label ($written/${files.length()} files)")
            toast(context, true, "Session restored ($written files) — force-stop Facebook and reopen")
        } else {
            L.e(TAG, "session import wrote nothing")
            toast(context, false, "Import failed — check the module log")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Relative path of the authentication file under FB's data dir. */
    private const val AUTH_KEY = "authentication"

    /**
     * Every session-named file under FB's data dir, as relative-path → File.
     * walkTopDown's default onFail handler ignores unreadable dirs (other
     * users' CE storage), which is exactly what we want here.
     */
    private fun findSessionFiles(context: Context): Map<String, File> = runCatching {
        context.dataDir.walkTopDown()
            .maxDepth(MAX_WALK_DEPTH)
            .filter {
                it.isFile && SessionPaths.isSessionFileName(it.name)
            }
            .mapNotNull { f -> relativeTo(f, context)?.let { rel -> rel to f } }
            .toMap()
    }.getOrDefault(emptyMap())

    /** Path relative to the data dir, for a faithful import write-back. */
    private fun relativeTo(f: File, context: Context): String? = runCatching {
        val base = context.dataDir.canonicalPath.trimEnd('/') + "/"
        val full = f.canonicalPath
        if (full.startsWith(base)) full.removePrefix(base) else null
    }.getOrNull()

    /** Write-through-temp-then-rename so a crash never leaves a torn session file. */
    private fun atomicWrite(target: File, bytes: ByteArray): Boolean = runCatching {
        val tmp = File(target.parentFile, target.name + ".fbar_tmp")
        tmp.outputStream().use { it.write(bytes) }
        if (!tmp.renameTo(target)) {
            // Fall back to a direct overwrite (e.g. cross-filesystem tmp).
            target.outputStream().use { it.write(bytes) }
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    /** Contributes a file to MediaStore Downloads (pending → write → publish). */
    private fun writeMediaStore(context: Context, displayName: String, bytes: ByteArray): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, EXPORT_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return@runCatching false
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        true
    }.getOrElse {
        L.e(TAG, "MediaStore write failed", it)
        false
    }

    /** The most recent FBAR-Session-*.json in Downloads (own contributions — no permission needed). */
    private fun latestExport(context: Context): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("$EXPORT_PREFIX%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            ?.let { id ->
                Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
    }.getOrNull()

    /** FB-side toast (visible only when FB is foreground) + module-app result. */
    private fun toast(context: Context, ok: Boolean, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
        // Also notify the module app (foreground) — FB's own toast is
        // suppressed whenever FB is backgrounded, which is exactly when the
        // user presses the buttons.
        runCatching {
            val intent = Intent(ACTION_RESULT)
                .setPackage("tn.loukious.facebookappadsremover")
                .putExtra(EXTRA_OK, ok)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_NONCE, requestNonce.get())
            context.sendBroadcast(intent)
        }
    }
}
