package tn.loukious.facebookappadsremover.hooks

import kotlin.math.abs
import kotlin.math.pow

/**
 * Turns Facebook's blue accent into the system's accent ("Material You").
 * Kept Android-free so it can be regression tested.
 *
 * A colour is swapped for the shade of the system palette that is as light as
 * the colour itself, so text stays as readable on it as Facebook meant it to
 * be, and it keeps its alpha. Two things are swapped:
 *
 * - what Facebook's colour scheme resolves for its accent tokens, whatever
 *   blue that is in the current build and theme;
 * - the literal blues measured in Facebook 588, which reach the screen past
 *   the scheme (resources, server-sent colour strings, paints).
 *
 * Reactions keep their colours, and nothing that is not blue is touched.
 */
internal object AccentColorRule {

    /** Tones of `system_accent1_0, _10, _50, _100 ... _900, _1000`, lightest first. */
    val TONES = intArrayOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0)

    private val TOKENS = setOf(
        "ACCENT", "ACCENT_DEEMPHASIZED", "BLUE_LINK", "FB_WORDMARK", "STORY_UNSEEN",
        "PRIMARY_BUTTON_BACKGROUND", "PRIMARY_BUTTON_PRESSED",
        "PRIMARY_DEEMPHASIZED_BUTTON_BACKGROUND", "PRIMARY_DEEMPHASIZED_BUTTON_PRESSED",
        "PRIMARY_DEEMPHASIZED_BUTTON_ICON", "PRIMARY_DEEMPHASIZED_BUTTON_TEXT",
        "NEW_NOTIFICATION_BACKGROUND", "TAB_BAR_ACTIVE_ICON", "HIGHLIGHT",
    )

    /** Facebook 588's blues without alpha: accent, links, pressed states and their dark-theme pairs. */
    private val LITERALS = setOf(
        0x0866FF, 0x0064D1, 0x0051A1, 0x1D85FC, 0x2D88FF, 0x5AA7FF, 0x75B6FF,
        0xEBF5FF, 0xE7F3FF, 0xDDEDFE,
    )

    /** A colour the scheme resolved for [tokenName]; [palette] holds the 13 system shades. */
    fun resolver(color: Int, tokenName: String?, palette: IntArray?): Int = when {
        palette == null || palette.size != TONES.size -> color
        tokenName in TOKENS && isBlue(color) -> swap(color, palette)
        else -> literal(color, palette)
    }

    /** A colour that reached the screen past the scheme. */
    fun literal(color: Int, palette: IntArray?): Int = when {
        palette == null || palette.size != TONES.size -> color
        (color and 0xFFFFFF) in LITERALS && (color ushr 24) != 0 -> swap(color, palette)
        else -> color
    }

    private fun swap(color: Int, palette: IntArray): Int {
        val tone = lightness(color)
        var best = 0
        for (i in TONES.indices) if (abs(TONES[i] - tone) < abs(TONES[best] - tone)) best = i
        return (color and 0xFF000000.toInt()) or (palette[best] and 0xFFFFFF)
    }

    /** Facebook's blue family: hue around 210 degrees, from the palest tint to the darkest link. */
    fun isBlue(color: Int): Boolean {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max != b || max == min) return false
        // Facebook's greys lean blue too (#65686C is 6.5 % saturated); its palest tint is 7.8 %.
        if ((max - min) * 100 < max * 7) return false
        val hue = 240.0 - 60.0 * (g - r) / (max - min)
        return hue in 195.0..230.0
    }

    /** CIE L* of the colour without its alpha, 0 (black) to 100 (white). */
    fun lightness(color: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val y = 0.2126 * linear((color ushr 16) and 0xFF) +
            0.7152 * linear((color ushr 8) and 0xFF) +
            0.0722 * linear(color and 0xFF)
        return if (y <= 216.0 / 24389.0) y * 24389.0 / 27.0 else 116.0 * Math.cbrt(y) - 16.0
    }
}
