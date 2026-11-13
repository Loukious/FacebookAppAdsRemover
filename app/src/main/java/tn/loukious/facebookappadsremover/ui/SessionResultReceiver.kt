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
        private const val KEY_LEGACY_NONCE = "nonce"

        /**
         * The receiver must stay exported (Facebook's process sends the
         * result), so it only trusts replies carrying a pending request token.
         * Tokens stay pending until a reply arrives; workers have no fixed timeout.
         */
        @Synchronized
        fun newRequestNonce(context: Context): String {
            val nonce = java.util.UUID.randomUUID().toString()
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val requests = loadRequests(prefs)
            requests.register(nonce)
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

        private fun loadRequests(prefs: SharedPreferences): SessionRequestState {
            // Copy the preference set before changing it. Also preserve an in-flight
            // request from the previous single-token implementation during an update.
            val pending = prefs.getStringSet(KEY_PENDING_NONCES, emptySet()).orEmpty().toMutableSet()
            prefs.getString(KEY_LEGACY_NONCE, null)?.let { pending.add(it) }
            return SessionRequestState(pending)
        }

        private fun saveRequests(prefs: SharedPreferences, requests: SessionRequestState) {
            prefs.edit()
                .putStringSet(KEY_PENDING_NONCES, requests.snapshot())
                .remove(KEY_LEGACY_NONCE)
                .apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionBackup.ACTION_RESULT) return
        val message = intent.getStringExtra(SessionBackup.EXTRA_MESSAGE) ?: return
        val nonce = intent.getStringExtra(SessionBackup.EXTRA_NONCE)
        if (!consumeRequestNonce(context, nonce)) {
            android.util.Log.w("FBAR.Session", "ignoring session result without a matching request")
            return
        }
        val ok = intent.getBooleanExtra(SessionBackup.EXTRA_OK, false)
        runCatching {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        if (!ok) {
            android.util.Log.e("FBAR.Session", "reported failure: $message")
        }
    }
}
