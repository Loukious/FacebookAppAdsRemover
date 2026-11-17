package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun truncatedOrUnbalancedTextIsLeftAlone() {
        val text = menu(*all)
        val truncated = text.dropLast(3)
        assertSame(truncated, BloksMenuFilter.filter(truncated) { true })
        val unbalanced = text.replaceFirst("[", "{")
        assertSame(unbalanced, BloksMenuFilter.filter(unbalanced) { true })
    }

    @Test fun onlyObjectsAreCut() {
        // A bare string element that refers to a bookmark script is not a row.
        val text = """{"ft":{"k1":"${script(11111)}","k2":"${script(22222)}"},"tree":["(dnk k1)",{"r":"(dnk k2)"}]}"""
        assertEquals(
            """{"ft":{"k1":"${script(11111)}","k2":"${script(22222)}"},"tree":["(dnk k1)"]}""",
            BloksMenuFilter.filter(text) { true },
        )
    }

    @Test fun reportsHiddenEntriesTheFormatNoLongerExposesAsRows() {
        val text = """{"ft":{"k1":"${script(11111)}","k2":"${script(22222)}"},"tree":{"a":"(dnk k1)","b":"(dnk k2)"}}"""
        assertSame(text, BloksMenuFilter.filter(text) { it == 11111L })
        assertEquals(true, BloksMenuFilter.namesHidden(text) { it == 11111L })
        assertEquals(false, BloksMenuFilter.namesHidden(text) { false })
    }

    // A menu shaped like the real one: state table, sections with visibility scripts, "See more".
    private fun vis(section: String) =
        """"v":["1","\t(e1w (e2e 0 (dnt \"st01\")) (f6t (e25 0) \"$section\" false) (e2a (e25 1) \"gone\" \"visible\"))"]"""
    private fun sectionOf(key: String, body: String) = """{"s":{" ":[$body],${vis(key)}}}"""
    private val button =
        """{"b":{" ":[{"l":{"t":[" ","\t(dkc (e2a (dnv \"more1\" 1) (fna \"x7\" \"see_less_label\") (fna \"x9\" \"see_more_label\")))"]}}]}}"""
    private fun fullMenu(sections: String, moreInitial: String = "false") =
        """{"ft":{"k1":"${script(11111)}","k2":"${script(22222)}","k3":"${script(33333)}"},""" +
            """"data":[{"id":"st01","type":"gs","data":{"key":"PE_PLAZA_SECTIONS:is_hidden_map","mode":"d","initial":{}}},""" +
            """{"id":"more1","type":"ls","data":{"initial":$moreInitial}}],"tree":{"t":{" ":[$sections]}}}"""
    private val mainRows = """${row("k1", "One")},{"m":{" ":[${row("k2", "Two")}],"v":["1","\t(e2a (dnv \"more1\" 1) \"visible\" \"gone\")"]}}"""
    private val main = sectionOf("all_blended", "$mainRows,$button")
    private val help = sectionOf("help", row("k3", "Help centre"))
    private val promo = sectionOf("upsell", """{"card":{"#":"Promo"}}""")

    @Test fun cutsWholeSectionsByKey() {
        val text = fullMenu("$main,$help,$promo")
        val rules = { keys: Set<String> -> BloksMenuFilter.Rules(bookmark = { false }, section = { it in keys }) }
        assertSame(text, BloksMenuFilter.filter(text, rules(emptySet())))
        assertEquals(fullMenu("$main,$promo"), BloksMenuFilter.filter(text, rules(setOf("help"))))
        assertEquals(fullMenu("$main"), BloksMenuFilter.filter(text, rules(setOf("help", "upsell"))))
    }

    @Test fun expandShowsEverythingAndDropsTheButton() {
        val text = fullMenu("$main,$help")
        val expanded = fullMenu("${sectionOf("all_blended", mainRows)},$help", moreInitial = "true")
        assertEquals(expanded, BloksMenuFilter.filter(text, BloksMenuFilter.Rules(bookmark = { false }, expand = true)))
    }

    @Test fun buttonStaysWhenItsStateIsNotFound() {
        val text = fullMenu("$main,$help").replace("\"type\":\"ls\"", "\"type\":\"zz\"")
        assertSame(text, BloksMenuFilter.filter(text, BloksMenuFilter.Rules(bookmark = { false }, expand = true)))
    }

    /** Rows of a section since November 2026: no logging JSON, the id is the call's first value. */
    private fun bare(id: Long) =
        """\t(f43 \"click\" (abc \"bookmark_id\" \"item_id\") (dkc \"$id\" (dkc) \"$id\"))"""
    private fun bareNumber(id: Long) =
        """\t(f43 \"click\" (abc \"bookmark_id\" \"item_id\") (dkc (eud $id) (dkc) (eud $id)))"""

    @Test fun cutsRowsNamedOnlyByBookmarkId() {
        val text = """{"ft":{"k1":"${bare(11111)}","k2":"${bareNumber(22222)}","k3":"${script(33333)}"},""" +
            """"tree":{"c":[${row("k1", "One")},${row("k2", "Two")},${row("k3", "Three")}]}}"""
        val out = BloksMenuFilter.filter(text) { it == 11111L || it == 22222L }
        assertFalse(out.contains("One"))
        assertFalse(out.contains("Two"))
        assertTrue(out.contains("Three"))
    }
}
