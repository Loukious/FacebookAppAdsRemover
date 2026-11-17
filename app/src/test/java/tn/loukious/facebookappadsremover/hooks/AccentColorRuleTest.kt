package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentColorRuleTest {
    /** A palette whose shade i is the grey 0xFF0000ii, so the chosen shade can be read back. */
    private val palette = IntArray(AccentColorRule.TONES.size) { 0xFF000000.toInt() or it }
    private fun shade(color: Int) = color and 0xFFFFFF

    @Test fun accentTokenTakesTheShadeOfItsOwnLightness() {
        // #0866FF is about L* 48: tone 50, the eighth shade (system_accent1_500).
        assertEquals(7, shade(AccentColorRule.resolver(0xFF0866FF.toInt(), "ACCENT", palette)))
        // The dark theme's link #75B6FF is about L* 72: tone 70.
        assertEquals(5, shade(AccentColorRule.resolver(0xFF75B6FF.toInt(), "BLUE_LINK", palette)))
        // The palest tint goes to the palest shades.
        assertEquals(2, shade(AccentColorRule.resolver(0xFFEBF5FF.toInt(), "ACCENT_DEEMPHASIZED", palette)))
    }

    @Test fun alphaIsKept() {
        val out = AccentColorRule.literal(0x331D85FC, palette)
        assertEquals(0x33, out ushr 24)
        assertTrue(shade(out) < AccentColorRule.TONES.size)
    }

    @Test fun tokensThatAreNotBlueStayAsTheyAre() {
        // The wordmark is white in the dark theme.
        assertEquals(0xFFFFFFFF.toInt(), AccentColorRule.resolver(0xFFFFFFFF.toInt(), "FB_WORDMARK", palette))
        assertEquals(0xFF080809.toInt(), AccentColorRule.resolver(0xFF080809.toInt(), "PRIMARY_TEXT", palette))
    }

    @Test fun otherColoursAndReactionsStayAsTheyAre() {
        assertEquals(0xFFDD2334.toInt(), AccentColorRule.literal(0xFFDD2334.toInt(), palette))
        // Like has a blue of its own in the dark theme...
        assertEquals(0xFF3E93F8.toInt(), AccentColorRule.resolver(0xFF3E93F8.toInt(), "REACTION_LIKE", palette))
        // ...and is the accent blue itself in the light theme, where it cannot be told apart.
        assertEquals(7, shade(AccentColorRule.resolver(0xFF0866FF.toInt(), "REACTION_LIKE", palette)))
        // Fully transparent: nothing to recolour.
        assertEquals(0x000866FF, AccentColorRule.literal(0x000866FF, palette))
    }

    @Test fun literalBluesAreSwappedWithoutAToken() {
        assertEquals(7, shade(AccentColorRule.literal(0xFF0866FF.toInt(), palette)))
        assertEquals(7, shade(AccentColorRule.resolver(0xFF0866FF.toInt(), null, palette)))
    }

    @Test fun amoledRunsFirstAndLeavesTheAccentToThisRule() {
        fun both(color: Int, token: String) =
            AccentColorRule.resolver(AmoledColorRule.resolver(color, token), token, palette)
        // A dark neutral background is blackened and then is no blue any more.
        assertEquals(AmoledColorRule.BLACK, both(0xFF242526.toInt(), "SURFACE_BACKGROUND"))
        // The unread tint is no background token: AMOLED passes it on, the accent takes it.
        val tint = both(0x192D88FF, "NEW_NOTIFICATION_BACKGROUND")
        assertEquals(0x19, tint ushr 24)
        assertTrue(shade(tint) < AccentColorRule.TONES.size)
        // The accent itself.
        assertEquals(7, shade(both(0xFF0866FF.toInt(), "ACCENT")))
    }

    @Test fun onlyColoursThatWouldBeSwappedAreCandidates() {
        assertTrue(AccentColorRule.isLiteral(0xFF0866FF.toInt()))
        assertTrue(AccentColorRule.isLiteral(0x331D85FC))
        assertFalse(AccentColorRule.isLiteral(0x000866FF))
        assertFalse(AccentColorRule.isLiteral(0xFF242526.toInt()))
    }

    @Test fun withoutAPaletteNothingChanges() {
        assertEquals(0xFF0866FF.toInt(), AccentColorRule.resolver(0xFF0866FF.toInt(), "ACCENT", null))
        assertEquals(0xFF0866FF.toInt(), AccentColorRule.literal(0xFF0866FF.toInt(), null))
    }

    @Test fun blueFamily() {
        assertTrue(AccentColorRule.isBlue(0xFF0866FF.toInt()))
        assertTrue(AccentColorRule.isBlue(0xFFE7F3FF.toInt()))
        assertFalse(AccentColorRule.isBlue(0xFF65686C.toInt()))
        assertFalse(AccentColorRule.isBlue(0xFF3FBB46.toInt()))
        assertFalse(AccentColorRule.isBlue(0xFF8A2BE2.toInt()))
    }
}
