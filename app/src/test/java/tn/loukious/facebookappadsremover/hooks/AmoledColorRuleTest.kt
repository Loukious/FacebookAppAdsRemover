package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmoledColorRuleTest {
    @Test fun morpheDarkNeutralRuleMatchesMeasuredPalette() {
        assertTrue(AmoledColorRule.isDarkNeutral(0xFF101011.toInt()))
        assertTrue(AmoledColorRule.isDarkNeutral(0xFF252728.toInt()))
        assertFalse(AmoledColorRule.isDarkNeutral(0xFF3A3B3C.toInt()))
        assertFalse(AmoledColorRule.isDarkNeutral(0xFF102A10.toInt()))
        assertFalse(AmoledColorRule.isDarkNeutral(0xFFF2F4F7.toInt()))
    }

    @Test fun resolverOnlyBlackensBackgroundTokens() {
        val card = 0xFF252728.toInt()
        val facebook580Card = 0xFF333334.toInt()
        assertEquals(AmoledColorRule.BLACK, AmoledColorRule.resolver(card, "CARD_BACKGROUND"))
        assertEquals(AmoledColorRule.BLACK,
            AmoledColorRule.resolver(facebook580Card, "CARD_BACKGROUND_FLAT"))
        assertEquals(card, AmoledColorRule.resolver(card, "SECONDARY_TEXT"))
        assertEquals(facebook580Card,
            AmoledColorRule.resolver(facebook580Card, "SECONDARY_BUTTON_BACKGROUND"))
    }

    @Test fun commentsBackgroundBlackensMeasuredGray() {
        val comments = 0xFF101011.toInt()
        assertEquals(AmoledColorRule.BLACK, AmoledColorRule.resolver(comments, "COMMENT_BACKGROUND"))
        assertEquals(AmoledColorRule.BLACK, AmoledColorRule.untokened(comments))
    }

    @Test fun untokenedRouteBlackensDarkNeutralButKeepsDivider() {
        assertEquals(AmoledColorRule.BLACK, AmoledColorRule.untokened(0xFF242526.toInt()))
        assertEquals(AmoledColorRule.BLACK, AmoledColorRule.untokened(0xFF333334.toInt()))
        assertEquals(0xFF3A3B3C.toInt(), AmoledColorRule.untokened(0xFF3A3B3C.toInt()))
    }

    @Test fun unreadOverlayCanBeMadeOpaqueWithoutChangingItsVisibleShade() {
        assertEquals(0xFF040D19.toInt(), AmoledColorRule.opaqueOverBlack(0x192D88FF))
    }
}
