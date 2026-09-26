package tn.loukious.facebookappadsremover.hooks

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Pure JVM signature matching for the Facebook-specific feed adapters.
 *
 * Callers discover the candidate *classes* through their stable Dex strings
 * before passing methods here. The known FB 580 parameter order is preferred;
 * changed positions/arity are accepted only when one method uniquely carries
 * the semantic argument roles. Method names are never used for matching.
 *
 * This helper does not install hooks or touch Facebook objects. `describe`
 * converts java.lang.reflect.Method for an Android caller; the descriptor
 * overloads are executable by ordinary local JVM tests.
 */
internal object FeedHookSignatures {
    private const val SESSION = "com.facebook.auth.usersession.FbUserSession"
    private const val LIST = "com.google.common.collect.ImmutableList"
    private const val STRING = "java.lang.String"
    private const val OBJECT = "java.lang.Object"

    data class MethodInfo(
        val owner: String,
        val name: String,
        val parameters: List<String>,
        val returnType: String,
        val isPublic: Boolean = true,
        val isStatic: Boolean = false,
        val isAbstract: Boolean = false,
        val isSynthetic: Boolean = false,
        val isBridge: Boolean = false,
    )

    data class ListMatch(val method: MethodInfo, val listArgIndex: Int)

    enum class LateKind { STORAGE, VENDING, LIFECYCLE }

    fun describe(method: Method): MethodInfo = MethodInfo(
        owner = method.declaringClass.name,
        name = method.name,
        parameters = method.parameterTypes.map { it.name },
        returnType = method.returnType.name,
        isPublic = Modifier.isPublic(method.modifiers),
        isStatic = Modifier.isStatic(method.modifiers),
        isAbstract = Modifier.isAbstract(method.modifiers),
        isSynthetic = method.isSynthetic,
        isBridge = method.isBridge,
    )

    /**
     * FB 580: public ApC(FbUserSession, X, ImmutableList, int): X/2Kq.
     * Prefer the established 4-/3-argument ordering. On signature drift,
     * require one session, one list and one int in at most seven arguments.
     * Exclude private multi-argument helpers such as FB 580 X/1xc.A00.
     */
    fun csr(methods: Collection<MethodInfo>): ListMatch? = preferred(
        methods,
        exact = { m ->
            val p = m.parameters
            (p.size == 4 && p[0] == SESSION && p[2] == LIST && p[3] == "int" &&
                p[1] != SESSION && p[1] != LIST && p[1] != "int" && isReference(p[1])) ||
                (p.size == 3 && p[0] == SESSION && p[1] == LIST && p[2] == "int")
        },
        semantic = { m ->
            val p = m.parameters
            hookable(m) && referenceReturn(m) && p.size in 3..7 &&
                p.count { it == SESSION } == 1 &&
                p.count { it == LIST } == 1 &&
                p.count { it == "int" } == 1
        },
    )

    /**
     * Storage: void(any, list, int); vending: void(list, String);
     * lifecycle: void(session, any, list). Prefer those established orders;
     * then accept a unique semantic match with at most seven arguments.
     * The caller supplies the kind inferred from the class discovery anchor.
     */
    fun late(methods: Collection<MethodInfo>, kind: LateKind): ListMatch? = preferred(
        methods,
        exact = { m ->
            val p = m.parameters
            when (kind) {
                LateKind.STORAGE -> p.size == 3 &&
                    p[0] != LIST && p[0] != "int" && isReference(p[0]) &&
                    p[1] == LIST && p[2] == "int"
                LateKind.VENDING -> p == listOf(LIST, STRING)
                LateKind.LIFECYCLE -> p.size == 3 &&
                    p[0] == SESSION && p[1] != LIST && p[1] != SESSION &&
                    isReference(p[1]) && p[2] == LIST
            }
        },
        semantic = { m ->
            val p = m.parameters
            if (!hookable(m) || m.returnType != "void" ||
                p.count { it == LIST } != 1 || p.size > 7
            ) false else when (kind) {
                LateKind.STORAGE -> p.size >= 3 &&
                    p.count { it == "int" } == 1 &&
                    p.any { it != LIST && it != "int" && isReference(it) }
                LateKind.VENDING -> p.size >= 2 &&
                    p.count { it == STRING } == 1 && SESSION !in p
                LateKind.LIFECYCLE -> p.size >= 3 &&
                    p.count { it == SESSION } == 1 &&
                    p.any { it != LIST && it != SESSION && isReference(it) }
            }
        },
    )

    private fun preferred(
        methods: Collection<MethodInfo>,
        exact: (MethodInfo) -> Boolean,
        semantic: (MethodInfo) -> Boolean,
    ): ListMatch? {
        val candidates = methods.filter(semantic).distinct()
        val preferred = candidates.filter(exact)
        // A tie at the preferred rank is ambiguous too; do not fall through.
        val match = (preferred.ifEmpty { candidates }).singleOrNull() ?: return null
        return ListMatch(match, match.parameters.indexOf(LIST))
    }

    private fun hookable(m: MethodInfo): Boolean =
        m.isPublic && !m.isStatic && !m.isAbstract && !m.isSynthetic && !m.isBridge

    private fun referenceReturn(m: MethodInfo): Boolean =
        isReference(m.returnType) && m.returnType != OBJECT

    private fun isReference(type: String): Boolean = type !in setOf(
        "void", "boolean", "byte", "short", "char", "int", "long", "float", "double",
    )
}
