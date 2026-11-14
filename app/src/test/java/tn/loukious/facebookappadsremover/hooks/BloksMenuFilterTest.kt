package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BloksMenuFilterTest {
    private fun script(id: Long) = """\t(f43 \"click\" (dkc \"{\\\"bmid\\\":$id,\\\"bookmark_type\\\":\\\"x\\\"}\"))"""
    private fun row(key: String, label: String) =
        """{"r":{"#":"$label","7":[{"c":{"&":"\t(e1w (dnk \"$key\") (e25 0))"}}]}}"""
    private fun menu(vararg rows: String) =
        """{"ft":{"k1":"${script(11111)}","k2":"${script(22222)}","k3":"${script(33333)}","kx":"\t(e2f 1)"},""" +
            """"tree":{"t":{" ":[{"h":{"#":"Header, with [brackets]"}},{"g":{"c":[${rows.joinToString(",")}]}}]}}}"""

    private val all = arrayOf(row("k1", "One"), row("k2", "Two"), row("k3", "Three"))

    @Test fun nothingHiddenReturnsSameText() {
        val text = menu(*all)
        assertSame(text, BloksMenuFilter.filter(text) { false })
    }

    @Test fun textWithoutBookmarksIsUntouched() {
        val text = """{"tree":[1,2,3]}"""
        assertSame(text, BloksMenuFilter.filter(text) { true })
    }

    @Test fun cutsFirstMiddleAndLastRows() {
        val text = menu(*all)
        assertEquals(menu(all[1], all[2]), BloksMenuFilter.filter(text) { it == 11111L })
        assertEquals(menu(all[0], all[2]), BloksMenuFilter.filter(text) { it == 22222L })
        assertEquals(menu(all[0], all[1]), BloksMenuFilter.filter(text) { it == 33333L })
    }

    @Test fun cutsSeveralRows() {
        val text = menu(*all)
        assertEquals(menu(all[0]), BloksMenuFilter.filter(text) { it != 11111L })
        assertEquals(menu(all[2]), BloksMenuFilter.filter(text) { it != 33333L })
        assertEquals(menu(), BloksMenuFilter.filter(text) { true })
    }

    @Test fun filteringTwiceChangesNothingMore() {
        val once = BloksMenuFilter.filter(menu(*all)) { it == 22222L }
        assertSame(once, BloksMenuFilter.filter(once) { it == 22222L })
    }
}
