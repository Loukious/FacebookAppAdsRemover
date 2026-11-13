package tn.loukious.facebookappadsremover.hooks

/** The native menu item shared by legacy and Facebook 587 story actions. */
internal object StoryMenuSignature {
    fun accepts(types: List<String>, iconType: String): Boolean =
        types.size == 10 &&
            types[1] == iconType &&
            types[2] == "java.lang.CharSequence" &&
            (3..5).all { types[it] == "java.lang.Integer" } &&
            (6..7).all { types[it] == "java.lang.String" } &&
            types[8] == "int" && types[9] == "boolean"
}
