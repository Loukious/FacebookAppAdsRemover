package tn.loukious.facebookappadsremover.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        private const val KEY_NONCE = "nonce"

        /**
         * The receiver must stay exported (Facebook's process sends the
         * result), so it only trusts replies carrying the token of the last
         * request this app sent. Returns that new token.
         */
        fun newRequestNonce(context: Context): String {
            val nonce = java.util.UUID.randomUUID().toString()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_NONCE, nonce).apply()
            return nonce
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionBackup.ACTION_RESULT) return
        val expected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NONCE, null)
        val nonce = intent.getStringExtra(SessionBackup.EXTRA_NONCE)
        if (expected == null || nonce != expected) {
            android.util.Log.w("FBAR.Session", "ignoring session result without a matching request")
            return
        }
        val ok = intent.getBooleanExtra(SessionBackup.EXTRA_OK, false)
        val message = intent.getStringExtra(SessionBackup.EXTRA_MESSAGE) ?: return
        runCatching {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        if (!ok) {
            android.util.Log.e("FBAR.Session", "reported failure: $message")
        }
    }
}
