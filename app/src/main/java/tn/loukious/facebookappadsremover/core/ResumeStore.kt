package tn.loukious.facebookappadsremover.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resume-position store — port of the mod's `X.iJ8P7k7CqYKf8TGkcVsI`
 * (original mod v1.4.1, libnc.so).
 *
 * The mod kept three `ConcurrentHashMap<String, JSONObject>` maps keyed by
 * videoId, a single-thread `ScheduledExecutorService` for flushes and a
 * `File` for persistence. Its store file and entry key names were decoded
 * 2026-09-01 from the WatchedVideoStore state strings:
 *
 *   file:  APKMODDONE/…/WatchedVideos.txt
 *   keys:  resumePositionMs, positionUpdatedAt (+ historyFallbackAt,
 *          graphAttemptedAt, graphDescription, graphCache_v1 for the
 *          watched-list UI, which is a separate feature)
 *
 * The port keeps the decoded file name and the two keys the resume arm
 * actually reads/writes; the graph-cache keys belong to the deferred
 * watched-list panel (task #28 territory) and are not written here.
 *
 * Behaviour mirrors the decoded save/restore rules:
 *  - positions below 1000 ms are never saved (qsJG/EQRQ clamp);
 *  - a re-open of the same video within 5000 ms refreshes the entry
 *    instead of resetting it (QnHm store-refresh runnable @237193).
 */
object ResumeStore {

    private const val TAG = "FBAR.ResumeStore"

    /** Mod: WatchedVideos.txt (decoded state-key family). */
    private const val FILE_NAME = "WatchedVideos.txt"

    /** Mod layout: APKMODDONE/<feature> under the FB app's dataDir. */
    private const val DIR_NAME = "APKMODDONE/VideoResume"

    /** Decoded store keys (mod: WatchedVideoStore). */
    private const val KEY_POSITION = "resumePositionMs"
    private const val KEY_UPDATED = "positionUpdatedAt"

    /** Mod: QnHm's re-open window — 0 ≤ now − ts ≤ 5000 keeps the entry. */
    const val REOPEN_WINDOW_MS = 5000L

    /** Port hygiene (NOT decoded mod behaviour): drop entries older than 30
     *  days so the file cannot grow unbounded across sessions. */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    class Entry(val positionMs: Long, val updatedAt: Long)

    private val loaded = AtomicBoolean(false)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var storeFile: File? = null

    /** Idempotent; call from ModuleMain before any hook touches the store. */
    fun init(context: Context) {
        if (!loaded.compareAndSet(false, true)) return
        val file = File(File(context.dataDir, DIR_NAME), FILE_NAME)
        storeFile = file
        runCatching {
            if (file.isFile) {
                val root = JSONObject(file.readText())
                val now = System.currentTimeMillis()
                for (key in root.keys()) {
                    val obj = root.optJSONObject(key) ?: continue
                    val pos = obj.optLong(KEY_POSITION, 0L)
                    val ts = obj.optLong(KEY_UPDATED, 0L)
                    if (pos >= 1000L && now - ts <= MAX_AGE_MS) {
                        entries[key] = Entry(pos, ts)
                    }
                }
                L.i(TAG, "loaded ${entries.size} resume entr(y/ies) from ${file.path}")
            }
        }.onFailure { L.w(TAG, "store load failed (${file.path})", it) }
    }

    fun get(videoId: String): Entry? = entries[videoId]

    /** True when the entry exists and was updated within the re-open window. */
    fun isRecent(videoId: String, now: Long = System.currentTimeMillis()): Boolean {
        val e = entries[videoId] ?: return false
        val delta = now - e.updatedAt
        return delta in 0..REOPEN_WINDOW_MS
    }

    /**
     * Saves/updates a position (mod: qsJGnbanr07zUGNprRk → EQRQ save path).
     * Positions under 1000 ms are ignored — the mod's clamp.
     */
    fun put(videoId: String, positionMs: Long) {
        if (positionMs < 1000L) return
        entries[videoId] = Entry(positionMs, System.currentTimeMillis())
        scheduleFlush()
    }

    /** A deliberate seek near the beginning must clear an older far-ahead
     * resume point; ignoring <1000 ms would make the next open jump forward.
     */
    fun remove(videoId: String) {
        if (entries.remove(videoId) != null) scheduleFlush()
    }

    /**
     * Refreshes only the timestamp (mod: QnHm's re-save of an unchanged
     * entry when the same video restarts within the 5 s window).
     */
    fun touch(videoId: String) {
        val e = entries[videoId] ?: return
        entries[videoId] = Entry(e.positionMs, System.currentTimeMillis())
        scheduleFlush()
    }

    private fun scheduleFlush() {
        val file = storeFile ?: return
        io.execute {
            runCatching {
                val root = JSONObject()
                for ((id, e) in entries) {
                    root.put(id, JSONObject()
                        .put(KEY_POSITION, e.positionMs)
                        .put(KEY_UPDATED, e.updatedAt))
                }
                file.parentFile?.mkdirs()
                file.writeText(root.toString())
            }.onFailure { L.w(TAG, "store flush failed", it) }
        }
    }
}
