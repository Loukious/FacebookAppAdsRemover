package tn.loukious.facebookappadsremover.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import tn.loukious.facebookappadsremover.core.SessionBackup

/**
 * Shows the Facebook process's session export/import result in the module
 * app. The work happens inside FB (core.SessionBackup), but FB is
 * backgrounded while the user presses the buttons, and Android 11+
 * suppresses background toasts — so FB sends ACTION_RESULT here and this
 * foreground app does the talking.
 *
 * Referencing SessionBackup's constants is safe here: they're compile-time
 * consts, so no SessionBackup initialization runs in this process.
 */
class SessionResultReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS = "fbar_session_request"
        private const val KEY_PENDING_NONCES = "pending_nonces"
        private const val KEY_REQUEST_KINDS = "request_kinds"
        private const val KEY_LEGACY_NONCE = "nonce"

        /**
         * The receiver must stay exported (Facebook's process sends the
         * result), so it only trusts replies carrying a pending request token.
         * Tokens stay pending until a reply arrives; workers have no fixed timeout.
         */
        @Synchronized
        internal fun newRequestNonce(context: Context, kind: SessionRequestKind = SessionRequestKind.LEGACY): String {
            val nonce = java.util.UUID.randomUUID().toString()
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val requests = loadRequests(prefs)
            requests.register(nonce, kind)
            saveRequests(prefs, requests)
            return nonce
        }

        /** Cancel only the request whose broadcast could not be sent. */
        fun cancelRequestNonce(context: Context, nonce: String) {
            consumeRequestNonce(context, nonce)
        }

        @Synchronized
        private fun consumeRequestNonce(context: Context, nonce: String?): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val requests = loadRequests(prefs)
            if (!requests.consume(nonce)) return false
            saveRequests(prefs, requests)
            return true
        }

        @Synchronized
        private fun requestKind(context: Context, nonce: String?): SessionRequestKind? =
            loadRequests(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).kindOf(nonce)

        private fun loadRequests(prefs: SharedPreferences): SessionRequestState {
            // Copy the preference set before changing it. Also preserve an in-flight
            // request from the previous single-token implementation during an update.
            val pending = prefs.getStringSet(KEY_PENDING_NONCES, emptySet()).orEmpty().toMutableSet()
            prefs.getString(KEY_LEGACY_NONCE, null)?.let { pending.add(it) }
            val kinds = prefs.getStringSet(KEY_REQUEST_KINDS, emptySet()).orEmpty().mapNotNull { record ->
                val parts = record.split(':', limit = 2)
                if (parts.size != 2) null else runCatching {
                    parts[1] to SessionRequestKind.valueOf(parts[0])
                }.getOrNull()
            }.toMap()
            return SessionRequestState(pending, kinds)
        }

        private fun saveRequests(prefs: SharedPreferences, requests: SessionRequestState) {
            prefs.edit()
                .putStringSet(KEY_PENDING_NONCES, requests.snapshot())
                .putStringSet(KEY_REQUEST_KINDS, requests.kindsSnapshot().map { (nonce, kind) -> "${kind.name}:$nonce" }.toSet())
                .remove(KEY_LEGACY_NONCE)
                .commit()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionBackup.ACTION_RESULT) return
        val nonce = intent.getStringExtra(SessionBackup.EXTRA_NONCE)
        val report = intent.getStringExtra(SessionBackup.EXTRA_REPORT)
        val message = intent.getStringExtra(SessionBackup.EXTRA_MESSAGE)
        if (report == null && message == null) return
        val kind = requestKind(context, nonce)
        if (kind == null) {
            android.util.Log.w("FBAR.Session", "ignoring session result without a matching request")
            return
        }
        val store = SettingsUiStore(context)
        if (kind.isDiagnostics) {
            val current = store.report
            if (current == null || !current.accepts(nonce, kind)) return
            if (report != null && store.receiveReport(nonce, kind, report)) {
                consumeRequestNonce(context, nonce)
            } else {
                consumeRequestNonce(context, nonce)
                store.clearReport()
                runCatching {
                    Toast.makeText(context, message ?: "Couldn't keep the diagnostics report", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        // A report cannot be mistaken for an import acknowledgement.
        if (report != null || !consumeRequestNonce(context, nonce)) return
        message ?: return
        val ok = intent.getBooleanExtra(SessionBackup.EXTRA_OK, false)
        if (kind.requiresRestart(ok)) store.markChanged()
        runCatching {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        if (!ok) {
            android.util.Log.e("FBAR.Session", "reported failure: $message")
        }
    }
}
