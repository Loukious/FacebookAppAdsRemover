package tn.loukious.facebookappadsremover.hooks

/**
 * Stable *shape* of Facebook's reel button factory, not its obfuscated name.
 *
 * 578 had 19 parameters; 580 added a trailing boolean. Keep the established
 * argument slots for the download action, and preserve any additional flags
 * from a real native button created for that exact reel. If Facebook inserts
 * or rearranges arguments in that prefix, fail closed until re-reversed.
 */
internal object ReelFactorySignature {
    private const val SESSION = "com.facebook.auth.usersession.FbUserSession"
    private const val FUNCTION1 = "kotlin.jvm.functions.Function1"

    fun accepts(types: List<String>): Boolean =
        types.size >= 19 &&
            types[0] == SESSION &&
            types[6] == "java.lang.Boolean" && types[7] == "java.lang.Boolean" &&
            // Facebook declares the test-id slot as Object (String values
            // are accepted); the following caption/metadata slots are String.
            (types[8] == "java.lang.Object" || types[8] == "java.lang.String") &&
            (9..10).all { types[it] == "java.lang.String" } &&
            (11..14).all { types[it] == FUNCTION1 } &&
            types[15] == "int" &&
            types.drop(16).all { it == "boolean" }
}
