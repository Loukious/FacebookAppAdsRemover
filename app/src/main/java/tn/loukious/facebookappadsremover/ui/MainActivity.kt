package tn.loukious.facebookappadsremover.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import tn.loukious.facebookappadsremover.core.AdSettingsMigration
import tn.loukious.facebookappadsremover.core.SessionBackup
import tn.loukious.facebookappadsremover.core.Settings

/**
 * Settings screen — the toggle surface for every feature the module ports.
 * The UI is Jetpack Compose (SettingsScreens.kt); this activity owns the
 * state and the side effects behind it ([SettingsModel]).
 *
 * CHANNEL: toggles live in the framework's remote-preferences storage
 * (Vector/LSPosed daemon), reached from the module app through the libxposed
 * SERVICE library — XposedServiceHelper.registerListener delivers the
 * framework binder to our manifest-merged XposedProvider, and
 * service.getRemotePreferences(NAME) returns a WRITABLE SharedPreferences
 * whose edits go over binder. The hooks inside the Facebook process read the
 * same group read-only via XposedInterface.getRemotePreferences. Verified the
 * hard way (2026-09-06): plain SharedPreferences files in the module's own
 * storage — credential-encrypted OR device-protected — are never read by the
 * daemon; writes must go through the service.
 *
 * Until the service binds, switches show disabled with defaults.
 */
class MainActivity : ComponentActivity(), SettingsModel {

    private var prefs: SharedPreferences? = null

    // ------------------------------------------------------------- state --

    override var moduleState by mutableStateOf(ModuleState.CONNECTING)
        private set
    override var frameworkDetail by mutableStateOf<String?>(null)
        private set
    override var bound by mutableStateOf(false)
        private set
    /** Something changed since the last Apply: shows the Apply row. */
    override var changed by mutableStateOf(false)
        private set
    override var keywords by mutableStateOf("")
        private set
    override var launcherVisible by mutableStateOf(true)
        private set
    override val contributors: List<Pair<Contributor, ImageBitmap?>> by lazy {
        loadContributors(this).map { c ->
            c to runCatching {
                assets.open(c.avatar).use(BitmapFactory::decodeStream)?.asImageBitmap()
            }.getOrNull()
        }
    }

    private val values = mutableStateMapOf<String, Boolean>()

    override var reportLoading by mutableStateOf(false)
        private set

    /**
     * System file browser for importing a specific session export. The FB
     * process cannot open another app's content URIs without a grant, so the
     * picked JSON is read here (~15 KB) and shipped inside the broadcast.
     */
    private val pickSessionFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> onSessionFilePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        launcherVisible = LauncherIcon.isVisible(this)
        setContent { SettingsTheme { SettingsApp(this) } }
        SessionResultReceiver.reportListener = { text ->
            runOnUiThread {
                reportLoading = false
                onReport?.invoke(text)
                onReport = null
            }
        }

        // No bind within a few seconds: the framework doesn't know this
        // module (not enabled in LSPosed/Vector), so say so instead of
        // showing "connecting" forever.
        window.decorView.postDelayed({ if (!bound) moduleState = ModuleState.INACTIVE }, 3000)

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                prefs = runCatching { service.getRemotePreferences(Settings.NAME) }.getOrNull()
                migrateFromLocalStorage()
                migrateSplitAdToggles()
                val detail = runCatching {
                    "${service.frameworkName} ${service.frameworkVersion} (API ${service.apiVersion})"
                }.getOrNull()
                runOnUiThread { onServiceReady(detail) }
            }

            override fun onServiceDied(service: XposedService) {
                prefs = null
                runOnUiThread {
                    bound = false
                    moduleState = ModuleState.INACTIVE
                }
            }
        })
    }

    private fun onServiceReady(detail: String?) {
        if (bound) return
        // Load the real stored state, then unlock the switches.
        for (spec in TOGGLE_SECTIONS.flatMap { it.toggles }) {
            values[spec.key] = prefs?.getBoolean(spec.key, spec.default) ?: spec.default
        }
        keywords = prefs?.getString(Settings.FEED_KEYWORDS, "") ?: ""
        frameworkDetail = detail
        moduleState = ModuleState.ACTIVE
        bound = true
    }

    // ----------------------------------------------------------- actions --

    override fun isOn(spec: ToggleSpec) = values[spec.key] ?: spec.default

    override fun setOn(spec: ToggleSpec, on: Boolean) {
        values[spec.key] = on
        prefs?.edit()?.putBoolean(spec.key, on)?.apply()
        changed = true
    }

    /**
     * The keyword list for the feed filter (Settings.FEED_KEYWORDS). Saved on
     * every change: focus-loss saving proved fragile (a force-stop skips
     * onPause entirely), which silently dropped typed keywords.
     */
    override fun updateKeywords(value: String) {
        if (value == keywords) return
        keywords = value
        prefs?.edit()?.putString(Settings.FEED_KEYWORDS, value)?.apply()
        changed = true
    }

    /**
     * Launcher-icon switch (ui.LauncherIcon). Its value lives in
     * PackageManager's component state rather than the framework's remote
     * preferences, so it needs no service. Hiding is confirmed in the UI first.
     */
    override fun updateLauncherVisible(visible: Boolean) {
        LauncherIcon.setVisible(this, visible)
        launcherVisible = visible
    }

    /**
     * One-tap apply: the module's receiver inside Facebook (SessionBackup,
     * signature-protected) makes Facebook exit, then Facebook is started
     * again here. If Facebook wasn't running, it simply starts.
     */
    override fun restartFacebook() {
        val launch = packageManager.getLaunchIntentForPackage(FACEBOOK)
        if (launch == null) {
            Toast.makeText(this, "Facebook isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { sendBroadcast(Intent(SessionBackup.ACTION_RESTART).setPackage(FACEBOOK)) }
        changed = false
        Toast.makeText(this, "Restarting Facebook…", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            runCatching { startActivity(launch) }
        }, 1500)
    }

    /** What to do with the report once Facebook sends it. */
    private var onReport: ((String) -> Unit)? = null
    /** Increments per request so a stale timeout never cancels a newer one. */
    private var reportRequest = 0
    private var reportToSave: String? = null

    private val createReportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = reportToSave
        reportToSave = null
        if (uri == null || text == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
        }.getOrDefault(false)
        Toast.makeText(this, if (ok) "Report saved" else "Couldn't save the report", Toast.LENGTH_SHORT).show()
    }

    private fun reportFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        return "FBAR-diagnostics-$stamp.txt"
    }

    /**
     * Ask Facebook for its module log (collected inside Facebook's process,
     * no computer needed), then run [then] with it.
     */
    private fun fetchReport(then: (String) -> Unit) {
        if (reportLoading) return
        val nonce = SessionResultReceiver.newRequestNonce(this)
        val intent = Intent(SessionBackup.ACTION_DIAGNOSTICS).setPackage(FACEBOOK)
            .putExtra(SessionBackup.EXTRA_NONCE, nonce)
        if (runCatching { sendBroadcast(intent) }.isFailure) {
            SessionResultReceiver.cancelRequestNonce(this, nonce)
            return
        }
        onReport = then
        reportLoading = true
        val request = ++reportRequest
        // Facebook only answers while running; say so instead of spinning.
        window.decorView.postDelayed({
            if (reportLoading && request == reportRequest) {
                reportLoading = false
                onReport = null
                SessionResultReceiver.cancelRequestNonce(this, nonce)
                Toast.makeText(
                    this,
                    "Facebook didn't answer. Open Facebook once, then try again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }, 6000)
    }

    override fun saveReport() = fetchReport { text ->
        reportToSave = text
        createReportFile.launch(reportFileName())
    }

    /** Shares the report as a .txt attachment (for an issue or a chat). */
    override fun shareReport() = fetchReport { text ->
        runCatching {
            val dir = java.io.File(cacheDir, "reports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = java.io.File(dir, reportFileName()).apply { writeText(text) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.reports", file)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Facebook App Ads Remover diagnostics")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share report"))
        }.onFailure { Toast.makeText(this, "Couldn't share the report", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        SessionResultReceiver.reportListener = null
        super.onDestroy()
    }

    override fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /**
     * Session export/import — broadcasts into the Facebook process
     * (core.SessionBackup answers; it holds the session files and the
     * captured cookies).
     */
    override fun sessionExport() = sendSession(SessionBackup.ACTION_EXPORT)

    override fun sessionImportLatest() = sendSession(SessionBackup.ACTION_IMPORT)

    override fun sessionImportFile() {
        pickSessionFile.launch(arrayOf("application/json", "text/*", "*/*"))
    }

    private fun sendSession(action: String) {
        val nonce = SessionResultReceiver.newRequestNonce(this)
        val intent = Intent(action).setPackage(FACEBOOK).putExtra(SessionBackup.EXTRA_NONCE, nonce)
        val sent = runCatching { sendBroadcast(intent) }.isSuccess
        if (!sent) SessionResultReceiver.cancelRequestNonce(this, nonce)
        Toast.makeText(
            this,
            if (sent) "Asking Facebook…" else "Broadcast failed — is Facebook installed?",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun onSessionFilePicked(uri: Uri?) {
        if (uri == null) return // picker cancelled
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't read the picked file", Toast.LENGTH_LONG).show()
            return
        }
        // Quick client-side sanity check so obvious wrong files fail here
        // rather than as a silent FB-side toast.
        if (!text.trimStart().startsWith("{") || !text.contains("\"files\"")) {
            Toast.makeText(this, "That doesn't look like a session export", Toast.LENGTH_LONG).show()
            return
        }
        val nonce = SessionResultReceiver.newRequestNonce(this)
        val intent = Intent(SessionBackup.ACTION_IMPORT_DATA)
            .setPackage(FACEBOOK)
            .putExtra(SessionBackup.EXTRA_DATA, text)
            .putExtra(SessionBackup.EXTRA_NONCE, nonce)
        runCatching { sendBroadcast(intent) }
            .onSuccess { Toast.makeText(this, "Asking Facebook…", Toast.LENGTH_SHORT).show() }
            .onFailure {
                SessionResultReceiver.cancelRequestNonce(this, nonce)
                Toast.makeText(this, "Broadcast failed — is Facebook installed?", Toast.LENGTH_SHORT).show()
            }
    }

    // -------------------------------------------------------- migrations --

    /**
     * One-time carry-over from the plain-SharedPreferences writes of the first
     * settings build (which the framework never saw). Runs once per bind but
     * only pushes when the remote storage is still empty.
     */
    private fun migrateFromLocalStorage() {
        val remote = prefs ?: return
        runCatching {
            if (remote.all.isNotEmpty()) return
            val legacy = getSharedPreferences(Settings.NAME, MODE_PRIVATE)
            val old = legacy.all
            if (old.isEmpty()) return
            remote.edit().apply {
                for ((key, value) in old) {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> @Suppress("UNCHECKED_CAST") (value as? Set<String>)?.let { putStringSet(key, it) }
                    }
                }
            }.apply()
        }
    }

    /**
     * The previous Block ads setting was a master gate over feed, video,
     * stories, Marketplace and games. Convert it once into independent surface
     * preferences so an existing disabled master stays disabled after upgrade.
     * A user can then enable any individual ad family without the old master
     * overriding their choice. The retired Reels shopping setting is removed.
     */
    private fun migrateSplitAdToggles() {
        val remote = prefs ?: return
        runCatching {
            val updates = AdSettingsMigration.updates(remote.all)
            if (updates.isEmpty() && !remote.contains(AdSettingsMigration.RETIRED_SHOPPING)) {
                return
            }
            remote.edit().apply {
                for ((key, value) in updates) putBoolean(key, value)
                remove(AdSettingsMigration.RETIRED_SHOPPING)
                remove(Settings.LEGACY_ADS_ENABLED)
            }.apply()
        }.onFailure {
            android.util.Log.w("FBAR.Settings", "Ad toggle migration failed", it)
        }
    }
}

private const val FACEBOOK = "com.facebook.katana"
