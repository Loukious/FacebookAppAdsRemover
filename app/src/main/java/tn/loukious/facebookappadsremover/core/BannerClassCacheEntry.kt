package tn.loukious.facebookappadsremover.core

/**
 * Stores banner anchor provenance alongside each obfuscated class name.
 * A bare legacy class name has no reliable surface and is never restored.
 */
data class BannerClassCacheEntry(val className: String, val surfaces: Set<AdSurface>) {
    fun encode(): String = surfaces.sortedBy { it.ordinal }
        .joinToString(",") { it.name } + "|" + className

    companion object {
        fun parse(raw: String): BannerClassCacheEntry? {
            val divider = raw.indexOf('|')
            if (divider <= 0 || divider >= raw.lastIndex) return null
            val names = raw.substring(0, divider).split(',')
            val parsed = names.map { name ->
                AdSurface.values().firstOrNull { it.name == name }
                    ?: return null
            }.toSet()
            val cls = raw.substring(divider + 1)
            // DEX obfuscated segments can start with a digit (X.2Qm), even
            // though a Java source identifier usually cannot.
            if (parsed.isEmpty() || !cls.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z0-9_$]+)+"))) {
                return null
            }
            return BannerClassCacheEntry(cls, parsed)
        }
    }
}
