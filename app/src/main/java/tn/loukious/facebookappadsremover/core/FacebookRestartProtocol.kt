package tn.loukious.facebookappadsremover.core

import android.os.IBinder

/** Shared wire format; preparing a restart never terminates a process. */
internal object FacebookRestartProtocol {
    const val ACTION_PREPARE = "tn.loukious.facebookappadsremover.action.PREPARE_RESTART"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_DEADLINE = "restart_deadline"
    const val ENDPOINT_PREFIX = "restart_endpoint_"
    const val DESCRIPTOR = "tn.loukious.facebookappadsremover.RestartEndpoint"
    const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION
    const val TIMEOUT_MS = 10_000L
}
