package tn.loukious.facebookappadsremover.hooks

/** Pure ranking for Page repost sources. Prefer a muxed/progressive file so
 * audio is retained. If Facebook exposes only split/video-only renditions,
 * fall back to the highest-quality video track instead of refusing a
 * legitimately silent source. */
internal object RepostMediaSelector {
    data class Candidate(
        val label: String,
        val url: String,
        val tag: String,
    )

    fun best(candidates: List<Candidate>): Candidate? {
        val videos = candidates.asSequence()
            .filter(::isVideo)
            .distinctBy { it.url }
            .toList()
        return rank(videos.filter(::isMuxedVideo)).firstOrNull()
            ?: rank(videos).firstOrNull()
    }

    private fun rank(candidates: List<Candidate>): List<Candidate> = candidates
        .asSequence()
        .distinctBy { it.url }
        .sortedWith(
            compareByDescending<Candidate> { qualityScore(it.label) }
                .thenByDescending { isNonSdUrl(it.url) }
                .thenBy { it.url },
        )
        .toList()

    private fun isVideo(candidate: Candidate): Boolean {
        val tag = candidate.tag.lowercase()
        return tag != "m4a" && !tag.startsWith("audio")
    }

    private fun isMuxedVideo(candidate: Candidate): Boolean {
        val tag = candidate.tag.lowercase()
        if (tag.contains("no_audio") || tag == "m4a" || tag.startsWith("audio")) return false
        return tag == "mp4" || tag.startsWith("video") || tag.isBlank()
    }

    private fun qualityScore(label: String): Int {
        val numeric = Regex("(\\d{2,4})p?", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (numeric != null) return numeric
        return when {
            label.contains("HD", ignoreCase = true) -> 720
            label.contains("SD", ignoreCase = true) -> 480
            else -> 0
        }
    }

    private fun isNonSdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return !lower.contains("tag=sve_sd") &&
            !lower.contains("tag=sd") &&
            !lower.contains("sve_sd")
    }
}
