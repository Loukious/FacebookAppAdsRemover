package tn.loukious.facebookappadsremover.ui

internal enum class ReportAction { SAVE, SHARE }
internal enum class ReportPhase { WAITING, READY, PICKER }

/** Only small identifiers are persisted; report contents stay in a private file. */
internal data class DiagnosticsRequest(
    val nonce: String,
    val action: ReportAction,
    val startedAt: Long,
    val phase: ReportPhase = ReportPhase.WAITING,
    val fileName: String? = null,
) {
    val kind: SessionRequestKind
        get() = if (action == ReportAction.SAVE) SessionRequestKind.DIAGNOSTICS_SAVE
        else SessionRequestKind.DIAGNOSTICS_SHARE

    fun accepts(replyNonce: String?, replyKind: SessionRequestKind): Boolean =
        phase == ReportPhase.WAITING && nonce == replyNonce && kind == replyKind

    fun expired(now: Long): Boolean = phase == ReportPhase.WAITING &&
        (now < startedAt || now - startedAt >= TIMEOUT_MILLIS)

    fun encode(): String = listOf(nonce, action.name, startedAt.toString(), phase.name, fileName.orEmpty())
        .joinToString("\t")

    companion object {
        const val TIMEOUT_MILLIS = 6000L

        fun decode(value: String?): DiagnosticsRequest? = runCatching {
            val parts = value?.split('\t') ?: return null
            if (parts.size != 5 || parts[0].isBlank()) return null
            val phase = ReportPhase.valueOf(parts[3])
            val file = parts[4].takeIf(String::isNotEmpty)
            if (file != null && (file.contains('/') || file.contains('\\') || file == "." || file == "..")) return null
            if (phase != ReportPhase.WAITING && file == null) return null
            DiagnosticsRequest(parts[0], ReportAction.valueOf(parts[1]), parts[2].toLong(), phase, file)
        }.getOrNull()
    }
}
