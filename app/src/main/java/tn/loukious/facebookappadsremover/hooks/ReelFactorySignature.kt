package tn.loukious.facebookappadsremover.hooks

/**
 * Stable *shape* of Facebook's reel button factory, not its obfuscated name.
 *
 * 578 had 19 parameters; 580 added a trailing boolean. 587 inserted a second
 * primary-text wrapper before the two labels. Clear that optional secondary
 * text so a copied native Like/count handler cannot survive in Download;
 * preserve trailing flags from the real native button used as the template.
 */
internal object ReelFactorySignature {
    private const val SESSION = "com.facebook.auth.usersession.FbUserSession"
    private const val FUNCTION1 = "kotlin.jvm.functions.Function1"

    data class Layout internal constructor(private val extraPrimary: Int) {
        /** Map a known 578 slot around the additional 587 secondary text. */
        fun argumentIndex(knownSlot: Int): Int =
            knownSlot + if (knownSlot >= 3) extraPrimary else 0

        fun copyTemplate(template: Array<Any?>): Array<Any?> =
            template.copyOf().also { args ->
                // 587's extra C03AU is optional and null-guarded by the native
                // button wrapper. Its native value may contain Like/count
                // text and callbacks; our button needs no secondary count.
                if (extraPrimary == 1) args[3] = null
            }
    }

    fun accepts(types: List<String>): Boolean = layout(types) != null

    fun layout(types: List<String>): Layout? {
        if (types.firstOrNull() != SESSION) return null
        return (0..1).firstNotNullOfOrNull { extra ->
            val layout = Layout(extra)
            fun slot(index: Int): String? = types.getOrNull(layout.argumentIndex(index))
            if (types.size < 19 + extra ||
                (extra == 1 && types[2] != types[3]) ||
                slot(3) != slot(4) ||
                slot(6) != "java.lang.Boolean" || slot(7) != "java.lang.Boolean" ||
                // Facebook declares the test-id slot as Object (String values
                // are accepted); the following caption/metadata slots are String.
                slot(8) !in setOf("java.lang.Object", "java.lang.String") ||
                (9..10).any { slot(it) != "java.lang.String" } ||
                (11..14).any { slot(it) != FUNCTION1 } ||
                slot(15) != "int" ||
                types.drop(layout.argumentIndex(16)).any { it != "boolean" }
            ) null else layout
        }
    }
}
