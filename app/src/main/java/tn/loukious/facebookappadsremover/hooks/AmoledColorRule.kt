package tn.loukious.facebookappadsremover.hooks

/**
 * Morphe's AMOLED colour rule, kept Android-free so it can be regression tested.
 * Resolver colours use semantic token names; resource/server colours do not.
 */
internal object AmoledColorRule {
    const val BLACK: Int = -0x1000000
    private const val MAX_CHANNEL = 0x2A
    private const val MAX_SEMANTIC_BACKGROUND_CHANNEL = 0x3F
    private const val MAX_SPREAD = 8

    private val BACKGROUND_TOKENS = setOf(
        "WASH", "SURFACE", "CARD", "ELEVATION", "BANNER", "PRIMARY_UI",
        "WEB_WASH", "FBLITE_WASH", "SURFACE_BACKGROUND", "BACKGROUND_SURFACE",
        "DEVICE_BACKGROUND", "BACKGROUND_DEEMPHASIZED", "CARD_BACKGROUND",
        "CARD_BACKGROUND_FLAT", "CARD_BACKGROUND_LEGACY_WEB", "BACKGROUND_CARD",
        "BACKGROUND_ELEVATION", "LIST_CELL_BACKGROUND", "ATTACHMENT_FOOTER_BACKGROUND",
        "ENTITY_HEADER_BACKGROUND", "COMMENT_BACKGROUND", "COMMENT_BACKGROUND_DEEMPHASIZED",
        "BOTTOM_SHEET_BACKGROUND_DEEMPHASIZED", "BOTTOM_SHEET_INSET_BACKGROUND",
        "POPOVER_BACKGROUND", "FADED_POPOVER_BACKGROUND", "NAV_BAR_BACKGROUND",
        "TAB_BAR_BACKGROUND", "BACKGROUND_BANNER", "BACKGROUND_PRIMARY_UI",
    )

    /** Measured Facebook chrome colours Morphe's literal sweep blackens. */
    private val KNOWN_LITERAL_BACKGROUNDS = setOf(
        0xFF101011.toInt(),
        0xFF171818.toInt(),
        0xFF202021.toInt(),
        0xFF242526.toInt(),
        0xFF252728.toInt(),
        0xFF333334.toInt(),
    )

    fun resolver(color: Int, tokenName: String?): Int = when {
        tokenName in BACKGROUND_TOKENS && isSemanticBackgroundNeutral(color) -> BLACK
        else -> color
    }

    fun untokened(color: Int): Int = when {
        color in KNOWN_LITERAL_BACKGROUNDS || isDarkNeutral(color) -> BLACK
        else -> color
    }

    fun isDarkNeutral(color: Int): Boolean {
        return isNeutralUnder(color, MAX_CHANNEL)
    }

    /**
     * Facebook 580 moved several semantic card surfaces from #242526-ish to
     * #333334. They are still background tokens, so allow a slightly wider
     * range only when the resolver supplies that semantic context. Arbitrary
     * literals/buttons keep the stricter Morphe-style threshold above.
     */
    fun isSemanticBackgroundNeutral(color: Int): Boolean =
        isNeutralUnder(color, MAX_SEMANTIC_BACKGROUND_CHANNEL)

    /** Render an ARGB overlay exactly as it appears over AMOLED black. */
    fun opaqueOverBlack(color: Int): Int {
        val alpha = (color ushr 24) and 0xFF
        if (alpha == 0xFF) return color
        fun composite(channel: Int): Int = (channel * alpha + 127) / 255
        val red = composite((color ushr 16) and 0xFF)
        val green = composite((color ushr 8) and 0xFF)
        val blue = composite(color and 0xFF)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun isNeutralUnder(color: Int, maxChannel: Int): Boolean {
        if ((color ushr 24) != 0xFF) return false
        val red = (color ushr 16) and 0xFF
        val green = (color ushr 8) and 0xFF
        val blue = color and 0xFF
        val high = maxOf(red, green, blue)
        val low = minOf(red, green, blue)
        return high <= maxChannel && high - low <= MAX_SPREAD
    }
}
