package tn.loukious.facebookappadsremover.ui

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import tn.loukious.facebookappadsremover.R
import tn.loukious.facebookappadsremover.core.SessionBackup
import tn.loukious.facebookappadsremover.core.Settings
import tn.loukious.facebookappadsremover.core.AdSettingsMigration
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Settings screen — the toggle surface for every feature the module ports.
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
 * Until the service binds, switches show disabled with defaults. The daemon
 * pushes change notifications to hooked processes, so toggles may apply
 * without an FB restart (documented as restart-required regardless).
 *
 * The in-FB floating panel the original mod had is a later milestone (M6)
 * and will read the same keys.
 */
class MainActivity : AppCompatActivity() {

    private var prefs: SharedPreferences? = null
    private var serviceBound = false
    private var resyncing = false
    private lateinit var serviceHint: TextView
    private lateinit var togglesList: LinearLayout

    /**
     * System file browser for importing a specific session export. The FB
     * process cannot open another app's content URIs without a grant, so the
     * picked JSON is read here (~15 KB) and shipped inside the broadcast.
     */
    private val pickSessionFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> onSessionFilePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Activity-only: never change Facebook's process/theme from the module.
        // Apply BEFORE AppCompat inflates the ActionBar, views and dialogs.
        // On Android < 12 or unsupported builds this is a no-op; the M3
        // DayNight theme supplies its own accessible fallback colors.
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceHint = TextView(this)
        serviceHint.text = getString(R.string.service_waiting)
        serviceHint.textSize = 13f
        serviceHint.setPadding(0, 0, 0, 16)

        togglesList = findViewById(R.id.toggles)
        togglesList.addView(serviceHint)
        for (section in TOGGLE_SECTIONS) {
            addSectionHeader(togglesList, section.title)
            for (toggle in section.toggles) {
                addToggleRow(togglesList, toggle)
            }
            // Non-toggle controls that live inside their feature's section.
            when (section.extra) {
                SectionExtra.KEYWORDS -> addKeywordInput(togglesList)
                SectionExtra.SESSION -> addSessionButtons(togglesList)
                SectionExtra.LAUNCHER -> addLauncherIconRow(togglesList)
                SectionExtra.NONE -> {}
            }
        }

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                prefs = runCatching { service.getRemotePreferences(Settings.NAME) }.getOrNull()
                migrateFromLocalStorage()
                migrateSplitAdToggles()
                runOnUiThread { onServiceReady() }
            }

            override fun onServiceDied(service: XposedService) {
                prefs = null
                runOnUiThread { onServiceGone() }
            }
        })
    }

    private fun onServiceReady() {
        if (serviceBound) return
        serviceBound = true
        serviceHint.text = getString(R.string.service_ready)
        // Re-sync every switch with the real stored state, then unlock them.
        resyncing = true
        for (spec in TOGGLE_SECTIONS.flatMap { it.toggles }) {
            val switch = rows[spec.key] ?: continue
            switch.isChecked = prefs?.getBoolean(spec.key, spec.default) ?: spec.default
            switch.isEnabled = true
        }
        resyncing = false
        keywordInput?.let { input ->
            input.setText(prefs?.getString(Settings.FEED_KEYWORDS, "") ?: "")
            input.isEnabled = true
        }
    }

    private fun onServiceGone() {
        serviceBound = false
        serviceHint.text = getString(R.string.service_waiting)
        for (switch in rows.values) switch.isEnabled = false
        keywordInput?.isEnabled = false
    }

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

    private val rows = HashMap<String, MaterialSwitch>()
    private var keywordInput: android.widget.EditText? = null

    /**
     * The keyword list for the feed filter (Settings.FEED_KEYWORDS). Saved on
     * every text change; loaded once the service binds (same lifecycle as the
     * switches).
     */
    private fun addKeywordInput(parent: ViewGroup) {
        // Material text field: boxed outline with a floating hint, so the
        // placeholder never overlaps the typed text (plain EditText inside
        // this theme drew both on top of each other).
        val layout = com.google.android.material.textfield.TextInputLayout(
            this, null,
            com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox,
        ).apply {
            hint = "e.g. giveaway, crypto, follow me on"
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }

        val input = com.google.android.material.textfield.TextInputEditText(layout.context)
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        input.setSingleLine(false)
        input.minLines = 1
        input.maxLines = 3
        input.isEnabled = false // until the service binds
        // Save on every change: focus-loss saving proved fragile (an EditText
        // keeps focus when a non-focusable view is tapped, and a force-stop
        // skips onPause entirely), which silently dropped typed keywords.
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                saveKeywords(s?.toString() ?: "")
            }
        })
        keywordInput = input
        layout.addView(input)
        parent.addView(layout)
    }

    private fun saveKeywords(value: String) {
        prefs?.edit()?.putString(Settings.FEED_KEYWORDS, value)?.apply()
    }

    /**
     * Session export/import — broadcasts into the Facebook process
     * (core.SessionBackup answers; it holds the session files and the
     * captured cookies).
     */
    private fun addSessionButtons(parent: ViewGroup) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(4), dp(12), dp(4), dp(4))

        val export = android.widget.Button(this)
        export.text = "Export session"
        export.setOnClickListener { sendSession(SessionBackup.ACTION_EXPORT) }
        row.addView(export, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val spacer = View(this)
        row.addView(spacer, LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.WRAP_CONTENT))

        val import = android.widget.Button(this)
        import.text = "Import latest"
        import.setOnClickListener { sendSession(SessionBackup.ACTION_IMPORT) }
        row.addView(import, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        parent.addView(row)

        val fromFile = android.widget.Button(this)
        fromFile.text = "Import from file…"
        fromFile.setOnClickListener { pickSessionFile.launch(arrayOf("application/json", "text/*", "*/*")) }
        parent.addView(fromFile)

        val caption = TextView(this)
        caption.text = "Export copies the login (authentication/logged_in) files + cookies to Download/FacebookAppAdsRemover. \"Import latest\" restores the newest export; \"Import from file\" lets you browse to any FBAR-Session-*.json (e.g. one you copied to another folder or device). Force-stop Facebook afterwards."
        caption.textSize = 13f
        caption.setPadding(dp(4), 0, dp(4), dp(8))
        parent.addView(caption)
    }

    private fun sendSession(action: String) {
        val intent = Intent(action).setPackage("com.facebook.katana")
        val sent = runCatching { sendBroadcast(intent) }.isSuccess
        Toast.makeText(
            this,
            if (sent) "Asking Facebook…" else "Broadcast failed — is Facebook installed?",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun onSessionFilePicked(uri: android.net.Uri?) {
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
        val intent = Intent(SessionBackup.ACTION_IMPORT_DATA)
            .setPackage("com.facebook.katana")
            .putExtra(SessionBackup.EXTRA_DATA, text)
        runCatching { sendBroadcast(intent) }
            .onSuccess { Toast.makeText(this, "Asking Facebook…", Toast.LENGTH_SHORT).show() }
            .onFailure {
                Toast.makeText(this, "Broadcast failed — is Facebook installed?", Toast.LENGTH_SHORT).show()
            }
    }

    private fun addSectionHeader(parent: ViewGroup, title: String) {
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 13f
        tv.isAllCaps = true
        tv.letterSpacing = 0.1f
        tv.setPadding(dp(4), dp(24), dp(4), dp(8))
        parent.addView(tv)
    }

    private fun addToggleRow(parent: ViewGroup, spec: ToggleSpec) {
        val switch = buildToggleRow(parent, spec.title, spec.subtitle)
        switch.isChecked = spec.default
        switch.isEnabled = false // until the service binds
        switch.setOnCheckedChangeListener { _, checked ->
            if (resyncing) return@setOnCheckedChangeListener
            prefs?.edit()?.putBoolean(spec.key, checked)?.apply()
        }
        rows[spec.key] = switch
    }

    /**
     * The row layout every switch on this screen uses: title + subtitle on the
     * left, switch on the right, whole row as the click target (the switch
     * itself is a small target). Callers own the switch's initial state,
     * enabled state and listener.
     */
    private fun buildToggleRow(parent: ViewGroup, title: String, subtitle: String): MaterialSwitch {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(10), dp(4), dp(10))

        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val titleView = TextView(this)
        titleView.text = title
        titleView.textSize = 16f
        text.addView(titleView)

        val subtitleView = TextView(this)
        subtitleView.text = subtitle
        subtitleView.textSize = 13f
        subtitleView.setPadding(0, dp(2), 0, 0)
        text.addView(subtitleView)
        row.addView(text)

        // MaterialSwitch reads Material 3 colorPrimary/onSurface, including
        // Monet wallpaper colors after DynamicColors applies its overlay.
        val switch = MaterialSwitch(this)
        row.addView(switch)

        // Whole row toggles, not just the small switch target.
        row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
        parent.addView(row)
        return switch
    }

    /**
     * Launcher-icon switch (ui.LauncherIcon). The odd one out on this screen:
     * its value lives in PackageManager's component state rather than the
     * framework's remote preferences, so it needs no service and is enabled
     * immediately instead of waiting for the bind.
     *
     * Hiding is confirmed first, because the icon is this app's only entry
     * point — there is no second screen to fall back to.
     */
    private fun addLauncherIconRow(parent: ViewGroup) {
        val switch = buildToggleRow(
            parent,
            "Show launcher icon",
            "Hide this app's icon from the launcher. Everything keeps working — reopen this screen from the Xposed module list, or with the adb command shown before it's hidden",
        )
        switch.isChecked = LauncherIcon.isVisible(this)
        switch.isEnabled = true

        // Reverting from inside the listener re-enters it; this is the same
        // guard the prefs switches use via `resyncing`.
        var reverting = false
        fun revert() {
            reverting = true
            switch.isChecked = true
            reverting = false
        }

        switch.setOnCheckedChangeListener { _, checked ->
            if (reverting) return@setOnCheckedChangeListener
            if (checked) {
                LauncherIcon.setVisible(this, true)
                return@setOnCheckedChangeListener
            }
            AlertDialog.Builder(this)
                .setTitle("Hide the launcher icon?")
                .setMessage(
                    "The icon disappears from your app drawer. Facebook keeps working, and so do its hooks.\n\n" +
                        "To open this screen again:\n" +
                        "•  Xposed/Vector → Modules → Facebook App Ads Remover → open settings\n" +
                        "•  adb shell am start -n tn.loukious.facebookappadsremover/.ui.MainActivity"
                )
                // Both the button and a back/outside dismissal must put the
                // switch back, or it would read "hidden" while the icon is
                // still in the drawer.
                .setNegativeButton("Cancel") { _, _ -> revert() }
                .setOnCancelListener { revert() }
                .setPositiveButton("Hide") { _, _ -> LauncherIcon.setVisible(this, false) }
                .show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
