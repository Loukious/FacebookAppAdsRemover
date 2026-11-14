package tn.loukious.facebookappadsremover.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.io.File

/** Durable UI work shared with the manifest receiver when the activity is absent. */
internal class SettingsUiStore(context: Context) {
    companion object { private val changeLock = Any() }
    val preferences: SharedPreferences = context.getSharedPreferences("fbar_ui_state", Context.MODE_PRIVATE)
    private val reportDirectory = File(context.cacheDir, "reports")

    val changed: Boolean
        get() = preferences.getBoolean("pending_changes", false)

    val changeRevision: Long
        get() = preferences.getLong("change_revision", 0L)

    fun markChanged() {
        synchronized(changeLock) {
            val next = PendingChangesState(changeRevision, changed).changed()
            preferences.edit().putLong("change_revision", next.revision)
                .putBoolean("pending_changes", next.pending).commit()
        }
    }

    /** A completed restart cannot clear edits made while it was in flight. */
    fun markApplied(expectedRevision: Long): Boolean = synchronized(changeLock) {
        if (changeRevision != expectedRevision) return@synchronized false
        val next = PendingChangesState(changeRevision, changed).applied(expectedRevision)
        preferences.edit().putBoolean("pending_changes", next.pending).commit()
    }

    var report: DiagnosticsRequest?
        get() = DiagnosticsRequest.decode(preferences.getString("report_request", null))
        set(value) { preferences.edit().putString("report_request", value?.encode()).commit() }

    fun reportFile(request: DiagnosticsRequest): File? =
        request.fileName?.let { File(reportDirectory, it) }

    /** Persist the report before notifying a recreated settings activity. */
    fun receiveReport(nonce: String?, kind: SessionRequestKind, text: String): Boolean {
        val request = report ?: return false
        if (!request.accepts(nonce, kind)) return false
        reportDirectory.mkdirs()
        val fileName = "FBAR-diagnostics-${request.nonce}.txt"
        val file = File(reportDirectory, fileName)
        val temporary = File(reportDirectory, "$fileName.tmp")
        return runCatching {
            temporary.writeText(text)
            check(temporary.renameTo(file))
            report = request.copy(phase = ReportPhase.READY, fileName = fileName)
            true
        }.onFailure { temporary.delete() }.getOrDefault(false)
    }

    fun clearReport(deleteFile: Boolean = true) {
        if (deleteFile) report?.let { reportFile(it)?.delete() }
        report = null
    }
}

/** Survives rotation; its store also restores pending work after process death. */
internal class SettingsUiViewModel(application: Application) : AndroidViewModel(application) {
    data class RestartOutcome(val result: FacebookRestartController.Result, val revision: Long)

    val store = SettingsUiStore(application)
    private var restartController: FacebookRestartController? = null
    private var restartOutcome: RestartOutcome? = null
    /** Attached only while the current activity is resumed. */
    var onRestartReady: (() -> Unit)? = null
    var changed by mutableStateOf(store.changed)
        private set
    var changeRevision by mutableStateOf(store.changeRevision)
        private set
    var report by mutableStateOf(store.report)
        private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init { store.preferences.registerOnSharedPreferenceChangeListener(listener) }

    fun refresh() {
        changed = store.changed
        changeRevision = store.changeRevision
        report = store.report
    }

    fun markChanged() { store.markChanged() }
    fun markApplied(expectedRevision: Long = changeRevision): Boolean = store.markApplied(expectedRevision)

    fun beginRestart(): Boolean {
        if (restartController != null || restartOutcome != null) return false
        val revision = store.changeRevision
        restartController = FacebookRestartController.start(getApplication()) { result ->
            restartController = null
            restartOutcome = RestartOutcome(result, revision)
            onRestartReady?.invoke()
        }
        return true
    }

    fun takeRestartResult(): RestartOutcome? = restartOutcome.also { restartOutcome = null }

    override fun onCleared() {
        onRestartReady = null
        restartController?.cancel()
        restartController = null
        store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
