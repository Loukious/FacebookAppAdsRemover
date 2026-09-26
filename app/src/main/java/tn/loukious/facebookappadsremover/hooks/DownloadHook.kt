package tn.loukious.facebookappadsremover.hooks

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * M4 media download — port of the original mod's video-download pipeline.
 *
 * Reversed mod flow (libnc.so.c, all regions indexed in
 * E:\Apps\Reversing\Java\FB\Mod\decrypted-index.md "M4" sections):
 *
 *  1. CAPTURE — oRfibdnyvv3dEUIoNSY0$Hk8o installer (1297516–1297788) hooks ALL
 *     non-abstract declared methods of the video-player container view (mod:
 *     X.XnS; stock 576.0.0.42.73: AbstractC50323OAd "X.OAd", DexKit anchor
 *     "has already been attached to a RichVideoPlayer"). The after-callback
 *     (B0ZaivReUE4KG70LvZab, 16380–22320) scans param.args for one whose
 *     toString() contains "VideoId: " and != "VideoId: null" — a
 *     RichVideoPlayerParams whose toString embeds the GraphQLMedia dump.
 *     It writes the raw dump to <dataDir>/APKMODDONE/DownloadVideo/AllData.txt
 *     and extracts the quality-URL ladder.
 *
 *  2. CONTEXTUAL UI — [ContextualDownloadHook] follows Morphe's native
 *     Facebook surfaces: a real Download button is appended to the active
 *     reel's UDD sidebar, while stories reuse Facebook's own Save row. Both
 *     actions carry the tapped item's player/card into this downloader, so
 *     prefetched neighbouring media cannot redirect the action.
 *
 *  3. QUICK VIDEO DOWNLOAD DIALOG — card tap (the mod's card action,
 *     GshsfL21ZSKG5nJgp93s, 126093) fetches the desktop page (E1s6) → the
 *     aIv5$bsrnc.run dialog (1068984): HD video / SD video / every MPD
 *     rendition "%s video" / Audio rows with quick_link.option.* subtitles,
 *     HD preselected, bottom row "Copy URL" / "Preview" / "Download".
 *     (The mod's card SD/HD buttons, eFLd3885l8N8rPYV87zB @ 1146430, are not
 *     ported: 576 dumps carry a single progressive URL, so they could only
 *     fail — see docs/unported-hooks.md.)
 *     Item click (YJMDIpfpsAneohZjKEuU.onClick, 1056161): append "?dl=1"/
 *     "&dl=1" if missing, toast "quick_link.toast.downloading", dismiss,
 *     then dispatch: tag "mp4_no_audio" (DASH video-only) →
 *     Ezo4.AdjzonOq0OhXgiiuU6H(url, audioUrl); any other tag →
 *     Ezo4.yhNqDcnFVKtPAnsZdXH(url, tag).
 *
 *  4. DASH DISPATCH — Ezo4.AdjzonOq0OhXgiiuU6H (77145): audio empty → direct
 *     download; both present → "VIDEO HAS NO AUDIO" choice dialog
 *     (media.mix.choice.*): "Mix audio" → local mux (see 5), "Download
 *     without audio" → direct download.
 *
 *  5. MIX — the mod POSTs {video_url, audio_url, music_volume:1, s:true} to
 *     https://tonghopgaixinh.xyz with header X-API-Key <decrypted 64-hex
 *     key>, consumed as an NDJSON/SSE stream (accepted / s / progress /
 *     error / complete events) with a progress dialog, then a success dialog
 *     whose Download button runs the direct downloader. That service rotated
 *     its key on 2026-08-31 (v1.4.1 key revoked, HTTP 401 invalid_api_key —
 *     see docs/blockers.md), so the port muxes LOCALLY: download both
 *     streams, remux into one MP4 via MediaExtractor + MediaMuxer (no
 *     server, no re-encoding, no upload). MixService below preserves the
 *     reversed wire protocol for when a newer mod build yields the rotated
 *     key; LocalMux is the live path.
 *
 *  6. DOWNLOADER — Ezo4.yhNqDcnFVKtPAnsZdXH(url, ext): with the
 *     "download with browser" preference ON (mod default) fire ACTION_VIEW so
 *     a browser/download manager grabs it; otherwise stream to public
 *     Movies/FB_Download/FB_yyyyMMdd_HHmmss_SSS.ext.
 */
object DownloadHook {

    private const val TAG = "FBAR.Download"

    /**
     * Anchor literal in the video-player container class (mod: X.XnS; stock
     * 576.0.0.42.73: AbstractC50323OAd "X.OAd") — the RichVideoPlayer
     * double-attach guard makes the class findable via DexKit.
     */
    private const val CLASS_ANCHOR = "has already been attached to a RichVideoPlayer"

    /** Cache key for the discovered class name (MethodCache.store classes map). */
    const val CACHE_KEY = "media.playerContainer"

    /**
     * Cache key for the renamed media-setup capture class (576: C90164w8).
     * The mod's capture regexed the ImmutableMap {GraphQLMedia=…,
     * NeedCenteringKey=…} that a container-method arg carried; on 576 that map
     * is BUILT inside the video-setup controller (the only class using both
     * "GraphQLMedia" and "NeedCenteringKey" literals), so the hook moves there.
     */
    const val MEDIA_CACHE_KEY = "media.videoSetup"

    /** Stable video-engine params class (no DexKit — resolves by name). */
    private const val PARAMS_CLASS = "com.facebook.video.engine.api.VideoPlayerParams"

    /** Stable video-engine source class — the params' only field of this type. */
    private const val SOURCE_CLASS = "com.facebook.video.engine.api.VideoDataSource"

    /** Stable GraphQL media class — the setup controller's media field type. */
    private const val MEDIA_CLASS = "com.facebook.graphql.model.GraphQLMedia"

    /** Mod: APKMODDONE/DownloadVideo under the app's dataDir. */
    private const val DIR_NAME = "APKMODDONE/DownloadVideo"

    /** Mod DAT_00fcea18/00fcea58 — RichVideoPlayerParams arg trigger. */
    private const val VIDEO_ID_MARKER = "VideoId: "
    private const val NULL_MARKER = "null"

    /** Mod DAT_00fceb18 — main regex extracting the GraphQLMedia dump. */
    private val GRAPHQL_MEDIA_REGEX =
        Regex("GraphQLMedia=([\\s\\S]+?), NeedCenteringKey=(true|false)")

    /** Mod quality ladder (DAT_00fcf098 … 00fcf358, decrypted-index.md). */
    private val QUALITY_KEYS = listOf(
        "playable_url",
        "playable_url_quality_sd",
        "playable_url_quality_hd",
        "browser_native_sd_url",
        "browser_native_hd_url",
        "original_download_url_hd",
        "preferredPlayableUrlString",
        "playableUrlHdString",
        "sphericalPlayableUrlSdString",
        "sphericalPlayableUrlHdString",
    )

    /**
     * DASH video-only URL keys — the mod tags these items "mp4_no_audio"
     * (DAT_010b3558) because Facebook serves them without an audio track;
     * progressive playable_url* items carry audio.
     */
    private val NO_AUDIO_KEYS = setOf(
        "browser_native_sd_url",
        "browser_native_hd_url",
        "original_download_url_hd",
    )

    /** Mod DAT_010ca418 — the desktop watch URL the qualities fetcher GETs. */
    private const val WATCH_PREFIX = "https://www.facebook.com/watch/?v="

    // ---- Mix service constants (mod: DAT_00fdc258 / DAT_00fdc1d8 / blob 00fdc218) ----
    // SERVER ROTATION (verified 2026-08-31): the mod's v1.4.1 endpoint
    // /facebook-mix/api.php now 404s; the service moved to /api.php (same
    // NDJSON protocol + X-API-Key header) but the v1.4.1 key is revoked
    // ("invalid_api_key", HTTP 401). The new key is published by the server
    // ONLY as the encrypted "AMD1:" base64 blob on the domain root (4688
    // bytes, AES-class entropy — decryptable by newer builds of the original mod,
    // not by v1.4.1 which has no root-config fetch). Until a newer mod APK
    // is reversed for the new key, mix requests will 401 — the port surfaces
    // that reason verbatim instead of the old generic toast.
    private const val MIX_ENDPOINT = "https://tonghopgaixinh.xyz/api.php"
    private const val MIX_API_KEY = "456be4916618bd3ce3a45307634c9e94097a7c00c908d4f3a1f3e068b3430738"

    /** Download history lives in the FB process's own prefs (written by the
     *  module itself, so remote preferences — read-only — can't hold it). */
    private const val PREFS_NAME = "fbar_download"
    private const val KEY_HISTORY = "history"

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())

    // One executor per workload class: the card list enqueues a page fetch for
    // EVERY card it shows (~15 sequential network round-trips on io), and a
    // shared single thread starved the thumbnail decodes behind them — cards
    // sat gray until every fetch finished. Thumbnails and downloads/mix get
    // their own threads so they never queue behind the fetch storm.
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbIo: ExecutorService = Executors.newSingleThreadExecutor()
    private val dlIo: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Decoded card thumbnails, keyed by album-art URL or "frame:<videoId>".
     * The mod extracts videoThumbnail once at capture time and persists it in
     * VideoPlaylist.json, so its list always serves the image synchronously —
     * no per-open fetch. The port decodes lazily but caches at hook level the
     * same way: a bitmap decoded once keeps serving every later open of the
     * list, instead of re-fetching per dialog like the previous per-adapter
     * cache did.
     */
    private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val thumbRequested = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** The hook handles (mod keeps one callback across all hooked methods). */
    private val hookHandles = ArrayList<XposedInterface.HookHandle>()

    /** Current activity tracker — the mod's X/D92k.solarOrbitBridge(). */
    @Volatile private var currentActivity: Activity? = null

    /** Latest captured video (mod: pO4TjBxQp2IFO1UyrtSF.zkQ4kenQD4EJspRyMc8). */
    @Volatile private var current: VideoData? = null

    /**
     * Ring of the most recent captures, newest first. The mod's download
     * button shows "most 15 recently viewed videos" and lets the user pick —
     * the last capture is NOT necessarily the video on screen, because the
     * media-setup controller fires for prefetched/offscreen videos too.
     */
    @Volatile private var recentVideos: List<VideoData> = emptyList()

    /**
     * Captures indexed by their real video id. Facebook constructs players for
     * upcoming reels/stories before they are visible, so "the last capture" is
     * not a safe answer to a UI click. Contextual download actions always
     * resolve the id carried by the visible item through this map instead.
     */
    private val videosById = object : LinkedHashMap<String, VideoData>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoData>?): Boolean =
            size > 48
    }

    private val videosByIdLock = Any()

    private fun captureRichness(data: VideoData): Int =
        data.qualities.size +
            (data.playlist?.videos?.size ?: 0) * 10 +
            (data.playlist?.audios?.size ?: 0) * 4 +
            (if (data.mediaDump.isNotEmpty()) 100 else 0)

    private fun registerCapture(data: VideoData) {
        // A constructor capture has only the progressive pair, while the
        // later media-setup capture may carry the whole DASH playlist. Never
        // let that richer record be replaced by a later minimal observation.
        val stored = synchronized(videosByIdLock) {
            val old = videosById[data.videoId]
            val chosen = if (old != null && captureRichness(old) > captureRichness(data)) {
                old
            } else {
                data
            }
            videosById[data.videoId] = chosen
            chosen
        }
        current = stored
        recentVideos = buildList {
            add(stored)
            for (v in recentVideos) {
                if (v.videoId != stored.videoId && size < 15) add(v)
            }
        }
    }

    private fun videoById(videoId: String?): VideoData? {
        if (videoId.isNullOrBlank()) return null
        return synchronized(videosByIdLock) { videosById[videoId] }
    }

    /** The id currently reported by the player that actually started playback. */
    @Volatile private var activeVideoId: String? = null

    // ------------------------------------------------------------------ data

    /** One quality option in the quick-download dialog (mod: aIv5$bsrnc). */
    data class Quality(val label: String, val url: String, val tag: String)

    /** A captured video (mod's per-video JSON store, incl. videoThumbnail). */
    class VideoData(
        val videoId: String,
        val mediaDump: String,
        val qualities: List<Quality>,
        val audioUrl: String?,
        val postUrl: String,
        val thumbnailUrl: String? = null,
        // The DASH manifest embedded in the GraphQLMedia dump ("playlist" —
        // the mod's instant many-qualities source; see parsePlaylist below).
        val playlist: PlaylistData? = null,
    )

    /**
     * One DASH MPD Representation — the exact entry shape the mod persists to
     * its VideoPlaylist.json (verified against the mod's own live file,
     * APKMODDONE/DownloadVideo/VideoPlaylist.json on com.facebook.katanb):
     *
     *   video: {id, bandwidth, codecs, width, height, qualityLabel, BaseURL}
     *   audio: {id, bandwidth, codecs, audioSamplingRate, BaseURL}
     *
     * All fields are attr() reads, so absent attributes arrive as "" — exactly
     * what the mod stores (it never writes null).
     */
    class PlaylistRep(
        val id: String,
        val bandwidth: String,
        val codecs: String,
        val width: String,
        val height: String,
        val qualityLabel: String,
        val audioSamplingRate: String,
        val baseUrl: String,
    )

    /**
     * The parsed MPD: every video Representation (the "See more" many-qualities
     * list — 360p…1080p per codec variant) plus every audio Representation,
     * both sorted ascending by bandwidth like the mod's comparator.
     * [defaultAudioUrl] is the FIRST non-empty audio BaseURL in document order
     * — chosen during the walk, before the lists get sorted, so it is not
     * necessarily audios[0] afterwards.
     */
    class PlaylistData(
        val videos: List<PlaylistRep>,
        val audios: List<PlaylistRep>,
        val defaultAudioUrl: String?,
    )

    /**
     * The mod's playlist parse — ported 1:1 from libnc.so
     * Java_X_Ezo4LOWOaf1b7errhR7A_Rn0LbcxLisWuSI9YThk (reversed 2026-08-31).
     * It is a jsoup XML DOM walk, NOT regex:
     *
     *   Document doc = Jsoup.parse(mpd, "", Parser.xmlParser());
     *   for (Element set : doc.select("AdaptationSet")) {
     *     String contentType = set.attr("contentType");
     *     for (Element rep : set.select("Representation")) {
     *       if ("video".equals(contentType))          // id, bandwidth, codecs,
     *         o.put("width", rep.attr("width"))       // width, height,
     *            .put("height", …)                    // qualityLabel ← attr("FBQualityLabel"),
     *            .put("qualityLabel", rep.attr("FBQualityLabel"))
     *            .put("BaseURL", rep.select("BaseURL").text());
     *       else if ("audio".equals(contentType))     // id, bandwidth, codecs,
     *         o.put("audioSamplingRate", …)           // audioSamplingRate, BaseURL
     *            .put("BaseURL", rep.select("BaseURL").text());
     *     }
     *   }
     *   Collections.sort(video, cmp); Collections.sort(audio, cmp);
     *   // cmp (Ezo4LOWOaf1b7errhR7A$EKUKHusVr05bae6gyJv3):
     *   //   parseInt(o1.optString("bandwidth","0")) - parseInt(o2.optString("bandwidth","0"))
     *   result.put("video", …).put("audio", …).put("defaultAudioUrl", …);
     *
     * defaultAudioUrl = the first audio BaseURL for which
     * JvXXhNaRKzGCpkFxz5M (a non-empty check) is true while the current
     * default is still empty.
     */
    private fun parsePlaylist(mediaDump: String): PlaylistData? {
        val mpd = runCatching {
            JSONObject(mediaDump).optString("playlist")
        }.getOrNull()
        if (mpd.isNullOrBlank()) return null
        val doc = runCatching {
            org.jsoup.Jsoup.parse(mpd, "", org.jsoup.parser.Parser.xmlParser())
        }.getOrNull() ?: return null

        val videos = ArrayList<PlaylistRep>()
        val audios = ArrayList<PlaylistRep>()
        var defaultAudioUrl: String? = null
        for (set in doc.select("AdaptationSet")) {
            val contentType = set.attr("contentType")
            for (rep in set.select("Representation")) {
                if (contentType == "video") {
                    videos.add(PlaylistRep(
                        id = rep.attr("id"),
                        bandwidth = rep.attr("bandwidth"),
                        codecs = rep.attr("codecs"),
                        width = rep.attr("width"),
                        height = rep.attr("height"),
                        qualityLabel = rep.attr("FBQualityLabel"),
                        audioSamplingRate = "",
                        baseUrl = rep.select("BaseURL").text(),
                    ))
                } else if (contentType == "audio") {
                    val baseUrl = rep.select("BaseURL").text()
                    if (defaultAudioUrl.isNullOrEmpty() && baseUrl.isNotEmpty()) {
                        defaultAudioUrl = baseUrl
                    }
                    audios.add(PlaylistRep(
                        id = rep.attr("id"),
                        bandwidth = rep.attr("bandwidth"),
                        codecs = rep.attr("codecs"),
                        width = "",
                        height = "",
                        qualityLabel = "",
                        audioSamplingRate = rep.attr("audioSamplingRate"),
                        baseUrl = baseUrl,
                    ))
                }
            }
        }
        if (videos.isEmpty() && audios.isEmpty()) return null
        // The mod's comparator: parseInt(bandwidth) difference, ascending —
        // NumberFormatException (empty/garbage attr) counts as 0.
        val byBandwidth = compareBy<PlaylistRep> { it.bandwidth.toIntOrNull() ?: 0 }
        videos.sortWith(byBandwidth)
        audios.sortWith(byBandwidth)
        return PlaylistData(videos, audios, defaultAudioUrl)
    }

    // ------------------------------------------------------------- lifecycle

    fun init(context: Context) {
        appContext = context.applicationContext
        if (context is Application) {
            runCatching {
                context.registerActivityLifecycleCallbacks(ActivityTracker())
            }.onFailure { L.w(TAG, "activity tracker registration failed", it) }
        }
    }

    /** Activity tracker — port of X/D92k.solarOrbitBridge(). */
    private class ActivityTracker : Application.ActivityLifecycleCallbacks {

        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
        }

        override fun onActivityPaused(activity: Activity) {
            if (currentActivity === activity) currentActivity = null
        }

        override fun onActivityDestroyed(a: Activity) {}

        override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
    }

    // ------------------------------------------------ contextual media binding

    /** Called by the real player start hook; unlike construction, this means the item is visible. */
    fun onPlaybackStarted(videoId: String?) {
        activeVideoId = videoId?.takeIf { it.isNotBlank() && it != NULL_MARKER }
    }

    /** Removes the reel action only when the player that owns it actually stopped. */
    fun onPlaybackStopped(videoId: String?) {
        if (videoId != null && activeVideoId == videoId) activeVideoId = null
    }

    /** Native reel-sidebar entry point: open this item's existing quality picker. */
    fun openReelDownload(context: Context?, playerHost: Any?): Boolean {
        if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return false
        val videoId = videoIdFromPlayerParamsIn(playerHost) ?: return false
        val data = videoById(videoId) ?: return false
        val activity = context as? Activity ?: currentActivity ?: return false
        main.post { openQuickDownload(activity, data) }
        return true
    }

    /** Native reel-sidebar entry point for the existing Page repost flow. */
    fun openReelRepost(context: Context?, playerHost: Any?): Boolean {
        if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return false
        val videoId = videoIdFromPlayerParamsIn(playerHost) ?: return false
        val data = videoById(videoId) ?: return false
        val activity = context as? Activity ?: currentActivity ?: return false
        main.post { Repost.open(activity, data) }
        return true
    }

    /**
     * Story-menu entry point. The StoryCard carries the visible video's id;
     * resolve that id to the capture made by VideoPlayerParams and open the
     * existing per-video quality dialog. The active player is a safe fallback
     * because it represents playback, not prefetch construction.
     */
    fun openStoryDownload(context: Context?, storyCard: Any?): Boolean {
        if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return false
        val data = storyVideoData(storyCard) ?: return false
        val activity = context as? Activity ?: currentActivity ?: return false
        main.post { openQuickDownload(activity, data) }
        return true
    }

    /** Story three-dot entry point for the existing Page repost flow. */
    fun openStoryRepost(context: Context?, storyCard: Any?): Boolean {
        if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return false
        val data = storyVideoData(storyCard) ?: return false
        val activity = context as? Activity ?: currentActivity ?: return false
        main.post { Repost.open(activity, data) }
        return true
    }

    private fun storyVideoData(storyCard: Any?): VideoData? {
        val ids = videoIdsIn(storyCard)
        val active = activeVideoId
        return active?.takeIf(ids::contains)?.let(::videoById)
            ?: ids.firstNotNullOfOrNull(::videoById)
            ?: storyDataFromCard(storyCard, ids.firstOrNull())
    }

    /**
     * Morphe's fallback for stories: the card itself carries the progressive
     * video URL even when the richer player capture has not landed yet.
     */
    private fun storyDataFromCard(storyCard: Any?, knownId: String?): VideoData? {
        storyCard ?: return null
        val media = runCatching {
            storyCard.javaClass.methods.firstOrNull {
                it.name == "getMedia" && it.parameterCount == 0
            }?.invoke(storyCard)
        }.getOrNull()
        val urls = collectHttpUrls(media ?: storyCard, if (media != null) 1 else 2)
        val videos = urls.filter(::looksLikeVideoFile).distinct()
        if (videos.isEmpty()) return null
        val id = knownId ?: "story_${System.identityHashCode(storyCard)}"
        val qualities = videos.mapIndexed { index, url ->
            Quality(if (index == 0) "Story video" else "Story video ${index + 1}", url, "mp4")
        }
        return VideoData(
            videoId = id,
            mediaDump = "",
            qualities = qualities,
            audioUrl = null,
            postUrl = "",
            thumbnailUrl = null,
            playlist = null,
        )
    }

    private fun looksLikeVideoFile(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.substringBefore('?').endsWith(".mp4") ||
            lower.contains("video%2fmp4") ||
            lower.contains("video/mp4")
    }

    private fun collectHttpUrls(root: Any?, maxDepth: Int): List<String> {
        root ?: return emptyList()
        val out = LinkedHashSet<String>()
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val queue = java.util.ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        seen[root] = true
        var nodes = 0

        fun add(value: String) {
            if (value.startsWith("http://") || value.startsWith("https://")) out.add(value)
        }

        while (queue.isNotEmpty() && nodes < 384) {
            val (node, depth) = queue.removeFirst()
            nodes++
            when (node) {
                is CharSequence -> {
                    add(node.toString())
                    continue
                }
                is android.net.Uri -> {
                    add(node.toString())
                    continue
                }
                is Iterable<*> -> {
                    if (depth < maxDepth) node.forEach { value ->
                        if (value != null && seen.put(value, true) == null) {
                            queue.add(value to (depth + 1))
                        }
                    }
                    continue
                }
            }
            if (depth >= maxDepth) continue

            var type: Class<*>? = node.javaClass
            while (type != null && type != Any::class.java) {
                val current = type
                for (field in runCatching { current.declaredFields }.getOrElse { emptyArray() }) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(node)
                    }.getOrNull() ?: continue
                    when (value) {
                        is CharSequence -> add(value.toString())
                        is android.net.Uri -> add(value.toString())
                        else -> {
                            val name = value.javaClass.name
                            if ((name.startsWith("com.facebook.") || name.startsWith("X.") ||
                                    value is Iterable<*>) && seen.put(value, true) == null
                            ) {
                                queue.add(value to (depth + 1))
                            }
                        }
                    }
                }
                type = current.superclass
            }
        }
        return out.toList()
    }

    /**
     * Finds short numeric ids reachable from one Facebook model/component.
     * This mirrors Morphe's bounded StoryCard walk: depth 3, 512 nodes, and
     * only Facebook/Redex objects are traversed. URL strings are ignored so
     * timestamps or CDN path numbers cannot be mistaken for a video id.
     */
    private fun videoIdsIn(root: Any?): List<String> {
        root ?: return emptyList()
        val found = LinkedHashSet<String>()
        val idPattern = Regex("\\d{8,20}")
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val queue = java.util.ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        seen[root] = true
        var nodes = 0

        fun addText(text: String) {
            if (text.length > 40 || text.startsWith("http://") || text.startsWith("https://")) return
            idPattern.findAll(text).forEach { found.add(it.value) }
        }

        while (queue.isNotEmpty() && nodes < 512) {
            val (node, depth) = queue.removeFirst()
            nodes++

            if (node.javaClass.name == PARAMS_CLASS) {
                runCatching { node.toString().removePrefix(VIDEO_ID_MARKER) }
                    .getOrNull()?.let(::addText)
            }

            var type: Class<*>? = node.javaClass
            while (type != null && type != Any::class.java) {
                val currentType = type
                val fields = runCatching { currentType.declaredFields }.getOrElse { emptyArray() }
                for (field in fields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(node)
                    }.getOrNull() ?: continue
                    if (value is CharSequence) {
                        addText(value.toString())
                        continue
                    }
                    if (depth >= 3) continue
                    val name = value.javaClass.name
                    if (!name.startsWith("com.facebook.") && !name.startsWith("X.")) continue
                    if (seen.put(value, true) != null) continue
                    queue.add(value to (depth + 1))
                }
                type = currentType.superclass
            }
        }
        return found.toList()
    }

    /**
     * Reels have a stronger signal than stories: Morphe's sidebar object walk
     * reaches VideoPlayerParams directly. Use only that typed object here so a
     * profile/story/user id elsewhere in the component can never mark a reel.
     */
    private fun videoIdFromPlayerParamsIn(root: Any?): String? {
        root ?: return null
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val queue = java.util.ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)
        seen[root] = true
        var nodes = 0

        while (queue.isNotEmpty() && nodes < 256) {
            val (node, depth) = queue.removeFirst()
            nodes++
            if (node.javaClass.name == PARAMS_CLASS) {
                return runCatching {
                    node.toString().removePrefix(VIDEO_ID_MARKER)
                        .takeIf { it.isNotBlank() && it != NULL_MARKER }
                }.getOrNull()
            }
            if (depth >= 3) continue

            var type: Class<*>? = node.javaClass
            while (type != null && type != Any::class.java) {
                val currentType = type
                for (field in runCatching { currentType.declaredFields }.getOrElse { emptyArray() }) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(node)
                    }.getOrNull() ?: continue
                    val name = value.javaClass.name
                    if (name != PARAMS_CLASS &&
                        !name.startsWith("com.facebook.") && !name.startsWith("X.")) continue
                    if (seen.put(value, true) != null) continue
                    queue.add(value to (depth + 1))
                }
                type = currentType.superclass
            }
        }
        return null
    }

    // -------------------------------------------------------------- thumbnails

    /**
     * Poster-frame URL for a captured video — the mod's VideoPlaylist.json
     * kept one per entry (videoThumbnail). 576's GraphQL Video JSON for this
     * surface leaves thumbnailImage an EMPTY Image shell (verified: 80/80
     * dumps, no Image object carries a uri); the one image URL every dump
     * does carry is music_album_art_uri — the soundtrack cover, square art.
     */
    private val THUMBNAIL_KEYS = arrayOf(
        "music_album_art_uri", "thumbnailImage", "preferred_thumbnail",
        "videoThumbnail", "image",
    )

    private fun extractThumbnail(mediaDump: String): String? {
        val json = runCatching { JSONObject(mediaDump) }.getOrNull() ?: return null
        for (key in THUMBNAIL_KEYS) {
            if (!json.has(key)) continue
            val uri = findImageUri(json.opt(key)) ?: continue
            return cleanUrl(uri).takeIf { it.startsWith("http") }
        }
        return findImageUri(json)?.let { cleanUrl(it).takeIf { u -> u.startsWith("http") } }
    }

    /** First image URI inside a JSON node (string members, objects, arrays). */
    private val IMAGE_EXT = Regex("\\.(jpg|jpeg|png|webp)")

    private fun findImageUri(node: Any?): String? {
        when (node) {
            is String ->
                return node.takeIf { it.startsWith("http") && IMAGE_EXT.containsMatchIn(it) }
            is JSONObject -> {
                val uri = node.optString("uri")
                if (uri.startsWith("http") && IMAGE_EXT.containsMatchIn(uri)) return uri
                for (k in node.keys()) {
                    findImageUri(node.opt(k))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findImageUri(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------ params ctor capture

    /**
     * 576-era capture: the RichVideoPlayerParams whose toString carried the
     * whole GraphQLMedia dump no longer exists — stock 576's
     * VideoPlayerParams.toString() is just "VideoId: <id>" (verified in
     * Sources2), and reels playback never touches the OAd container at all
     * (verified on-device: 46 hooked methods, zero calls during reels).
     *
     * The stable replacement is the video ENGINE's params object
     * com.facebook.video.engine.api.VideoPlayerParams — constructed for every
     * video in every surface (feed, reels, watch, stories). Everything is
     * resolved structurally (the field NAMES rotate per release):
     *  - videoId: params.toString() is exactly "VideoId: <id>";
     *  - source: the params' unique field of the stable type
     *    com.facebook.video.engine.api.VideoDataSource;
     *  - playback Uris: the source's exactly-three Uri-typed fields, in dex
     *    declaration order (sd, hd, ABR manifest) — identical layout in 576
     *    and 577.
     *
     * Installed on the probe-retry cadence like AccountHook — no DexKit.
     */
    fun installVideoParamsHook(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (paramCtorHandles.isNotEmpty()) return true
        val cls = runCatching {
            Class.forName(PARAMS_CLASS, false, classLoader)
        }.getOrNull() ?: return false
        var installed = 0
        for (ctor in cls.declaredConstructors) {
            runCatching {
                ctor.isAccessible = true
                paramCtorHandles.add(module.hook(ctor).intercept(ParamsCtorHook))
                installed++
            }.onFailure { L.w(TAG, "params ctor hook failed", it) }
        }
        if (installed == 0) {
            L.w(TAG, "no constructors hookable on $PARAMS_CLASS")
            return false
        }
        L.i(TAG, "VideoPlayerParams ctor hook installed ($installed ctor)")
        return true
    }

    private val paramCtorHandles = ArrayList<XposedInterface.HookHandle>()

    // ------------------------------------------- clipboard trigger (mod l6g hook)
    //
    // The mod's own trigger for the qualities fetch (oRfibdnyvv3dEUIoNSY0$
    // H4cuReyVMyd3apUPirC5.run, libnc.so.c 1297395):
    // findAndHookMethod(ClipboardManager, "setPrimaryClip", ClipData.class,
    // new l6gJTzHczDDiahs77aMq()). The hook's beforeHookedMethod (1259312)
    // reads the clip text, and when it starts with http:// or https:// it
    // toasts quick_link.toast.loading and hands the URL to the E1s6 fetcher —
    // i.e. copying any video link in FB's share menu ("Copy link") opens the
    // full-qualities picker.

    private var clipboardHandle: XposedInterface.HookHandle? = null

    fun installClipboardHook(module: XposedInterface): Boolean {
        clipboardHandle?.let { return true }
        return runCatching {
            val m = android.content.ClipboardManager::class.java
                .getDeclaredMethod("setPrimaryClip", android.content.ClipData::class.java)
            clipboardHandle = module.hook(m).intercept(ClipboardHook)
            L.i(TAG, "clipboard qualities trigger installed (setPrimaryClip)")
            true
        }.onFailure {
            L.w(TAG, "clipboard hook failed", it); false
        }.getOrDefault(false)
    }

    /** l6gJTzHczDDiahs77aMq port: link copied → fetch → quick-download dialog. */
    private object ClipboardHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching { onClip(chain.args.firstOrNull()) }
                .onFailure { L.w(TAG, "clipboard scan failed", it) }
            return result
        }

        private fun onClip(clip: Any?) {
            // Mod gate: encrypted pref taiNhanh (DAT_010c5e18, decoded
            // 2026-09-06) — default FALSE, like the original.
            if (!Settings.getBoolean(Settings.DOWNLOAD_CLIPBOARD, false)) return
            if (clip !is android.content.ClipData) return
            val text = clip.getItemAt(0)?.text?.toString() ?: return
            if (!text.startsWith("http://") && !text.startsWith("https://")) return
            val activity = currentActivity ?: return
            Toast.makeText(activity, "Getting available qualities…", Toast.LENGTH_SHORT)
                .show() // quick_link.toast.loading
            io.execute {
                val fetched = runCatching { fetchQualities(text) }
                    .onFailure { L.w(TAG, "clipboard qualities fetch failed", it) }
                    .getOrNull()
                val items = quickItems(null, fetched)
                val audioUrl = quickAudioUrl(null, fetched)
                logFetchedLinks("clip:${text.takeLast(60)}", items, audioUrl)
                main.post {
                    if (items.isEmpty()) {
                        Toast.makeText(activity,
                            "No downloadable media was found.", Toast.LENGTH_SHORT)
                            .show() // quick_link.toast.no_media
                    } else {
                        showQuickDownloadDialog(activity, "clip:${text.takeLast(60)}",
                            items, audioUrl)
                    }
                }
            }
        }
    }

    /** Most recent params-ctor videoId (separate from the capture dedup state). */
    @Volatile private var lastParamsVideoId: String? = null

    /** Structural members of VideoPlayerParams/VideoDataSource (resolved once). */
    @Volatile private var sourceField: java.lang.reflect.Field? = null
    @Volatile private var sourceUriFields: List<java.lang.reflect.Field>? = null

    /** Captures every constructed VideoPlayerParams — the all-surface site. */
    private object ParamsCtorHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching { onParams(chain.thisObject) }
                .onFailure { L.w(TAG, "params ctor scan failed", it) }
            return result
        }

        /**
         * 576 all-surface capture: the video ENGINE builds one
         * VideoPlayerParams per video in every surface — feed, reels, watch,
         * stories. The mod's single X.XnS arg-scan covered every surface the
         * same way; 576's media-setup controller (onMediaSetup) does NOT
         * cover stories playback reliably, so this ctor is the equivalent
         * catch-all. All field reads are structural (see installVideoParamsHook).
         *
         * Media-setup carries the far richer dump (embedded playlist, quality
         * ladder, album art) and re-registers the same videoId on top of this
         * minimal capture (registerCapture dedups by id) — so both hooks can
         * fire for one video. An existing RICH capture is never degraded back
         * to this minimal one on re-watch.
         */
        private fun onParams(params: Any?) {
            params ?: return
            // VideoPlayerParams.toString() is exactly "VideoId: <id>".
            val id = runCatching { params.toString() }.getOrNull()
                ?.removePrefix(VIDEO_ID_MARKER)
                ?.takeIf { it.isNotBlank() && it != NULL_MARKER } ?: return
            if (id == lastParamsVideoId) return
            lastParamsVideoId = id
            val src = resolveSourceField(params)?.get(params) ?: return
            val uris = resolveSourceUriFields(src.javaClass)
                ?: run { L.w(TAG, "source Uri fields not resolvable — capture skipped"); return }
            // Dex declaration order: sd, hd, ABR manifest (stable layout).
            val sdUri = (uris[0].get(src) as? android.net.Uri)?.toString()
                ?.takeIf { it.startsWith("http") }
            val hdUri = (uris[1].get(src) as? android.net.Uri)?.toString()
                ?.takeIf { it.startsWith("http") }
            val manifestUri = (uris[2].get(src) as? android.net.Uri)?.toString()
            L.i(TAG, "PARAMS videoId=$id hd=${hdUri != null} sd=${sdUri != null} " +
                    "manifest=${manifestUri != null}")

            // The progressive pair alone is a capture (both carry audio on
            // every surface — no mix needed); the manifest alone is not.
            if (hdUri == null && sdUri == null) return
            if (recentVideos.any { it.videoId == id && it.mediaDump.isNotEmpty() }) return
            val qualities = ArrayList<Quality>()
            hdUri?.let { qualities.add(Quality("HD video", it, "mp4")) }
            sdUri?.let { qualities.add(Quality("SD video", it, "mp4")) }
            val data = VideoData(
                videoId = id,
                mediaDump = "",
                qualities = qualities,
                audioUrl = null,
                postUrl = WATCH_PREFIX + id,
                thumbnailUrl = null,
                playlist = null,
            )
            registerCapture(data)
            L.i(TAG, "captured video $id via params-ctor (${qualities.size} qualities)")
            logVideoLinks(data)
        }
    }

    /**
     * The params' unique field of the stable VideoDataSource type (field names
     * rotate per release; the type never does). Resolved once, then cached.
     */
    private fun resolveSourceField(params: Any): java.lang.reflect.Field? {
        sourceField?.let { return it }
        val srcCls = runCatching {
            Class.forName(SOURCE_CLASS, false, params.javaClass.classLoader)
        }.getOrNull() ?: run {
            L.w(TAG, "stable class resolve failed: $SOURCE_CLASS")
            return null
        }
        val matches = params.javaClass.declaredFields.filter { it.type == srcCls }
        if (matches.size != 1) {
            L.w(TAG, "source field not unique on ${params.javaClass.name}: ${matches.size} candidate(s)")
            return null
        }
        matches[0].isAccessible = true
        sourceField = matches[0]
        return matches[0]
    }

    /**
     * The source's exactly-three Uri-typed fields, in dex declaration order
     * (sd, hd, ABR manifest — the layout in both 576 and 577). Resolved once
     * per source class, then cached.
     */
    private fun resolveSourceUriFields(srcCls: Class<*>): List<java.lang.reflect.Field>? {
        sourceUriFields?.let { return it }
        val uris = srcCls.declaredFields.filter { it.type == android.net.Uri::class.java }
        if (uris.size != 3) {
            L.w(TAG, "expected 3 Uri fields on ${srcCls.name}, found ${uris.size}")
            return null
        }
        uris.forEach { it.isAccessible = true }
        sourceUriFields = uris
        return uris
    }

    /**
     * Finds the video-player container class via DexKit and installs the
     * capture hook (mod installer: oRfibdnyvv3dEUIoNSY0$Hk8o, 1297516).
     *
     * @return the class name when found, for the discovery cache.
     */    fun install(module: XposedInterface, bridge: org.luckypray.dexkit.DexKitBridge,
                classLoader: ClassLoader): String? {
        val hits = runCatching {
            bridge.findClass {
                matcher {
                    addUsingString(CLASS_ANCHOR, org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                }
            }
        }.getOrElse {
            L.w(TAG, "DexKit query failed for player container class", it)
            return null
        }
        // The anchor is the double-attach guard in the attach() method —
        // unique to the container class in this build.
        val className = hits.firstOrNull()?.name
            ?: run {
                L.w(TAG, "NOT_FOUND player container class (anchor: $CLASS_ANCHOR)")
                return null
            }
        L.i(TAG, "FOUND player container: $className (${hits.size} hit(s))")
        return if (hookPlayerClass(module, classLoader, className)) className else null
    }

    /** Cache-hit path: hook the previously discovered class directly. */
    fun installCached(module: XposedInterface, classLoader: ClassLoader,
                      className: String): Boolean = hookPlayerClass(module, classLoader, className)

    // ------------------------------------------------- media-setup capture (576)

    private val mediaSetupHandles = ArrayList<XposedInterface.HookHandle>()

    /** Resolved-once controller payload fields — by stable TYPE, not name. */
    @Volatile private var mediaParamsField: java.lang.reflect.Field? = null
    @Volatile private var mediaField: java.lang.reflect.Field? = null

    /** Last media-setup capture (videoId + wall time) for throttling. */
    @Volatile private var lastMediaVideoId: String? = null
    @Volatile private var lastMediaCaptureAt = 0L

    /**
     * 576 rename of the mod's capture site: the ImmutableMap
     * {GraphQLMedia=<TreeJNI dump>, …, NeedCenteringKey=true} that the mod's
     * callback regexed out of a container-method arg is now BUILT inside the
     * video-setup controller itself (Sources2 C90164w8.A1F, line 1152; the
     * only class in the dex using both literals). The controller keeps the
     * map's payload in instance fields, resolved structurally (names rotate):
     *
     *   media  = the unique field of stable type GraphQLMedia
     *            (TreeJNI; toString() = the quality-ladder dump —
     *             the exact text the mod's regex extracted)
     *   params = the unique field of stable type VideoPlayerParams
     *            (videoId via toString's "VideoId: <id>" shape)
     *
     * Same hook-all strategy as the mod's installer on the renamed class.
     *
     * @return the class name when found, for the discovery cache.
     */
    fun installMediaSetup(module: XposedInterface, bridge: org.luckypray.dexkit.DexKitBridge,
                          classLoader: ClassLoader): String? {
        val hits = runCatching {
            bridge.findClass {
                matcher {
                    addUsingString("GraphQLMedia", org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                    addUsingString("NeedCenteringKey", org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                }
            }
        }.getOrElse {
            L.w(TAG, "DexKit query failed for media-setup class", it)
            return null
        }
        val className = hits.firstOrNull()?.name
            ?: run {
                L.w(TAG, "NOT_FOUND media-setup class (anchors: GraphQLMedia+NeedCenteringKey)")
                return null
            }
        L.i(TAG, "FOUND media-setup controller: $className (${hits.size} hit(s))")
        return if (hookMediaSetupClass(module, classLoader, className)) className else null
    }

    /** Cache-hit path: hook the previously discovered controller directly. */
    fun installMediaSetupCached(module: XposedInterface, classLoader: ClassLoader,
                                className: String): Boolean =
        hookMediaSetupClass(module, classLoader, className)

    /**
     * Mod-faithful hook-all on the renamed capture class: every declared
     * non-abstract method gets the observer callback (the mod did exactly this
     * on X.XnS). The per-call gate is cheap — two cached field reads plus a
     * videoId compare — so the full GraphQLMedia dump is only ever built once
     * per new video.
     */
    private fun hookMediaSetupClass(module: XposedInterface, classLoader: ClassLoader,
                                    className: String): Boolean {
        if (mediaSetupHandles.isNotEmpty()) return true
        val cls = runCatching {
            Class.forName(className, false, classLoader)
        }.getOrNull() ?: run {
            L.w(TAG, "class resolve failed: $className")
            return false
        }
        // Resolve the payload fields up front — by stable type, so a name
        // rotation never silences the capture. Missing fields leave the hook
        // installed but capturing nothing (logged once).
        mediaField = uniqueFieldOfType(cls, MEDIA_CLASS, classLoader, "media")
        mediaParamsField = uniqueFieldOfType(cls, PARAMS_CLASS, classLoader, "params")
        if (mediaField == null || mediaParamsField == null) {
            L.w(TAG, "media-setup payload fields not found on $className " +
                    "(media=${mediaField != null}, params=${mediaParamsField != null})")
        }
        val targets = cls.declaredMethods.filter {
            !java.lang.reflect.Modifier.isAbstract(it.modifiers)
        }
        var installed = 0
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                mediaSetupHandles.add(module.hook(m).intercept(MediaSetupHooker))
                installed++
            }.onFailure { L.w(TAG, "hook failed on ${cls.name}.${m.name}", it) }
        }
        L.i(TAG, "media-setup capture hook installed on ${cls.name}: $installed method(s)")
        return installed > 0
    }

    /**
     * The controller's unique declared field of the given stable type —
     * field names rotate per release, the types never do.
     */
    private fun uniqueFieldOfType(cls: Class<*>, typeName: String,
                                  classLoader: ClassLoader, role: String): java.lang.reflect.Field? {
        val type = runCatching { Class.forName(typeName, false, classLoader) }.getOrNull()
            ?: run {
                L.w(TAG, "stable class resolve failed: $typeName")
                return null
            }
        val matches = cls.declaredFields.filter { it.type == type }
        if (matches.size != 1) {
            L.w(TAG, "$role field not unique on ${cls.name}: ${matches.size} candidate(s) of $typeName")
            return null
        }
        matches[0].isAccessible = true
        return matches[0]
    }

    /** After-method observer on the media-setup controller (never alters). */
    private object MediaSetupHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching { onMediaSetup(chain.thisObject) }
                .onFailure { L.w(TAG, "media-setup scan failed", it) }
            return result
        }
    }

    /**
     * Reads the controller's GraphQLMedia + VideoPlayerParams (both by stable
     * type) and runs the mod's extraction pipeline on the dump. videoId comes
     * from params.toString() ("VideoId: <id>") and falls back to the freshest
     * params-ctor observation when the controller instance was built before
     * playback params were attached.
     */
    private fun onMediaSetup(controller: Any?) {
        val paramsField = mediaParamsField ?: return
        val graphQlField = mediaField ?: return

        val params = runCatching { paramsField.get(controller) }.getOrNull()
        val videoId = params?.toString()?.removePrefix(VIDEO_ID_MARKER)
            ?.takeIf { it.isNotBlank() && it != NULL_MARKER }
            ?: lastParamsVideoId?.takeIf { it.isNotBlank() && it != NULL_MARKER }
            ?: return

        // Same video: re-capture at most every 5s (the mod's freshness gate).
        val now = android.os.SystemClock.elapsedRealtime()
        if (videoId == lastMediaVideoId && now - lastMediaCaptureAt < 5000L) return

        val media = runCatching { graphQlField.get(controller) }.getOrNull() ?: return
        val mediaDump = runCatching { media.toString() }.getOrNull() ?: return

        val playlist = parsePlaylist(mediaDump)
        val qualities = extractQualities(mediaDump)
        // A playlist alone is a valid capture (its representations become the
        // picker items even when the dump's ladder keys are absent).
        if (qualities.isEmpty() && playlist == null) return

        lastMediaVideoId = videoId
        lastMediaCaptureAt = now

        // The MPD's first audio representation is the mod's defaultAudioUrl —
        // the mix source for every DASH video-only quality.
        val audioUrl = playlist?.defaultAudioUrl ?: extractAudioUrl("", mediaDump)
        val data = VideoData(
            videoId = videoId,
            mediaDump = mediaDump,
            qualities = qualities,
            audioUrl = audioUrl,
            postUrl = WATCH_PREFIX + videoId,
            thumbnailUrl = extractThumbnail(mediaDump),
            playlist = playlist,
        )
        registerCapture(data)

        // Mod store writes (AllData.txt header mirrors the mod's
        // RichVideoPlayerParams text: "VideoId: <id>, GraphQLMedia=<dump>").
        io.execute { persist(videoId, "$VIDEO_ID_MARKER$videoId, GraphQLMedia=$mediaDump", mediaDump, data) }
        L.i(TAG, "captured video $videoId via media-setup " +
                "(${qualities.size} qualities, audio=${audioUrl != null}, " +
                "thumb=${data.thumbnailUrl != null}, " +
                "playlist=${playlist?.videos?.size ?: 0}v/${playlist?.audios?.size ?: 0}a)")
        logVideoLinks(data)
    }

    /**
     * Installs the capture hook on every declared non-abstract method of the
     * video-player container class (mod installer, libnc.so.c 1297516).
     * Returns false when the class is not resolvable yet (retry cadence).
     */
    private fun hookPlayerClass(module: XposedInterface, classLoader: ClassLoader,
                                className: String): Boolean {
        if (hookHandles.isNotEmpty()) return true
        val playerClass = runCatching {
            Class.forName(className, false, classLoader)
        }.getOrNull() ?: run {
            L.w(TAG, "class resolve failed: $className")
            return false
        }
        val targets = playerClass.declaredMethods.filter {
            !java.lang.reflect.Modifier.isAbstract(it.modifiers)
        }
        if (targets.isEmpty()) {
            L.w(TAG, "no non-abstract declared methods on ${playerClass.name}")
            return false
        }
        var installed = 0
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                hookHandles.add(module.hook(m).intercept(CaptureHooker))
                installed++
            }.onFailure { L.w(TAG, "hook failed on ${playerClass.name}.${m.name}", it) }
        }
        L.i(TAG, "capture hook installed on ${playerClass.name}: $installed method(s)")
        return installed > 0
    }

    /** B0ZaivReUE4KG70LvZab.afterHookedMethod port: observe, never alter. */
    private object CaptureHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            // The container declares 46 methods and its params-carrying ones
            // fire many times a second during playback — the gate inside
            // onPlayerMethod must stay cheap enough for the UI thread.
            runCatching { onPlayerMethod(chain.args) }
                .onFailure { L.w(TAG, "capture failed", it) }
            return result
        }
    }

    // ---------------------------------------------------------------- capture

    /**
     * Class name of the RichVideoPlayerParams arg, learned on first capture.
     * Subsequent calls only stringify args of this exact class — building the
     * full GraphQLMedia dump toString for every one of the 46 hooked methods
     * on every call stalled the UI thread (feed videos autoplay → dozens of
     * multi-KB string builds per second → feed loader stuck).
     */
    @Volatile private var paramsClassName: String? = null

    /** Last capture state for throttling (videoId + wall time). */
    @Volatile private var lastVideoId: String? = null
    @Volatile private var lastCaptureAt = 0L

    /**
     * Scan the hooked method's arguments for the RichVideoPlayerParams (the
     * arg whose toString contains "VideoId: " and is not "VideoId: null"),
     * dump it and extract the quality ladder (libnc.so.c 16380–22320).
     */
    private fun onPlayerMethod(args: List<Any?>) {
        // Throttle: the same params object is passed to many container
        // methods; at most one full pass per second.
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastCaptureAt != 0L && now - lastCaptureAt < 1000L) return

        val known = paramsClassName
        val params = args.firstNotNullOfOrNull { arg ->
            if (arg == null) return@firstNotNullOfOrNull null
            val name = arg.javaClass.name
            if (known != null) {
                // Learned: only the params class is worth stringifying.
                if (name != known) return@firstNotNullOfOrNull null
            } else if (name.startsWith("java.") || name.startsWith("android.") ||
                name.startsWith("kotlin.")
            ) {
                return@firstNotNullOfOrNull null
            }
            val s = runCatching { arg.toString() }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (VIDEO_ID_MARKER in s && !s.contains("${VIDEO_ID_MARKER}$NULL_MARKER")) s else null
        } ?: return

        // Expensive pass done (full dump toString + scan) — throttle from
        // here on regardless of whether extraction succeeds.
        lastCaptureAt = now

        val videoId = extractVideoId(params) ?: return
        // Same video again: re-capture at most every 5s (quality may change).
        if (videoId == lastVideoId && now - lastCaptureAt < 5000L) return
        val mediaMatch = GRAPHQL_MEDIA_REGEX.find(params) ?: return
        val mediaDump = mediaMatch.groupValues[1]

        val playlist = parsePlaylist(mediaDump)
        val qualities = extractQualities(mediaDump)
        if (qualities.isEmpty() && playlist == null) return

        lastVideoId = videoId
        if (paramsClassName == null) {
            paramsClassName = params.javaClass.name
        }

        // The MPD's first audio representation is the mod's defaultAudioUrl.
        val audioUrl = playlist?.defaultAudioUrl ?: extractAudioUrl(params, mediaDump)
        val data = VideoData(
            videoId = videoId,
            mediaDump = mediaDump,
            qualities = qualities,
            audioUrl = audioUrl,
            postUrl = WATCH_PREFIX + videoId,
            thumbnailUrl = extractThumbnail(mediaDump),
            playlist = playlist,
        )
        registerCapture(data)

        // Mod writes AllData.txt + the per-video JSON store on a worker.
        io.execute { persist(videoId, params, mediaDump, data) }
        L.i(TAG, "captured video $videoId (${qualities.size} qualities, " +
                "audio=${audioUrl != null}, thumb=${data.thumbnailUrl != null})")
        logVideoLinks(data)
    }

    /**
     * The consistent per-video link log — one line per download link, the
     * same videoId prefix on every line so a video's full ladder can be
     * pulled out of logcat with `grep "VIDEO <id>"`:
     *
     *   VIDEO <videoId> <label> <tag> <url>
     */
    private fun logVideoLinks(data: VideoData) {
        val lines = StringBuilder("VIDEO ${data.videoId} captured: ${data.qualities.size} link(s)" +
                (data.playlist?.let { ", playlist ${it.videos.size}v/${it.audios.size}a" } ?: ""))
        for (q in data.qualities) {
            lines.append("\nVIDEO ").append(data.videoId).append(' ')
                .append(q.label).append(' ').append(q.tag).append(' ').append(q.url)
        }
        data.playlist?.videos?.forEach { r ->
            lines.append("\nVIDEO ").append(data.videoId).append(' ')
                .append(r.qualityLabel.ifEmpty { r.height.ifEmpty { "?" } + "p" })
                .append(" mp4_no_audio ").append(r.baseUrl)
        }
        data.audioUrl?.let {
            lines.append("\nVIDEO ").append(data.videoId).append(" audio audio ").append(it)
        }
        L.i(TAG, lines.toString())
    }

    /** VideoId: <id> from the params toString head. */
    private fun extractVideoId(params: String): String? {
        val i = params.indexOf(VIDEO_ID_MARKER)
        if (i < 0) return null
        val rest = params.substring(i + VIDEO_ID_MARKER.length)
        return rest.takeWhile { it != ',' && it != ')' }.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Quality ladder: each known URL field in the GraphQLMedia dump, first
     * occurrence wins, in the mod's DAT order. Labels via the mod's
     * yuDFuQ9Oykp56S86PUs regex ("(\d{2,4})p?" → "720p"), falling back to the
     * field name's SD/HD hint.
     *
     * 576 dump shape (verified on-device): GraphQLMedia.toString() is a raw
     * JSON object (`{"__typename":"Video",…,"playable_url":"https://…`}),
     * unlike the mod build's `key=value` TreeJNI text — so values are read
     * as JSON members first, with a literal `"key":"…"` regex and the legacy
     * `key=` scan as fallbacks.
     */
    private fun extractQualities(mediaDump: String): List<Quality> {
        val out = LinkedHashMap<String, Quality>()
        val json = runCatching { JSONObject(mediaDump) }.getOrNull()
        for (key in QUALITY_KEYS) {
            val value = dumpValue(mediaDump, json, key) ?: continue
            if (!value.startsWith("http")) continue
            val tag = if (key in NO_AUDIO_KEYS) "mp4_no_audio" else "mp4"
            // The hd/sd KEY hint is authoritative: the yuDFu URL-digit regex
            // mislabels fbcdn URLs (e.g. host "t42.1790-2" → "42p"), which
            // used to leave the ladder without any "HD" link.
            val label = when {
                key.contains("hd", ignoreCase = true) -> "HD"
                key.contains("sd", ignoreCase = true) -> "SD"
                else -> qualityLabel(value) ?: "SD"
            }
            // First occurrence of a URL wins.
            if (value !in out) out[value] = Quality(label, value, tag)
        }
        return out.values.toList()
    }

    /**
     * Raw URL value for [key] from the media dump: JSON object member (plain
     * string, or a {uri:…} URL object), else the `"key":"…"` literal, else
     * the mod-build `key=` text form.
     */
    private fun dumpValue(mediaDump: String, json: JSONObject?, key: String): String? {
        if (json != null && json.has(key)) {
            val direct = json.optString(key)
            if (direct.startsWith("http")) return cleanUrl(direct)
            val uri = json.optJSONObject(key)?.optString("uri") ?: ""
            if (uri.startsWith("http")) return cleanUrl(uri)
        }
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(mediaDump)
        if (m != null) return cleanUrl(m.groupValues[1]).takeIf { it.startsWith("http") }
        val idx = mediaDump.indexOf("$key=")
        if (idx >= 0) {
            return mediaDump.substring(idx + key.length + 1)
                .takeWhile { it != ',' && it != '\n' && it != '}' }
                .trim().takeIf { it.startsWith("http") }
        }
        return null
    }

    /** Un-escapes JSON/HTML URL encodings the dump carries (\/, &, &amp;). */
    private fun cleanUrl(raw: String): String {
        var s = raw
        if (s.startsWith("//")) s = "https:$s"
        s = s.replace("\\/", "/")
        s = s.replace("\\u0026", "&")
        s = s.replace("&amp;", "&")
        return s
    }

    /**
     * The DASH audio URL passed to the mix dispatcher. The mod's exact
     * derivation is not yet reversed (see docs/unported-hooks.md); FB's
     * GraphQLMedia dump exposes it under audio-named URL fields.
     */
    private fun extractAudioUrl(params: String, mediaDump: String): String? {
        val json = runCatching { JSONObject(mediaDump) }.getOrNull()
        for (key in listOf("audio_dashed_url", "audioUrl", "audio_url", "dashAudioUrl")) {
            val value = dumpValue(mediaDump, json, key) ?: continue
            if (value.startsWith("http")) return value
        }
        return null
    }

    /** Ezo4.yuDFuQ9Oykp56S86PUs port (libnc.so.c 87840). */
    fun qualityLabel(url: String): String? {
        if (url.isBlank()) return null
        val cleaned = url.lowercase(Locale.US).replace(" ", "")
        return Regex("(\\d{2,4})p?").find(cleaned)?.let { "${it.groupValues[1]}p" }
    }

    // ------------------------------------------- FacebookExtract qualities fetch
    //
    // The mod's "all available qualities" source (fully reversed 2026-08-31):
    // it does NOT rely on the GraphQLMedia dump alone — E1s6JJe3h6m1UlOuKaQx
    // (libnc.so.c 63305–66900) FETCHES the video's desktop page and parses the
    // DASH manifest embedded in it.
    //
    //   triggers (both converge on E1s6.T7Puc(url, cookie, callback)):
    //     • ClipboardManager.setPrimaryClip hook (l6gJTzHczDDiahs77aMq,
    //       beforeHookedMethod 1259312): clip text starting http(s):// →
    //       toast quick_link.toast.loading → fetch that URL. Registered from
    //       oRfibdnyvv3dEUIoNSY0$H4cuReyVMyd3apUPirC5.run (1297395:
    //       findAndHookMethod(ClipboardManager, "setPrimaryClip", ClipData, hook)).
    //     • quick-link controller pO4TjBxQp2IFI1UyrtSF.EQRQYtm1nsiKbj6wysU
    //       (1315615): URL "https://www.facebook.com/watch/?v=<id>".
    //   request: okhttp GET, headers user-agent (desktop Chrome 126 — desktop
    //   pages embed the MPD), accept, sec-fetch-mode: navigate, cookie
    //   (prefs FBHTTPCOOKIE / loginCookie = the captured session).
    //   parse: save body to APKMODDONE/FacebookExtract/{Response.html,
    //   Log.json, Data.json}; strip "<script[^>]+>|</script>|amp;"; extract
    //   "<MPD .+?</MPD>"; renditions via FBQualityLabel="…"><BaseURL>…;
    //   separate audio track via mimeType="audio/mp4"…<BaseURL>…; fallback
    //   page-JSON keys browser_native_hd_url / progressive_url.
    //   delivery: CvefI9fVBOgMOQQpg15f.T7Puc(SD, HD, audio, thumb,
    //   ArrayList<extra qualities>) → picker items "HD video" / "SD video" /
    //   "Audio" (M4A) / "Thumbnail" (JPG) / per-quality "%s video"
    //   (mp4_no_audio); empty → toast quick_link.toast.no_media.

    /** Mod: APKMODDONE/FacebookExtract under the app's dataDir. */
    private const val EXTRACT_DIR = "APKMODDONE/FacebookExtract"

    /** Mod DAT_00fd3458 — desktop Chrome page-navigation UA. */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Mod DAT_00fd32d8 — the Accept header of a desktop page navigation. */
    private const val DESKTOP_ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,image/apng,*/*;q=0.8," +
            "application/signed-exchange;v=b3"

    /** Mod DAT_00fd31d8/00fd3218 region — script tags + HTML entities. */
    private val SCRIPT_STRIP = Regex("<script[^>]+>|</script>|amp;")

    /**
     * JSON-string unescape for the fetched page: on 576 the MPD is embedded
     * inside a JSON string value ("<MPD … <\/MPD>"), so the mod's
     * angle-bracket regexes only see real tags after decoding </>/
     * &, \", \/ and \n (verified against a live watch/?v= page: 8
     * renditions 240p–1080p + 1 audio track once decoded).
     */
    private fun unescapePage(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'u' -> if (i + 5 < text.length) {
                        when (text.substring(i + 2, i + 6)) {
                            "003C" -> { append('<'); i += 6; continue }
                            "003E" -> { append('>'); i += 6; continue }
                            "0026" -> { append('&'); i += 6; continue }
                        }
                    }
                    '"' -> { append('"'); i += 2; continue }
                    '/' -> { append('/'); i += 2; continue }
                    'n' -> { append('\n'); i += 2; continue }
                    '\\' -> { append('\\'); i += 2; continue }
                }
            }
            append(c); i++
        }
    }

    /** Mod DAT_00fd3758 — the embedded DASH manifest. */
    private val MPD_REGEX = Regex("<MPD .+?</MPD>", RegexOption.DOT_MATCHES_ALL)

    /** Mod DAT_00fd3798 — one rendition per MPD Representation. */
    private val RENDITION_REGEX =
        Regex("FBQualityLabel=\"([^\"]+)\"><BaseURL>([^<]+)</BaseURL>")

    /** Mod DAT_00fd3718 — the separate audio track's BaseURL. */
    private val AUDIO_TRACK_REGEX =
        Regex("mimeType=\"audio/mp4\".+?<BaseURL>([^<]+)</BaseURL>",
            RegexOption.DOT_MATCHES_ALL)

    /**
     * The fetcher's delivery (mod callback args): SD/HD from the page JSON
     * fallback keys, the DASH audio track, and every MPD rendition as an
     * extra quality (tag mp4_no_audio — DASH video-only streams).
     */
    class FetchedQualities(
        val sd: String?,
        val hd: String?,
        val audio: String?,
        val extras: List<Quality>,
    )

    /** Cookie for the page fetch (mod prefs FBHTTPCOOKIE/loginCookie). */
    private fun sessionCookie(): String? =
        appContext.getSharedPreferences("fbar_account", Context.MODE_PRIVATE)
            .getString(tn.loukious.facebookappadsremover.hooks.AccountHook.KEY_COOKIE_HTTP, null)
            ?.takeIf { it.isNotBlank() }

    /**
     * E1s6$bsrnc.run() port: GET the page with desktop headers, persist the
     * FacebookExtract files, extract the MPD. Runs on the io executor.
     */
    fun fetchQualities(pageUrl: String): FetchedQualities? {
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return null
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val builder = okhttp3.Request.Builder().url(pageUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", DESKTOP_ACCEPT)
            .header("sec-fetch-mode", "navigate")
        sessionCookie()?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                L.w(TAG, "FacebookExtract HTTP ${resp.code} for $pageUrl")
                return null
            }
            val body = resp.body?.string() ?: return null
            persistExtract(pageUrl, body)

            val cleaned = SCRIPT_STRIP.replace(unescapePage(body), "")
            // EVERY MPD on the page, not just the first: a 576 watch page
            // carries 3 manifests (device-tailored + full 270p–1440p ladder +
            // low-power 72p–480p ladder) — the mod's "See more" list shows
            // all of them ("sooo many qualities").
            val extras = ArrayList<Quality>()
            var audio: String? = null
            for (mpd in MPD_REGEX.findAll(cleaned)) {
                for (m in RENDITION_REGEX.findAll(mpd.value)) {
                    val url = cleanUrl(m.groupValues[2]).takeIf { it.startsWith("http") }
                        ?: continue
                    // Mod item format "<label> video" — every MPD rendition is
                    // a DASH video-only stream (audio is a separate track).
                    if (extras.none { it.url == url }) {
                        extras.add(Quality(m.groupValues[1], url, "mp4_no_audio"))
                    }
                }
                if (audio == null) {
                    audio = AUDIO_TRACK_REGEX.find(mpd.value)?.let {
                        cleanUrl(it.groupValues[1]).takeIf { u -> u.startsWith("http") }
                    }
                }
            }
            // Page-JSON fallback keys (mod: S7VA.zN38a9JuKWA3qMpRIVA reads
            // "browser_native_hd_url" / "progressive_url" out of the page).
            val hd = pageJsonUrl(cleaned, "browser_native_hd_url")
            val sd = pageJsonUrl(cleaned, "progressive_url")
            L.i(TAG, "FacebookExtract: ${extras.size} rendition(s), " +
                    "audio=${audio != null}, hd=${hd != null}, sd=${sd != null}")
            return FetchedQualities(sd, hd, audio, extras)
        }
    }

    /** "key":"value" URL lookup in the fetched page text (escaped forms). */
    private fun pageJsonUrl(text: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)
            ?: return null
        return cleanUrl(m.groupValues[1]).takeIf { it.startsWith("http") }
    }

    /** Mod's FacebookExtract files: Response.html + Log.json + Data.json. */
    private fun persistExtract(pageUrl: String, body: String) {
        runCatching {
            val dir = File(appContext.dataDir, EXTRACT_DIR)
            dir.mkdirs()
            File(dir, "Response.html").writeText(body)
            File(dir, "Log.json").writeText(
                JSONObject().put("url", pageUrl)
                    .put("at", System.currentTimeMillis()).toString())
            File(dir, "Data.json").writeText(
                JSONObject().put("url", pageUrl)
                    .put("mpd", MPD_REGEX.find(SCRIPT_STRIP.replace(body, ""))?.value ?: "")
                    .toString())
        }.onFailure { L.w(TAG, "FacebookExtract persist failed", it) }
    }

    /**
     * Mod store: AllData.txt, VideoPlaylist.json, VideoGraphQLMedia.json.
     * VideoPlaylist.json is written in the mod's own shape (verified against
     * its live file): {video:[…], audio:[…], defaultAudioUrl} with one entry
     * per MPD Representation when the dump carried a playlist; the flat
     * ladder shape is the fallback when it didn't.
     */
    private fun persist(videoId: String, params: String, mediaDump: String, data: VideoData) {
        runCatching {
            val dir = File(appContext.dataDir, DIR_NAME)
            dir.mkdirs()
            File(dir, "AllData.txt").appendText(params + "\n\n")

            val playlist = if (data.playlist != null) {
                JSONObject().apply {
                    put("video", org.json.JSONArray().apply {
                        data.playlist.videos.forEach { r ->
                            put(JSONObject()
                                .put("id", r.id)
                                .put("bandwidth", r.bandwidth)
                                .put("codecs", r.codecs)
                                .put("width", r.width)
                                .put("height", r.height)
                                .put("qualityLabel", r.qualityLabel)
                                .put("BaseURL", r.baseUrl))
                        }
                    })
                    put("audio", org.json.JSONArray().apply {
                        data.playlist.audios.forEach { r ->
                            put(JSONObject()
                                .put("id", r.id)
                                .put("bandwidth", r.bandwidth)
                                .put("codecs", r.codecs)
                                .put("audioSamplingRate", r.audioSamplingRate)
                                .put("BaseURL", r.baseUrl))
                        }
                    })
                    put("defaultAudioUrl", data.playlist.defaultAudioUrl ?: "")
                }
            } else {
                JSONObject().apply {
                    put("videoId", videoId)
                    put("videoPostUrl", data.postUrl)
                    put("download_link_sd", data.qualities.firstOrNull { it.label == "SD" }?.url ?: "")
                    put("download_link_hd", data.qualities.firstOrNull { it.label.contains("HD") }?.url ?: "")
                    put("audioUrl", data.audioUrl ?: "")
                }
            }
            File(dir, "VideoPlaylist.json").writeText(playlist.toString())

            val media = JSONObject().apply {
                put("videoId", videoId)
                put("mediaDump", mediaDump)
                val arr = org.json.JSONArray()
                data.qualities.forEach { q ->
                    arr.put(JSONObject().put("label", q.label).put("url", q.url).put("tag", q.tag))
                }
                put("qualities", arr)
            }
            File(dir, "VideoGraphQLMedia.json").writeText(media.toString())
        }.onFailure { L.w(TAG, "persist failed", it) }
    }

    /**
     * XVv4O94z1XoxDaljJoG6 port (libnc.so.c 1045784): the "Facebook Videos"
     * watched-list — one card per captured video (thumbnail + title +
     * link-count/max-quality summary); tapping a card opens the QUICK VIDEO
     * DOWNLOAD dialog with that video's full link list.
     */
    private fun showVideoList(activity: Activity, videos: List<VideoData>) {
        val adapter = VideoCardAdapter(activity, videos)
        AlertDialog.Builder(activity)
            .setTitle("Facebook Videos") // XVv4 title (libnc.so.c 1045784)
            .setAdapter(adapter, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Mod: while the list is on screen the E1s6 fetch is already running
        // (EQRQ trigger), so tapping a card opens the dialog instantly. Each
        // fetch that lands re-binds the list so the card's link count matches
        // the dialog exactly.
        videos.forEach { video ->
            prefetchQualities(video) { main.post { adapter.notifyDataSetChanged() } }
        }
    }

    /**
     * The card's summary line: how many links the quick dialog will offer and
     * the best resolution among them. Once the E1s6 prefetch has landed the
     * count is EXACTLY quickItems() (fetched + captured, deduped) so the card
     * can never disagree with the dialog; before it lands it is estimated
     * from the capture alone (progressive ladder + playlist renditions +
     * audio, URL-deduped).
     */
    private fun cardSummary(video: VideoData): String {
        val fetched = fetchCache[video.videoId]
        if (fetched != null) {
            val items = quickItems(video, fetched)
            val maxRes = items.maxOfOrNull { resolutionOf(it.label) } ?: 0
            val best = when {
                maxRes > 0 -> "${maxRes}p"
                items.any { it.label == "HD video" } -> "HD"
                items.isNotEmpty() -> "SD"
                else -> "?"
            }
            return "${items.size} links • up to $best"
        }
        val urls = HashSet<String>()
        video.qualities.forEach { urls.add(it.url) }
        video.playlist?.videos?.forEach { urls.add(it.baseUrl) }
        (video.playlist?.defaultAudioUrl ?: video.audioUrl)?.let { urls.add(it) }
        val maxRes = video.playlist?.videos
            ?.mapNotNull { r ->
                r.qualityLabel.filter { it.isDigit() }.toIntOrNull()
                    ?: r.height.toIntOrNull()
            }?.maxOrNull()
        val best = when {
            maxRes != null -> "${maxRes}p"
            video.qualities.any { it.label.contains("HD", true) } -> "HD"
            video.qualities.isNotEmpty() -> "SD"
            else -> "?"
        }
        return "${urls.size} links • up to $best"
    }

    /**
     * One card per captured video (mod: vlMtnJ1KtZnsyQ9cbByv extends
     * LinearLayout) — thumbnail + title + a link-count/max-quality summary.
     * Tapping the card opens the QUICK VIDEO DOWNLOAD dialog with the full
     * link list (user-directed: no SD/HD buttons on the card — the 576 dump
     * usually carries a single progressive URL, so a card HD button can only
     * fail; every quality lives in the dialog instead).
     */
    private class VideoCardAdapter(
        private val activity: Activity,
        private val videos: List<VideoData>,
    ) : BaseAdapter() {

        override fun getCount() = videos.size
        override fun getItem(position: Int): Any = videos[position]
        override fun getItemId(position: Int): Long = position.toLong()

        @SuppressLint("ViewHolder")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val video = videos[position]
            val density = parent.resources.displayMetrics.density
            fun dp(v: Int) = (density * v).toInt()

            val thumb = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(132), dp(74))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(0x22808080); cornerRadius = density * 8
                }
                clipToOutline = true
            }
            val title = TextView(activity).apply {
                text = "Video ${video.videoId}"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            // Summary line: distinct captured links + the best resolution
            // (playlist qualityLabel/height; HD/SD ladder hint fallback).
            val summary = TextView(activity).apply {
                text = cardSummary(video)
                textSize = 12f
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(title)
                addView(summary)
            }
            // Repost button (mod: the card's own button, handler
            // YMipIyoBixtpR7utxfWU.T7PucPwlExzHkx17YkC) — opens the repost
            // dialog instead of the download list.
            val repostBtn = android.widget.Button(activity).apply {
                text = "Repost"
                textSize = 12f
                setPadding(dp(12), dp(4), dp(12), dp(4))
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { runCatching { Repost.open(activity, video) } }
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = density * 10
                    setStroke(dp(1), 0x33808080)
                }
                addView(thumb)
                // textCol takes the remaining width so the button stays put.
                addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(repostBtn)
                // Tap the card → the full link list (the "See more" dialog).
                setOnClickListener { runCatching { openQuickDownload(activity, video) } }
            }

            // Thumbnail: the soundtrack's album art when the dump carries it
            // (music_album_art_uri — the only image URL this GraphQL surface
            // exposes); otherwise the video's first frame via
            // MediaMetadataRetriever on playable_url. thumbnailImage is an
            // empty Image shell on 576, so there is no poster URL to read.
            //
            // getView may re-fire for a position after the fetch was queued
            // (this adapter builds a fresh ImageView each call), so the only
            // reliable delivery is: cache the bitmap, then re-bind the list —
            // the mod's list likewise reads its persisted videoThumbnail
            // synchronously and never races the view.
            val artUrl = video.thumbnailUrl
            val key = artUrl ?: "frame:${video.videoId}"
            thumbCache[key]?.let { thumb.setImageBitmap(it) }
            if (!thumbCache.containsKey(key) && thumbRequested.add(key)) {
                thumbIo.execute {
                    val bmp = if (artUrl != null) fetchThumbnail(artUrl)
                              else fetchFrame(video)
                    L.i(TAG, "THUMB ${video.videoId}: " +
                            (bmp?.let { "${it.width}x${it.height}" } ?: "unavailable"))
                    if (bmp != null) {
                        thumbCache[key] = bmp
                        // Re-bind so whichever ImageView is on screen now —
                        // possibly a newer one than this fetch started from —
                        // picks the cached bitmap up in getView.
                        main.post { notifyDataSetChanged() }
                    }
                }
            }
            return row
        }

        private fun fetchThumbnail(url: String): Bitmap? = runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            // fbcdn 403s some default Dalvik UAs; send a browser one.
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            )
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }.onFailure { L.w(TAG, "THUMB fetch error: ${it.message}") }.getOrNull()

        /**
         * Poster-frame fallback for videos whose dump carries no image URL
         * (no music metadata → no music_album_art_uri): grab the first frame
         * of any playable URL — the same role the mod's videoThumbnail served.
         *
         * Stories are the common miss: reels dumps carry playable_url (and
         * usually album art), stories often expose neither, so the ladder
         * widens from playable_url to every captured URL — a DASH video-only
         * rendition still has frames. The request also sends the session
         * cookie + browser UA: stories scontent URLs can 403 without them.
         */
        private fun fetchFrame(video: VideoData): Bitmap? = runCatching {
            val json = runCatching { JSONObject(video.mediaDump) }.getOrNull()
            val urls = ArrayList<String>()
            for (key in QUALITY_KEYS) dumpValue(video.mediaDump, json, key)?.let { urls.add(it) }
            for (q in video.qualities) urls.add(q.url)
            for (r in video.playlist?.videos.orEmpty()) urls.add(r.baseUrl)
            val url = urls.firstOrNull { it.startsWith("http") } ?: return null
            // MediaMetadataRetriever has no headers overload — pull the head
            // of the stream ourselves (cookie + UA + Range) into a cache file
            // and read the frame from disk.
            val tmp = File(activity.cacheDir, "thumb_${video.videoId}")
            try {
                downloadHead(url, tmp)
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(tmp.path)
                    retriever.getFrameAtTime(
                        0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                } finally {
                    runCatching { retriever.release() }
                }
            } finally {
                tmp.delete()
            }
        }.onFailure { L.w(TAG, "THUMB frame error: ${it.message}") }.getOrNull()

        /** First ~4MB of a video URL — enough for the poster frame. */
        private fun downloadHead(url: String, out: File) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val builder = okhttp3.Request.Builder().url(url)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .header("Range", "bytes=0-${4 * 1024 * 1024 - 1}")
            sessionCookie()?.let { builder.header("Cookie", it) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val input = resp.body?.byteStream() ?: error("empty body")
                input.use {
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (total < 4L * 1024 * 1024) {
                            val n = it.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            total += n
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------- quick video download
    // Mod flow, fully reversed:
    //   card tap (GshsfL21ZSKG5nJgp93s @ 126093 — the card's action)
    //     → E1s6 desktop-page fetch → aIv5 QUICK VIDEO DOWNLOAD dialog
    //       (aIv5$bsrnc.run, 1068984) with the full quality list.
    //   (The mod's card SD/HD buttons, eFLd3885l8N8rPYV87zB @ 1146430, are
    //    intentionally NOT ported: they read videoSD/videoHD captured on the
    //    mod's older FB build; 576 dumps carry a single progressive URL, so a
    //    card HD button can only fail — the qualities live in the dialog.)

    /**
     * The captured progressive ladder URL behind the quick dialog's HD/SD
     * rows when the page fetch delivered none (aIv5 fallback). HD = the
     * ladder link captured from an hd key; SD = the sd-key link, or — the
     * usual 576 shape, a single `playable_url` — the one non-HD ladder link.
     */
    private fun capturedLadderUrl(data: VideoData?, hd: Boolean): String? {
        val ladder = data?.qualities?.filter { it.tag == "mp4" } ?: return null
        return if (hd) {
            ladder.firstOrNull { it.label.contains("HD", ignoreCase = true) }?.url
        } else {
            ladder.firstOrNull { it.label.contains("SD", ignoreCase = true) }?.url
                ?: ladder.lastOrNull { !it.label.contains("HD", ignoreCase = true) }?.url
        }
    }

    /**
     * Card-tap action (the mod's card handler, GshsfL21ZSKG5nJgp93s @
     * libnc.so.c 126093): cookie-authenticated E1s6 desktop-page fetch →
     * QUICK VIDEO DOWNLOAD dialog. The prefetch (EQRQ) usually has it cached.
     */
    private fun openQuickDownload(activity: Activity, data: VideoData) {
        fetchCache[data.videoId]?.let { fetched ->
            showQuickDownloadDialog(activity, data.videoId,
                quickItems(data, fetched), quickAudioUrl(data, fetched))
            return
        }
        val capturedOnly = quickItems(data, null)
        if (data.postUrl.isBlank()) {
            showQuickDownloadDialog(activity, data.videoId, capturedOnly, quickAudioUrl(data, null))
            return
        }
        Toast.makeText(activity, "Getting available qualities…", Toast.LENGTH_SHORT)
            .show() // quick_link.toast.loading
        io.execute {
            val fetched = runCatching { fetchQualities(data.postUrl) }
                .onFailure { L.w(TAG, "qualities fetch failed", it) }.getOrNull()
                ?: fetchCache[data.videoId]
            fetched?.let { fetchCache[data.videoId] = it }
            val items = quickItems(data, fetched)
            main.post {
                if (items.isEmpty()) {
                    Toast.makeText(activity,
                        "No downloadable media was found.", Toast.LENGTH_SHORT)
                        .show() // quick_link.toast.no_media
                } else {
                    showQuickDownloadDialog(activity, data.videoId, items,
                        quickAudioUrl(data, fetched))
                }
            }
        }
    }

    /** The audio URL the mix/download dispatch falls back to (aIv5 extra). */
    private fun quickAudioUrl(data: VideoData?, fetched: FetchedQualities?): String? =
        fetched?.audio ?: data?.playlist?.defaultAudioUrl ?: data?.audioUrl

    /**
     * aIv5.T7Puc item list (libnc.so.c 1072654), the mod's order: HD video,
     * SD video, every rendition "%s video", Audio. Renditions = the page-fetch
     * MPDs MERGED with the captured embedded playlist — the fetch can come
     * back rendition-empty (page JSON carried only hd/sd) while the capture
     * has the full MPD, and vice versa; either source alone under-reports.
     * One option per resolution (deduped by URL and label — the 3 MPDs on a
     * 576 watch page repeat labels across codecs/manifests), best first.
     */
    private fun quickItems(data: VideoData?, fetched: FetchedQualities?): List<Quality> {
        val items = ArrayList<Quality>()
        (fetched?.hd ?: capturedLadderUrl(data, hd = true))
            ?.let { items.add(Quality("HD video", it, "mp4")) } // option.video_hd.title
        (fetched?.sd ?: capturedLadderUrl(data, hd = false))
            ?.let { items.add(Quality("SD video", it, "mp4")) } // option.video_sd.title
        // Mod item format quick_link.option.video_quality_format "%s video".
        val renditions = ArrayList<Quality>()
        for (e in fetched?.extras.orEmpty()) {
            renditions.add(Quality("${e.label} video", e.url, e.tag))
        }
        for (r in data?.playlist?.videos.orEmpty()) {
            val label = r.qualityLabel.ifEmpty { "${r.height}p" }
            renditions.add(Quality("$label video", r.baseUrl, "mp4_no_audio"))
        }
        renditions.sortByDescending { resolutionOf(it.label) }
        for (q in renditions) {
            if (items.none { it.url == q.url || it.label == q.label }) items.add(q)
        }
        quickAudioUrl(data, fetched)?.let { items.add(Quality("Audio", it, "m4a")) } // option.audio.title
        return items
    }

    /** Resolution in px parsed from a "<n>p video" label (0 when unlabeled). */
    private fun resolutionOf(label: String): Int =
        label.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toInt() ?: 0

    /** E1s6 results per videoId, so "See more" is instant on every later open. */
    private val fetchCache = java.util.concurrent.ConcurrentHashMap<String, FetchedQualities>()

    /** The background fetch that runs while the list is on screen (mod: EQRQ). */
    private fun prefetchQualities(data: VideoData, onDone: (() -> Unit)? = null) {
        if (fetchCache.containsKey(data.videoId)) {
            onDone?.invoke()
            return
        }
        // Keep the cache bounded to the recent-videos ring.
        if (fetchCache.size > 30) fetchCache.clear()
        io.execute {
            val fetched = runCatching { fetchQualities(data.postUrl) }
                .onFailure { L.w(TAG, "qualities prefetch failed", it) }.getOrNull()
            if (fetched != null) {
                fetchCache[data.videoId] = fetched
                L.i(TAG, "qualities prefetched for ${data.videoId}: " +
                        "${fetched.extras.size} rendition(s)")
                onDone?.invoke()
            }
        }
    }

    /** The merged per-video FETCH log (same grep-able VIDEO <id> format). */
    private fun logFetchedLinks(videoId: String, items: List<Quality>, audioUrl: String?) {
        val sb = StringBuilder("VIDEO $videoId picker: ${items.size} item(s)")
        for (q in items) {
            sb.append("\nVIDEO ").append(videoId).append(' ')
                .append(q.label).append(' ').append(q.tag).append(' ').append(q.url)
        }
        audioUrl?.let { sb.append("\nVIDEO ").append(videoId).append(" audio audio ").append(it) }
        L.i(TAG, sb.toString())
    }

    /**
     * aIv5$bsrnc.run port (libnc.so.c 1068984): the QUICK VIDEO DOWNLOAD
     * dialog — title + summary, scrollable item rows (title + subtitle, tap
     * = select, no dismissal), the HD row preselected (mod loop at 1071982),
     * and a bottom action row "Copy URL" / "Preview" / "Download"
     * (quick_link.action.*).
     */
    private fun showQuickDownloadDialog(activity: Activity, logId: String,
                                        items: List<Quality>, audioUrl: String?) {
        if (items.isEmpty()) {
            Toast.makeText(activity, "No downloadable media was found.", Toast.LENGTH_SHORT)
                .show() // quick_link.toast.no_media
            return
        }
        logFetchedLinks(logId, items, audioUrl)

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (density * v).toInt()

        // Mod selection state: int[] index, default = first "HD video" row
        // (mod loop at libnc.so.c 1071982).
        val selected = java.util.concurrent.atomic.AtomicInteger(
            items.indexOfFirst { it.label == "HD video" }.takeIf { it >= 0 } ?: 0)
        var dialog: AlertDialog? = null

        /** quick_link.option.* subtitle per item. */
        fun subtitle(q: Quality): String = when (q.tag) {
            "mp4" -> "MP4 • With audio" // quick_link.option.with_audio
            "m4a" -> "M4A • Separate audio stream" // quick_link.option.audio.summary
            "jpg" -> "JPG • Video thumbnail" // quick_link.option.thumbnail.summary
            else -> if (audioUrl != null)
                "MP4 • No audio • Audio will be merged during download" // no_audio_merge
            else "MP4 • No audio stream available" // quick_link.option.no_audio
        }

        val rows = ArrayList<LinearLayout>()
        val titles = ArrayList<TextView>()
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        // Selection render: highlight + a ✓ on the selected row so the tap
        // feedback is unambiguous in both light and dark themes.
        fun renderSelection(idx: Int) {
            rows.forEachIndexed { j, r ->
                r.setBackgroundColor(if (j == idx) 0x33808080 else 0x00000000)
                titles[j].text = (if (j == idx) "✓ " else "") + items[j].label
            }
        }
        items.forEachIndexed { i, q ->
            val title = TextView(activity).apply {
                text = q.label; textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(title)
                addView(TextView(activity).apply { text = subtitle(q); textSize = 12f })
                setOnClickListener { selected.set(i); renderSelection(i) }
            }
            rows.add(row)
            titles.add(title)
            list.addView(row)
        }

        fun actionButton(label: String, onClick: () -> Unit) = TextView(activity).apply {
            text = label; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = density * 8; setStroke(dp(1), 0x33808080)
            }
            setOnClickListener { runCatching { onClick() } }
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(16), dp(6), dp(16), dp(6))
            addView(actionButton("Copy URL") {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                val quality = items[selected.get()]
                cm.setPrimaryClip(ClipData.newPlainText("FacebookAppAdsRemover",
                    quality.url))
                Toast.makeText(activity, "${quality.label} URL copied.", Toast.LENGTH_SHORT)
                    .show()
            })
            addView(actionButton("Preview") { // quick_link.action.preview
                val url = items[selected.get()].url
                if (!url.startsWith("http")) {
                    Toast.makeText(activity, "The preview URL is invalid.",
                        Toast.LENGTH_SHORT).show() // toast.invalid_preview_url
                } else {
                    runCatching {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            addView(actionButton("Download") { // quick_link.action.download
                dialog?.dismiss()
                onQualitySelected(activity, items[selected.get()], audioUrl)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        }

        // Preselect the HD row (mod loop, libnc.so.c 1071982).
        renderSelection(selected.get())

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(activity).apply { addView(list) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("QUICK VIDEO DOWNLOAD") // quick_link.dialog.title
            .setMessage("Choose a quality to preview or download • ${items.size} options")
            .setView(content) // quick_link.dialog.summary_format
            .show()
    }

    /** YJMDIpfpsAneohZjKEuU.onClick port (libnc.so.c 1056161). */
    private fun onQualitySelected(activity: Activity,
                                  quality: Quality, audioUrl: String?) {
        var url = quality.url
        // Mod: append ?dl=1 / &dl=1 when missing (DAT_010b3418/3458/3498/34d8).
        if (!url.contains("dl=1")) {
            url += if (url.contains("?")) "&dl=1" else "?dl=1"
        }
        Toast.makeText(activity, "Downloading…", Toast.LENGTH_SHORT).show() // quick_link.toast.downloading

        when (quality.tag) {
            "mp4_no_audio" ->
                // DASH video-only: mod calls Ezo4.AdjzonOq0OhXgiiuU6H(url, audioUrl).
                dispatchDash(activity, url, audioUrl)
            "m4a", "jpg" ->
                // Audio (M4A) / Thumbnail (JPG) — direct items, no mix path.
                startDownload(activity, url, quality.tag)
            else ->
                // Progressive: mod calls Ezo4.yhNqDcnFVKtPAnsZdXH(url, tag).
                startDownload(activity, url, if (quality.tag.isEmpty()) "mp4" else quality.tag)
        }
    }

    // ---------------------------------------------------------- DASH dispatch

    /** Ezo4.AdjzonOq0OhXgiiuU6H(String,String) port (libnc.so.c 77145). */
    private fun dispatchDash(activity: Activity, videoUrl: String, audioUrl: String?) {
        if (audioUrl.isNullOrBlank()) {
            startDownload(activity, videoUrl, "mp4")
            return
        }
        if (videoUrl.isBlank()) {
            Toast.makeText(activity, "No video URL", Toast.LENGTH_SHORT).show()
            return
        }
        // Mod: IJWGvW3szti7foZ1dCqf choice dialog ("VIDEO HAS NO AUDIO").
        // "Mix audio" ran the remote mix service; the server rotated its API
        // key on 2026-08-31 (v1.4.1 key revoked — see docs/blockers.md), so
        // the button now muxes locally (LocalMux below): download both
        // streams, remux into one MP4 on-device. No server, no re-encoding.
        AlertDialog.Builder(activity)
            .setTitle("VIDEO HAS NO AUDIO")
            .setMessage(
                "This video quality does not include audio. Mix the video and " +
                    "audio locally on this device (no server, no re-encoding), or " +
                    "continue downloading the video without audio?",
            )
            .setPositiveButton("Mix audio") { _, _ -> LocalMux.start(activity, videoUrl, audioUrl) }
            .setNegativeButton("Download without audio") { _, _ -> startDownload(activity, videoUrl, "mp4") }
            .show()
    }

    // ------------------------------------------------------------- downloading

    /**
     * Ezo4.yhNqDcnFVKtPAnsZdXH(url, ext) port: browser hand-off (mod default,
     * swDownloadWithBrowser ON — the "Mei" extra targeted download-manager
     * browsers) or a direct streamed download to Movies/FB_Download.
     */
    fun startDownload(activity: Activity, url: String, ext: String) {
        val useBrowser = Settings.getBoolean(Settings.DOWNLOAD_USE_BROWSER, true)
        if (useBrowser) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.putExtra("Mei", true) // mod: default browser-download hint
                activity.startActivity(intent)
                L.i(TAG, "handing download to browser")
            }.onFailure {
                L.w(TAG, "browser hand-off failed — falling back to direct download", it)
                downloadDirect(url, ext)
            }
        } else {
            downloadDirect(url, ext)
        }
        recordHistory(url, ext)
    }

    /** MeiOkHttp.downLoadFile port: stream to Movies/FB_Download/FB_<ts>.<ext>. */
    private fun downloadDirect(url: String, ext: String) {
        dlIo.execute {
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val name = "FB_${stamp}.$ext"
                val out: File
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/$ext")
                        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MOVIES + "/FB_Download")
                    }
                    val resolver = appContext.contentResolver
                    val uri = resolver.insert(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("MediaStore insert failed")
                    resolver.openOutputStream(uri)?.use { os ->
                        stream(url, os)
                    } ?: error("openOutputStream failed")
                    out = File(name)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "FB_Download",
                    )
                    dir.mkdirs()
                    out = File(dir, name)
                    out.outputStream().use { stream(url, it) }
                }
                L.i(TAG, "downloaded ${out.name}")
                main.post {
                    Toast.makeText(appContext, "Saved ${out.name}", Toast.LENGTH_LONG).show()
                }
            }.onFailure { L.e(TAG, "direct download failed", it) }
        }
    }

    private fun stream(url: String, os: java.io.OutputStream) {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(url).header("User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            body.byteStream().use { input -> input.copyTo(os, 64 * 1024) }
        }
    }

    private fun recordHistory(url: String, ext: String) {
        runCatching {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = org.json.JSONArray(prefs.getString(KEY_HISTORY, "[]"))
            arr.put(JSONObject().put("url", url).put("ext", ext)
                .put("at", System.currentTimeMillis()))
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        }
    }

    // ------------------------------------------------------------ mix service

    /**
     * The mod's remote audio-mix client (mix onClick, libnc.so.c 152120 +
     * hQCe5CvZPZA1OSQW4RuB + W6pSE9eyvwFGibhIwImI, 1184301–1031803).
     *
     * RETIRED 2026-08-31: the endpoint moved from /facebook-mix/api.php to
     * /api.php AND the v1.4.1 key (MIX_API_KEY below) was revoked — the server
     * answers {"error":"invalid_api_key"} HTTP 401. The new key ships only as
     * an AMD1-encrypted blob on the domain root, readable by newer builds of the original mod
     * builds. The "Mix audio" button now runs [LocalMux] (on-device remux)
     * instead; this object is kept unreferenced as the wire-protocol record.
     *
     * POST {video_url: ZZy(url) — dl=1 appended, audio_url, music_volume:1,
     * s:true} to the mix endpoint (see MIX_ENDPOINT — the server moved from
     * /facebook-mix/api.php to /api.php on 2026-08-31) via MeiOkHttp's
     * postJsonStream: Referer = endpoint dir, Chrome 97 mobile UA,
     * Accept "application/x-ndjson, application/json", X-API-Key,
     * Content-Type "application/json;charset=UTF-8", timeouts 60s/10min/60s.
     * Line protocol: trimmed non-empty lines; "data:" prefix stripped
     * (substring(5)); ":"-prefixed lines are heartbeats; every other line is
     * parsed as raw NDJSON (e=accepted/s/progress/complete). Stream ending
     * without a complete event → media.mix.response.incomplete.
     */
    private object MixService {

        private const val TAG = "FBAR.Download.Mix"

        fun start(activity: Activity, videoUrl: String, audioUrl: String) {
            // Progress dialog — mod: media.mix.progress.* (title "Mixing audio",
            // connecting message, horizontal ProgressBar max 100, "Run in
            // background" positive button, cancelable, not canceled on outside
            // touch).
            val dp = activity.resources.displayMetrics.density
            val pad = (dp * 12).toInt()
            val title = TextView(activity).apply {
                text = "Connecting to the server…"
                textSize = 16f
            }
            val message = TextView(activity).apply {
                text = "The server is processing the video and audio. This may take a while, " +
                    "so please keep your network connection active."
                textSize = 14f
            }
            val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = true
            }
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(title)
                addView(message)
                addView(progress, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("MIXING AUDIO")
                .setView(layout)
                .setPositiveButton("Run in background") { d, _ -> d.dismiss() }
                .setCancelable(true)
                .create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()

            dlIo.execute { run(activity, dialog, title, message, progress, videoUrl, audioUrl) }
        }

        private fun run(
            activity: Activity,
            dialog: AlertDialog,
            title: TextView,
            message: TextView,
            progress: ProgressBar,
            videoUrl: String,
            audioUrl: String,
        ) {
            runCatching {
                // Mod mix onClick (152120): the video URL is run through ZZy
                // (the dl=1 appender) before being sent.
                val videoWithDl = if (videoUrl.contains("dl=1")) videoUrl
                else videoUrl + (if (videoUrl.contains("?")) "&dl=1" else "?dl=1")
                val body = JSONObject()
                    .put("video_url", videoWithDl)
                    .put("audio_url", audioUrl)
                    .put("music_volume", 1)
                    .put("s", true)
                    .toString()
                L.i(TAG, "mix request: video=${videoWithDl.take(120)} " +
                        "audio=${audioUrl.take(120)}")

                // MeiOkHttp.postJsonStream client: 60s connect / 10min read /
                // 60s write.
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(MIX_ENDPOINT)
                    .header("Referer", MIX_ENDPOINT.substring(
                        0, MIX_ENDPOINT.lastIndexOf('/') + 1))
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; K) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/97.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "application/x-ndjson, application/json")
                    .header("X-API-Key", MIX_API_KEY)
                    .post(body.toRequestBody("application/json;charset=UTF-8".toMediaType()))
                    .build()

                var completed = false
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Surface the server's own error (e.g. {"error":
                        // "invalid_api_key"}) instead of a bare HTTP code.
                        val errBody = runCatching {
                            resp.body?.string()?.take(200) ?: ""
                        }.getOrDefault("")
                        throw IllegalStateException(
                            "HTTP ${resp.code}${if (errBody.isNotBlank()) ": $errBody" else ""}")
                    }
                    val source = resp.body?.source() ?: throw IllegalStateException("empty body")
                    val ok = resp.header("Content-Type") ?: ""
                    L.i(TAG, "mix stream opened: $ok")

                    // MeiOkHttp line protocol: trimmed non-empty lines; the
                    // handler strips a "data:" prefix and skips ":"
                    // heartbeats; ANY other line is parsed as NDJSON too.
                    while (true) {
                        val line = source.readUtf8Line()?.trim() ?: break
                        if (line.isEmpty()) continue
                        val frame = when {
                            line.startsWith("data:") -> line.substring(5)
                            line.startsWith(":") -> continue  // heartbeat
                            else -> line
                        }
                        if (frame.isBlank()) continue
                        val json = runCatching { JSONObject(frame) }.getOrNull() ?: continue
                        L.i(TAG, "mix event: ${frame.take(160)}")
                        if (handle(activity, dialog, title, message, progress, json)) {
                            completed = true
                            break
                        }
                    }
                }
                if (!completed) {
                    // media.mix.response.incomplete
                    main.post {
                        runCatching { dialog.dismiss() }
                        Toast.makeText(activity,
                            "The stream ended before the server returned a completion result.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure { t ->
                L.e(TAG, "mix request failed", t)
                main.post {
                    runCatching { dialog.dismiss() }
                    Toast.makeText(activity,
                        "The server could not mix the video and audio.\n(${t.message?.take(120) ?: "unknown error"})",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        /** hQCe5CvZPZA1OSQW4RuB.onStreamData event dispatch; true = terminal. */
        private fun handle(
            activity: Activity,
            dialog: AlertDialog,
            title: TextView,
            message: TextView,
            progress: ProgressBar,
            json: JSONObject,
        ): Boolean {
            when (json.optString("e")) {
                "accepted" -> {
                    val job = json.optString("j")
                    main.post {
                        title.text = if (job.isNotBlank()) "Request accepted • Job ID: $job" else "Request accepted."
                    }
                }
                "s" -> {
                    // mod: media.mix.stage.<phase>
                    val phase = json.optString("p")
                    val text = when (phase) {
                        "downloading_video" -> "Downloading video…"
                        "downloading_audio" -> "Downloading audio…"
                        "probing_media" -> "Analyzing media…"
                        "merging" -> "Merging video and audio…"
                        else -> "Processing media…"
                    }
                    main.post { title.text = text }
                }
                "progress" -> {
                    val pct = json.optInt("percent").coerceIn(0, 100)
                    val processed = json.optDouble("processed_seconds", Double.NaN)
                    val total = json.optDouble("total_seconds", Double.NaN)
                    main.post {
                        progress.isIndeterminate = false
                        progress.progress = pct
                        message.text = if (!processed.isNaN() && !total.isNaN()) {
                            "Mixing audio… $pct% (${processed.toInt()} / ${total.toInt()} seconds)"
                        } else {
                            "Mixing audio… $pct%"
                        }
                    }
                }
                "complete" -> {
                    val ok = json.optBoolean("ok")
                    val videoUrl = json.optString("video_url")
                    val jobId = json.optString("j")
                    val expires = json.optLong("expires_in_seconds", 0)
                    main.post {
                        runCatching { dialog.dismiss() }
                        if (ok && videoUrl.isNotBlank()) {
                            showSuccess(activity, videoUrl, jobId, expires)
                        } else {
                            Toast.makeText(activity,
                                "The server could not mix the video and audio.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                    return true
                }
                // error events land here with an error message field
                else -> {
                    val err = json.optString("error", json.optString("message"))
                    if (err.isNotBlank()) {
                        L.w(TAG, "mix error event: $err")
                        main.post {
                            runCatching { dialog.dismiss() }
                            Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            return false
        }

        /** Ezo4.zN38a9JuKWA3qMpRIVA port: success dialog with Download/Close. */
        private fun showSuccess(activity: Activity, url: String, jobId: String, expiresSec: Long) {
            val message = buildString {
                append("The video and audio were mixed successfully.\n")
                if (expiresSec > 0) {
                    val mins = expiresSec / 60
                    append("The download link remains valid for ")
                    append(if (mins >= 60) "${mins / 60} hours ${mins % 60} minutes" else "$mins minutes")
                    append(".")
                } else if (jobId.isNotBlank()) {
                    append("Job ID: $jobId")
                }
            }
            AlertDialog.Builder(activity)
                .setTitle("AUDIO MIXED SUCCESSFULLY")
                .setMessage(message)
                .setPositiveButton("Download") { _, _ -> startDownload(activity, url, "mp4") }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    // ------------------------------------------------------------- local mux
    //
    // Replacement for the remote mix service: the v1.4.1 API key was revoked
    // server-side (HTTP 401 invalid_api_key — see docs/blockers.md), so
    // "Mix audio" now muxes ON-DEVICE: download the DASH video-only rendition
    // and the AAC audio track, then remux both into one MP4 with the Android
    // framework (MediaExtractor + MediaMuxer, sample copy — no re-encoding,
    // no upload, no API key). MixService above is kept unreferenced as the
    // record of the mod's wire protocol, revivable if a newer builds of the original mod
    // build ever yields the rotated key.

    private object LocalMux {

        private const val TAG = "FBAR.Download.Mix"

        fun start(activity: Activity, videoUrl: String, audioUrl: String) {
            // Same dialog shell as the mod's mix progress dialog
            // (media.mix.progress.*), driven by local stages instead of
            // server NDJSON events.
            val dp = activity.resources.displayMetrics.density
            val pad = (dp * 12).toInt()
            val title = TextView(activity).apply {
                text = "Preparing local mix…"
                textSize = 16f
            }
            val message = TextView(activity).apply {
                text = "The video and audio streams are downloaded and merged on " +
                    "this device — no server involved."
                textSize = 14f
            }
            val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = true
            }
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(title)
                addView(message)
                addView(progress, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("MIXING AUDIO")
                .setView(layout)
                .setPositiveButton("Run in background") { d, _ -> d.dismiss() }
                .setCancelable(true)
                .create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()

            dlIo.execute { run(activity, dialog, title, message, progress, videoUrl, audioUrl) }
        }

        private fun run(
            activity: Activity,
            dialog: AlertDialog,
            title: TextView,
            message: TextView,
            progress: ProgressBar,
            videoUrl: String,
            audioUrl: String,
        ) {
            val stamp = System.currentTimeMillis()
            val videoFile = File(activity.cacheDir, "mix_video_$stamp")
            val audioFile = File(activity.cacheDir, "mix_audio_$stamp")
            val outFile = File(activity.cacheDir, "mix_out_$stamp.mp4")
            runCatching {
                main.post { title.text = "Downloading video…" }
                downloadStream(videoUrl, videoFile) { done, total ->
                    main.post {
                        if (total > 0) {
                            progress.isIndeterminate = false
                            progress.progress = ((done * 100) / total).toInt().coerceIn(0, 100)
                        }
                    }
                }
                main.post { title.text = "Downloading audio…" }
                downloadStream(audioUrl, audioFile) { done, total ->
                    main.post {
                        if (total > 0) {
                            progress.isIndeterminate = false
                            progress.progress = ((done * 100) / total).toInt().coerceIn(0, 100)
                        }
                    }
                }
                main.post {
                    title.text = "Muxing video and audio…"
                    message.text = "Writing the merged file (no re-encoding)…"
                    progress.isIndeterminate = true
                }
                mux(videoFile, audioFile, outFile)

                // Same destination as downloadDirect: Movies/FB_Download.
                val name = "FB_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(Date()) + "_mixed.mp4"
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/FB_Download")
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { os ->
                    outFile.inputStream().use { it.copyTo(os) }
                } ?: error("openOutputStream failed")
                L.i(TAG, "locally muxed $name (${outFile.length()} bytes)")
                main.post {
                    runCatching { dialog.dismiss() }
                    Toast.makeText(appContext, "Saved $name", Toast.LENGTH_LONG).show()
                }
            }.onFailure { t ->
                L.e(TAG, "local mux failed", t)
                main.post {
                    runCatching { dialog.dismiss() }
                    Toast.makeText(activity,
                        "Could not mix the video and audio.\n(${t.message?.take(160) ?: "unknown error"})",
                        Toast.LENGTH_LONG).show()
                }
            }
            // Temp files always go, success or failure.
            videoFile.delete()
            audioFile.delete()
            outFile.delete()
        }

        /** OkHttp download to a cache file with byte progress. */
        private fun downloadStream(url: String, out: File, onProgress: (Long, Long) -> Unit) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).header("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code} fetching stream")
                val body = resp.body ?: error("empty body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
        }

        /**
         * MediaExtractor + MediaMuxer remux: copy every sample of the video
         * track and the audio track into one MP4 — no decoder, no re-encode.
         */
        private fun mux(videoFile: File, audioFile: File, outFile: File) {
            val videoExt = MediaExtractor()
            val audioExt = MediaExtractor()
            var muxer: MediaMuxer? = null
            try {
                videoExt.setDataSource(videoFile.path)
                audioExt.setDataSource(audioFile.path)
                val vTrack = selectTrack(videoExt, "video/")
                    ?: error("downloaded video stream has no video track")
                val aTrack = selectTrack(audioExt, "audio/")
                    ?: error("downloaded audio stream has no audio track")
                muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val vOut = try {
                    muxer.addTrack(videoExt.getTrackFormat(vTrack))
                } catch (t: Throwable) {
                    // e.g. an AV1 rendition MediaMuxer cannot write into MP4.
                    val mime = runCatching {
                        videoExt.getTrackFormat(vTrack).getString(MediaFormat.KEY_MIME)
                    }.getOrNull()
                    throw IllegalStateException(
                        "codec $mime cannot be muxed locally — pick another quality", t)
                }
                val aOut = try {
                    muxer.addTrack(audioExt.getTrackFormat(aTrack))
                } catch (t: Throwable) {
                    throw IllegalStateException("audio track rejected by the muxer", t)
                }
                muxer.start()
                copyTrack(videoExt, muxer, vOut)
                copyTrack(audioExt, muxer, aOut)
                muxer.stop()
            } finally {
                runCatching { muxer?.release() }
                runCatching { videoExt.release() }
                runCatching { audioExt.release() }
            }
        }

        private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) {
                    extractor.selectTrack(i)
                    return i
                }
            }
            return -1
        }

        /** Sample copy: extractor → muxer, one sample at a time. */
        private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, outTrack: Int) {
            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
        }
    }
}
