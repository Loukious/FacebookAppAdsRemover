package tn.loukious.facebookappadsremover.core

import java.io.File

/**
 * Which files a session export/import may touch (stock 576 layout):
 * app_light_prefs/.../authentication, logged_in_<uid>, dbl_local_auth_<uid>
 * and app_config_experiment/logged_in_user_ids.
 */
internal object SessionPaths {
    private const val AUTH_FILE = "authentication"
    private val SESSION_PREFIXES = arrayOf("logged_in_", "dbl_local_auth_")

    fun isSessionFileName(name: String): Boolean =
        name == AUTH_FILE || SESSION_PREFIXES.any { name.startsWith(it) }

    /**
     * Resolves an export key to a file under [dataDir], or null when the key
     * is not a session file the export could have produced. Absolute paths,
     * "." / ".." segments and anything resolving outside the data dir are
     * rejected, so an import can never write arbitrary files into
     * Facebook's storage.
     */
    fun sessionTarget(dataDir: File, rel: String): File? = runCatching {
        val segments = rel.split('/', '\\')
        if (rel.isBlank() || rel.startsWith("/") || rel.startsWith("\\") ||
            segments.any { it.isEmpty() || it == ".." || it == "." }
        ) {
            return@runCatching null
        }
        val target = File(dataDir, rel)
        if (!isSessionFileName(target.name)) return@runCatching null
        val base = dataDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
        target.takeIf { it.canonicalPath.startsWith(base) }
    }.getOrNull()
}
