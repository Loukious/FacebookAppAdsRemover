package tn.loukious.facebookappadsremover.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import tn.loukious.facebookappadsremover.core.FacebookRestartProtocol
import java.util.UUID

/** Prepares every participating Facebook process, then waits for their actual deaths. */
class FacebookRestartController private constructor(
    context: Context,
    onResult: (Result) -> Unit,
) {
    enum class Result { CONFIRMED, OPEN_ONLY, FAILED }

    companion object {
        private const val FACEBOOK = "com.facebook.katana"

        /** The callback runs on the main thread. [cancel] releases it and silences late replies. */
        fun start(context: Context, onResult: (Result) -> Unit): FacebookRestartController =
            FacebookRestartController(context, onResult).also { controller ->
                controller.main.post { controller.begin() }
            }
    }

    private data class Watch(val binder: IBinder, val recipient: IBinder.DeathRecipient)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nonce = UUID.randomUUID().toString()
    private val deadline = SystemClock.elapsedRealtime() + FacebookRestartProtocol.TIMEOUT_MS
    private val state = RestartWaitState(deadline)
    private val watches = LinkedHashMap<String, Watch>()
    private var receivedPreparation = false
    private var callback: ((Result) -> Unit)? = onResult
    private val timeout = Runnable { state.timeout(SystemClock.elapsedRealtime())?.let(::finish) }

    private val preparedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!state.pending) return
            runCatching { acceptEndpoints(getResultExtras(false)) }
                .onFailure { state.fail()?.let(::finish) }
        }
    }

    private fun begin() {
        if (!state.pending) return
        state.timeout(SystemClock.elapsedRealtime())?.let { finish(it); return }
        main.postDelayed(timeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        val request = Intent(FacebookRestartProtocol.ACTION_PREPARE)
            .setPackage(FACEBOOK)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(FacebookRestartProtocol.EXTRA_NONCE, nonce)
            .putExtra(FacebookRestartProtocol.EXTRA_DEADLINE, deadline)
        runCatching {
            app.sendOrderedBroadcast(request, null, preparedReceiver, main, Activity.RESULT_CANCELED, null, null)
        }.onFailure { state.fail()?.let(::finish) }
    }

    private fun acceptEndpoints(extras: Bundle?) {
        if (receivedPreparation || !state.pending) return
        receivedPreparation = true
        val endpoints = LinkedHashMap<String, IBinder>()
        for (key in extras?.keySet().orEmpty()) {
            if (!key.startsWith(FacebookRestartProtocol.ENDPOINT_PREFIX)) continue
            endpoints[key] = checkNotNull(extras?.getBinder(key))
        }
        state.prepared(endpoints.keys, SystemClock.elapsedRealtime())?.let { finish(it); return }
        if (!state.pending) return

        // Publish all expected IDs before installing watches: linkToDeath can discover
        // an already-dead peer, and a death callback can arrive during registration.
        for ((id, binder) in endpoints) {
            val recipient = IBinder.DeathRecipient {
                main.post { state.died(id, SystemClock.elapsedRealtime())?.let(::finish) }
            }
            watches[id] = Watch(binder, recipient)
            try {
                binder.linkToDeath(recipient, 0)
                if (!binder.isBinderAlive) state.died(id, SystemClock.elapsedRealtime())?.let(::finish)
            } catch (_: DeadObjectException) {
                state.died(id, SystemClock.elapsedRealtime())?.let(::finish)
            } catch (_: android.os.RemoteException) {
                if (!binder.isBinderAlive) state.died(id, SystemClock.elapsedRealtime())?.let(::finish)
                else state.fail()?.let(::finish)
            }
            if (!state.pending) return
        }

        // Preparation never schedules a kill. Only this phase may ask a peer to
        // stop, after every peer is watched and while the request is still live.
        for ((id, binder) in endpoints) {
            state.timeout(SystemClock.elapsedRealtime())?.let { finish(it); return }
            if (!state.pending) return
            if (!binder.isBinderAlive) {
                state.died(id, SystemClock.elapsedRealtime())?.let(::finish)
                continue
            }
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(FacebookRestartProtocol.DESCRIPTOR)
                data.writeString(nonce)
                check(binder.transact(FacebookRestartProtocol.TRANSACTION_STOP, data, null, IBinder.FLAG_ONEWAY))
            } catch (_: DeadObjectException) {
                state.died(id, SystemClock.elapsedRealtime())?.let(::finish)
            } catch (_: Exception) {
                if (!binder.isBinderAlive) state.died(id, SystemClock.elapsedRealtime())?.let(::finish)
                else state.fail()?.let(::finish)
            } finally {
                data.recycle()
            }
            if (!state.pending) return
        }
        state.arm(SystemClock.elapsedRealtime())?.let(::finish)
    }

    private fun finish(completion: RestartWaitState.Completion) {
        cleanup()
        val result = callback
        callback = null
        result?.invoke(Result.valueOf(completion.name))
    }

    /** Call on the main thread when the request's owner goes away. */
    fun cancel() {
        state.cancel()
        callback = null
        cleanup()
    }

    private fun cleanup() {
        main.removeCallbacks(timeout)
        for (watch in watches.values) runCatching { watch.binder.unlinkToDeath(watch.recipient, 0) }
        watches.clear()
    }
}
