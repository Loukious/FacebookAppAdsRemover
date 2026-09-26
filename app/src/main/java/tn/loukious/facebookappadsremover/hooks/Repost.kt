package tn.loukious.facebookappadsremover.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import tn.loukious.facebookappadsremover.core.L
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Repost to Facebook Page — port of the original mod's watched-video repost
 * feature (dialog X/pO4TjBxQp2IFO1UyrtSF, page client
 * X/l04QpuwZKns7J4v3u8bm, posting workers X/mYnFhIspdRmuIKOAkKGI, card
 * button X/YMipIyoBixtpR7utxfWU; natives in libnc.so.c).
 *
 * Decoded mod behaviour:
 *
 *  - Page list (PageClient.T7Puc, libnc.so.c 1255423): GET
 *    `https://graph.facebook.com/v19.0/me/accounts?fields=<urlenc
 *    id,name,access_token,picture.type(square).width(96).height(96){url}>
 *    &limit=100&access_token=<urlenc token>` with header
 *    `Authorization: Bearer <token>`. Response: `error.message` → failure;
 *    `data[]` → id/name/access_token/picture.data.url (all trimmed); rows
 *    kept only when id AND access_token are non-blank; empty list → failure.
 *  - Token: the ViewerContext.mAuthToken captured by AccountHook (mod:
 *    GhTaOHdq4kOQbdazOJvf static → app.telegram.bemai3012_FBTOKEN pref).
 *  - Prefs: page list cached as a JSON array (mod:
 *    facebook_page_client.pages_cache.v1), the chosen page id (mod:
 *    facebook_video_repost.selected_page_id) and the last comment text
 *    (mod: facebook_video_repost.saved_comment) both survive restarts.
 *  - Dialog (pO4Tj): page section (radio rows + reload) followed by Title /
 *    Content / Comment inputs; the comment EditText is pre-filled from
 *    saved_comment, and Post re-saves it before dispatching.
 *  - Result reporting: unlike the original mod's verbose result dialog, this
 *    port reports completion with a Toast. The Page picker/editor is kept;
 *    only the final success/failure surface is simplified.
 *
 * Deviations (documented): English labels instead of the mod's Vietnamese
 * string table; the port already holds direct video URLs in VideoData, so
 * the mod's graphql video-URL resolution pass is unnecessary — the best
 * captured rendition is posted directly.
 */
object Repost {

    private const val TAG = "FBAR.Repost"

    /** The module's own prefs file in the FB process. */
    private const val PREFS_NAME = "fbar_repost"

    // Pref keys — the mod's keys in parentheses.
    private const val KEY_PAGES = "pages_cache"             // facebook_page_client.pages_cache.v1
    private const val KEY_SELECTED_PAGE = "selected_page_id" // facebook_video_repost.selected_page_id
    private const val KEY_SAVED_COMMENT = "saved_comment"    // facebook_video_repost.saved_comment

    /** Mod: graph.facebook.com/v19.0 (libnc.so.c 1077702/1313983). */
    private const val GRAPH_BASE = "https://graph.facebook.com/v19.0/"

    /**
     * The pre-filled title (mod: item.video.repost.default_title — value
     * still being decoded from the string table; the port ships an English
     * default in the meantime).
     */
    private const val DEFAULT_TITLE = "Repost Video"

    private const val PAGE_FIELDS =
        "id,name,access_token,picture.type(square).width(96).height(96){url}"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    private val main = Handler(Looper.getMainLooper())

    /** All Graph traffic runs here — one call at a time, off the main thread. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var prefs: SharedPreferences

    /** X/l04QpuwZKns7J4v3u8bm$kRbFm7USGDhCvFgJ8BvY — one managed Page. */
    class Page(
        val id: String,
        val name: String,
        val accessToken: String,
        val avatarUrl: String,
    ) {
        /** Mod getter T7Puc: the name when present, else the id. */
        val displayName: String get() = name.ifBlank { id }
    }

    /** Everything the Post flow produces, used to choose the completion Toast. */
    class PostOutcome(
        val videoId: String,
        val commentId: String,
        val commentPosted: Boolean,
        val commentError: String?,
    )

    // ---------------------------------------------------------------- open

    /**
     * Opens the repost dialog for a captured video (mod:
     * YMipIyoBixtpR7utxfWU.T7PucPwlExzHkx17YkC — the Repost button on a
     * watched-list card).
     */
    fun open(activity: Activity, video: DownloadHook.VideoData) {
        if (!this::prefs.isInitialized) {
            prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        DialogUi(activity, video).show()
    }

    // ---------------------------------------------------------- page client

    /**
     * PageClient.T7PucPwlExzHkx17YkC port — fetches me/accounts on [io] and
     * posts the parsed list (or the failure message) back to the main thread.
     * The callback mirrors the mod's $Hk8o interface: T7Puc(List) on
     * success, onError(String) on failure.
     */
    private fun loadPages(onSuccess: (List<Page>) -> Unit, onError: (String) -> Unit) {
        val token = AccountHook.authToken()
        if (token.isNullOrBlank()) {
            onError("No Facebook user token found.")  // mod: item.video.repost.no_token
            return
        }
        io.execute {
            val result = runCatching { fetchPageList(token) }
            main.post {
                result.onSuccess(onSuccess).onFailure {
                    onError(it.message ?: it.javaClass.simpleName)
                }
            }
        }
    }

    /** The blocking GET + parse; every mod failure path throws with its message. */
    private fun fetchPageList(token: String): List<Page> {
        val url = GRAPH_BASE + "me/accounts?fields=" +
            URLEncoder.encode(PAGE_FIELDS, "UTF-8") +
            "&limit=100&access_token=" + URLEncoder.encode(token, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        // The token rides in the header unencoded (mod: "Bearer " + token).
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        val body = try {
            (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
        return parsePageList(body)
    }

    /**
     * MeiOkHttp.post port (okhttp3/MeiOkHttp.java in the mod's dex) — a
     * form-encoded POST with the Bearer header, Referer set to the endpoint
     * base (url up to the last "/") and the Chrome-mobile UA; non-2xx and
     * IO failures surface as "Error: {code}\n{body}", the mod's exact
     * convention. Blocking — call on [io].
     */
    @Throws(Exception::class)
    internal fun graphPost(url: String, token: String, form: List<Pair<String, String>>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Referer", url.substring(0, url.lastIndexOf('/') + 1))
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = form.joinToString("&") {
            URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("Error: $code\n$text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** $bsrnc.onSuccess parsing port (libnc.so.c 1253862–1255118). */
    private fun parsePageList(body: String): List<Page> {
        val json = JSONObject(body)
        if (json.has("error")) {
            throw IllegalStateException(
                json.optJSONObject("error")?.optString("message", "") ?: "Graph error")
        }
        val arr = json.optJSONArray("data")
            ?: throw IllegalStateException("Cannot get Page list.")  // mod: page_load_error path
        val pages = ArrayList<Page>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            val name = obj.optString("name", "").trim()
            val accessToken = obj.optString("access_token", "").trim()
            val avatarUrl = obj.optJSONObject("picture")
                ?.optJSONObject("data")?.optString("url", "")?.trim() ?: ""
            // Mod: only rows with a non-blank id AND access_token are kept.
            if (id.isNotBlank() && accessToken.isNotBlank()) {
                pages.add(Page(id, name, accessToken, avatarUrl))
            }
        }
        if (pages.isEmpty()) {
            throw IllegalStateException(
                "User token returned no Pages (or missing Page permission).")
        }
        return pages
    }

    // ------------------------------------------------------ prefs / cache

    /** UuCRUjLnvPR7SP3GNGo(List) port — the mod's cache serialize format. */
    private fun serializePages(pages: List<Page>): String {
        val arr = JSONArray()
        for (p in pages) {
            if (p.id.isBlank() || p.accessToken.isBlank()) continue
            arr.put(JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("access_token", p.accessToken)
                .put("avatar_url", p.avatarUrl))
        }
        return arr.toString()
    }

    /** s9sEFa2Cpw9OaL6Yf67(String) port — null/blank/invalid → empty list. */
    private fun parseCachedPages(s: String?): List<Page> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            val pages = ArrayList<Page>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                val accessToken = obj.optString("access_token", "").trim()
                if (id.isNotBlank() && accessToken.isNotBlank()) {
                    pages.add(Page(
                        id,
                        obj.optString("name", "").trim(),
                        accessToken,
                        obj.optString("avatar_url", "").trim()))
                }
            }
            pages
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun putPref(key: String, value: String) {
        if (this::prefs.isInitialized) {
            runCatching { prefs.edit().putString(key, value).apply() }
                .onFailure { L.w(TAG, "pref write failed: $key", it) }
        }
    }

    private fun getPref(key: String): String =
        if (this::prefs.isInitialized) {
            runCatching { prefs.getString(key, "") }.getOrNull() ?: ""
        } else ""

    // ---------------------------------------------------------------- dialog

    /**
     * The repost dialog (mod: X/pO4TjBxQp2IFO1UyrtSF + its natives
     * iVPmwurKt1w7Mjyvndw page section, yhNqDcnFVKtPAnsZdXH labeled rows,
     * qsJGnbanr07zUGNprRk row render, zkQ4kenQD4EJspRyMc8 selection).
     */
    private class DialogUi(
        private val activity: Activity,
        private val video: DownloadHook.VideoData,
    ) {
        private val density = activity.resources.displayMetrics.density
        private fun dp(v: Int) = (density * v).toInt()

        /** pO4Tj.zN38a9JuKWA3qMpRIVA — the current page list. */
        private val pages = ArrayList<Page>()

        /** pO4Tj.zkQ4kenQD4EJspRyMc8 — the selected Page. */
        private var selected: Page? = null

        /** pO4Tj.FVOAe9jtnRcTUQP3QZ2 — the reload debounce flag. */
        @Volatile private var busy = false

        private lateinit var status: TextView
        private lateinit var rows: LinearLayout
        private lateinit var titleInput: EditText
        private lateinit var contentInput: EditText
        private lateinit var commentInput: EditText
        private lateinit var dialog: AlertDialog

        /** Best muxed rendition when available, otherwise the best video-only
         *  rendition (for legitimately silent/split-only source media). */
        private val selectedVideo = bestVideoQuality(video)
        private val videoUrl: String? = selectedVideo?.url

        fun show() {
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(4))
            }
            buildPageSection(root)
            titleInput = buildLabeledRow(root, "Title", EditText(activity).apply {
                hint = "Enter video title"
                setText(DEFAULT_TITLE)
                inputType = InputType.TYPE_CLASS_TEXT
            })
            contentInput = buildLabeledRow(root, "Content", EditText(activity).apply {
                hint = "Content to post to the Page…"
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            })
            commentInput = buildLabeledRow(root, "Comment after posting", EditText(activity).apply {
                hint = "Optional comment to add after the video posts"
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                // Mod: the saved_comment pref pre-fills this field.
                setText(getPref(KEY_SAVED_COMMENT))
            })

            dialog = AlertDialog.Builder(activity)
                .setTitle("Repost Facebook Video")  // mod literal (libnc.so.c 1060297)
                .setView(ScrollView(activity).apply { addView(root) })
                // Null listener + manual wiring below: Post must NOT
                // auto-dismiss (the mod keeps the dialog up with a
                // "posting" status while the workers run).
                .setPositiveButton("Post", null)
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { onPost() }

            // Mod launcher: the saved page list renders first ("Loaded saved
            // Page list: N"), then the network load refreshes it.
            val cached = parseCachedPages(getPref(KEY_PAGES))
            if (cached.isNotEmpty()) {
                pages.addAll(cached)
                renderRows()
                status.text = "Loaded saved Page list: ${pages.size}"
            }
            reloadPages()
        }

        /** iVPmwurKt1w7Mjyvndw — header + reload button + status + rows. */
        private fun buildPageSection(root: LinearLayout) {
            val header = TextView(activity).apply {
                text = "Select Facebook Page"  // mod: mo_page section label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            val reload = Button(activity).apply {
                text = "Reload"  // mod: item.video.repost.reload_pages
                textSize = 12f
                setOnClickListener { reloadPages() }
            }
            root.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(header)
                addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(reload)
            })
            status = TextView(activity).apply { textSize = 12f }
            root.addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
            rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            root.addView(rows, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
        }

        /** yhNqDcnFVKtPAnsZdXH — one label + input row. */
        private fun buildLabeledRow(root: LinearLayout, label: String, input: EditText): EditText {
            root.addView(TextView(activity).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, 0)
            })
            input.textSize = 14f
            root.addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
            return input
        }

        /** ew0F4Lnper9BdpUXT3mo.onClick — debounced network reload. */
        private fun reloadPages() {
            if (busy) return
            busy = true
            status.text = "Loading pages…"  // mod: item.video.repost.loading_pages
            loadPages(
                onSuccess = { fresh ->
                    busy = false
                    pages.clear()
                    pages.addAll(fresh)
                    putPref(KEY_PAGES, serializePages(pages))
                    renderRows()
                    status.text = "Pages loaded: ${pages.size}"
                    L.i(TAG, "page list loaded (${pages.size} pages)")
                },
                onError = { msg ->
                    busy = false
                    status.text = "Page load error: $msg"
                    // Mod: a separate error popup (pO4Tj.T7Puc helper).
                    if (pages.isEmpty()) {
                        AlertDialog.Builder(activity)
                            .setTitle("Repost Facebook Video")
                            .setMessage("Page load error: $msg")
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                })
        }

        /** qsJGnbanr07zUGNprRk — render one radio row per page, then
         *  restore the saved selection by id. */
        private fun renderRows() {
            rows.removeAllViews()
            val savedId = getPref(KEY_SELECTED_PAGE)
            val group = RadioGroup(activity)
            for ((index, page) in pages.withIndex()) {
                // The radio is nested in a row, so RadioGroup cannot manage
                // exclusivity — clicks must reach the row's own listener and
                // selection state is driven manually (selectPage).
                val radio = RadioButton(activity).apply {
                    textSize = 14f
                    isClickable = false
                }
                val name = TextView(activity).apply {
                    text = page.displayName
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val idText = TextView(activity).apply {
                    text = "ID: ${page.id}"  // mod getter xZw29
                    textSize = 12f
                }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                    addView(radio)
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(4), 0, 0, 0)
                        addView(name)
                        addView(idText)
                    })
                    // Hk8o7CmpWEV6is6f24TW.onClick → select(index, true).
                    setOnClickListener { selectPage(index) }
                }
                group.addView(row)
                if (page.id == savedId) {
                    radio.isChecked = true
                    selected = page
                }
            }
            rows.addView(group)
        }

        /** zkQ4kenQD4EJspRyMc8 — select + persist the page id. */
        private fun selectPage(index: Int) {
            val page = pages.getOrNull(index) ?: return
            selected = page
            putPref(KEY_SELECTED_PAGE, page.id)
            (rows.getChildAt(0) as? RadioGroup)?.let { group ->
                for (i in 0 until group.childCount) {
                    val row = group.getChildAt(i) as? LinearLayout ?: continue
                    (row.getChildAt(0) as? RadioButton)?.isChecked = (i == index)
                }
            }
            status.text = "Selected: ${page.displayName}"  // mod: selected_page
        }

        /** GlpYnD4fzyFAQr8O2uoN.onClick — validate, persist, dispatch. */
        private fun onPost() {
            val page = selected ?: run {
                // Mod: "Vui lòng chọn Page muốn đăng trước." (libnc.so.c 39739)
                Toast.makeText(activity, "Please select a Page to post to first.",
                    Toast.LENGTH_SHORT).show()
                return
            }
            val title = titleInput.text?.toString()?.trim().orEmpty()
            if (title.isBlank()) {
                // Mod: item.video.repost.require_title
                Toast.makeText(activity, "Please enter a title.", Toast.LENGTH_SHORT).show()
                return
            }
            // Mod: blank content falls back to the title (GlpYnD4.onClick).
            val content = contentInput.text?.toString()?.trim().orEmpty().ifBlank { title }
            val comment = commentInput.text?.toString()?.trim().orEmpty()
            if (videoUrl.isNullOrBlank()) {
                Toast.makeText(activity, "No video URL available",
                    Toast.LENGTH_SHORT).show()
                return
            }
            // Mod: saved_comment persists the comment text across uses.
            putPref(KEY_SAVED_COMMENT, comment)

            // Mod: "Đang đăng video lên Page: <name>" (libnc.so.c 40156) in
            // the dialog while the workers run; the port dismisses and
            // reports completion with a Toast.
            dialog.dismiss()
            Toast.makeText(activity, "Posting video to Page: ${page.displayName}",
                Toast.LENGTH_SHORT).show()
            L.i(TAG, "posting videoId=${video.videoId} quality=${selectedVideo?.label} " +
                "tag=${selectedVideo?.tag} to page=${page.id}")
            io.execute {
                val outcome = runCatching { postVideoAndComment(page, videoUrl!!, title, content, comment) }
                main.post {
                    outcome.onSuccess {
                        L.i(TAG, "post complete: videoId=${it.videoId} " +
                                "comment=${it.commentPosted} commentId=${it.commentId}")
                        val message = if (comment.isNotBlank() && !it.commentPosted) {
                            "Reposted to ${page.displayName}, but comment failed."
                        } else {
                            "Reposted successfully to ${page.displayName}."
                        }
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    }.onFailure {
                        L.w(TAG, "post failed", it)
                        Toast.makeText(
                            activity,
                            "Repost failed: ${it.message ?: it.javaClass.simpleName}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

        /**
         * The posting workers port (mod: GlpYnD4.onClick → EQRQ →
         * FgOJykHvmRjW8PKecX8 → mOn7CdfVdcUJlauSjSYb video post → the
         * 500 ms-delayed PfLG6 comment post; libnc.so.c 1315615–1316948 and
         * 1270353–1271097). The port already holds the video URL in
         * VideoData, so the mod's watch-page scrape and alternate engine
         * never come into play — this starts at the Graph calls.
         *
         * A comment failure does NOT throw: the video is already posted, so
         * completion reports that partial failure via Toast.
         */
        @Throws(Exception::class)
        private fun postVideoAndComment(
            page: Page, videoUrl: String,
            title: String, content: String, comment: String,
        ): PostOutcome {
            // Video post — POST /{pageId}/videos, form-encoded, the PAGE
            // token as both the Bearer header and the access_token param
            // (mod: mOn7.T7PucPwlExzHkx17YkC, libnc.so.c 1270353).
            val videoForm = ArrayList<Pair<String, String>>(4)
            videoForm.add("access_token" to page.accessToken)
            // Mod: a blank title is omitted from the body entirely.
            if (title.isNotBlank()) videoForm.add("title" to title)
            videoForm.add("description" to content)
            videoForm.add("file_url" to videoUrl)
            val videoBody = graphPost(GRAPH_BASE + page.id + "/videos", page.accessToken, videoForm)
            // Mod: only the "id" key is read (mOn7$bsrnc.onSuccess).
            val videoId = JSONObject(videoBody).getString("id")
            L.i(TAG, "video posted: id=$videoId")

            // No comment → posting is complete.
            if (comment.isBlank()) return PostOutcome(videoId, "", false, null)

            // Mod: Handler().postDelayed(r, 500) — give Facebook a moment to
            // finish processing the video before commenting. We are already
            // on the io worker, so a plain sleep is equivalent.
            Thread.sleep(500)
            return try {
                val commentBody = graphPost(
                    GRAPH_BASE + videoId + "/comments", page.accessToken,
                    listOf("access_token" to page.accessToken, "message" to comment))
                val commentId = JSONObject(commentBody).getString("id")
                L.i(TAG, "comment posted: id=$commentId")
                PostOutcome(videoId, commentId, true, null)
            } catch (e: Exception) {
                // The video is already posted; preserve that distinction in
                // the completion Toast instead of treating the whole repost
                // as failed.
                L.w(TAG, "comment post failed: ${e.message}")
                PostOutcome(videoId, "", false, e.message)
            }
        }
    }

    /**
     * The best captured video URL, by the same ladder the card summary uses:
     * Page repost is different from download: Graph's `file_url` accepts one
     * URL, while every embedded playlist rendition is a video-only DASH
     * track. Prefer the highest-quality captured progressive/muxed MP4 and
     * only fall back to a split video track when there is no muxed candidate
     * at all (which also covers genuinely silent source videos).
     */
    internal fun bestVideoUrl(video: DownloadHook.VideoData): String? {
        return bestVideoQuality(video)?.url
    }

    private fun bestVideoQuality(video: DownloadHook.VideoData): RepostMediaSelector.Candidate? {
        val candidates = ArrayList<RepostMediaSelector.Candidate>()
        video.qualities.forEach {
            candidates += RepostMediaSelector.Candidate(it.label, it.url, it.tag)
        }
        // Embedded MPD representations are explicitly video-only. They are
        // lower priority than every muxed candidate, but they must still be
        // available for genuinely silent / split-only source media.
        video.playlist?.videos.orEmpty().forEach { rendition ->
            val label = rendition.qualityLabel.ifBlank {
                rendition.height.takeIf { it.isNotBlank() }?.let { "${it}p" } ?: "DASH video"
            }
            candidates += RepostMediaSelector.Candidate(
                label = label,
                url = rendition.baseUrl,
                tag = "mp4_no_audio",
            )
        }
        return RepostMediaSelector.best(candidates)
    }
}
