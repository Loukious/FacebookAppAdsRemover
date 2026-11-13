package tn.loukious.facebookappadsremover.hooks

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** The SDK's serialized viewType describes the ad actually owned by the activity. */
internal object GameAdLifecyclePolicy {
    const val VIEW_TYPE_EXTRA = "viewType"
    private const val ACTIVITY_API = "com.facebook.ads.internal.api.AudienceNetworkActivityApi"

    enum class Kind { INTERSTITIAL, REWARDED, UNKNOWN }

    private val interstitialTypes = setOf(
        "INTERSTITIAL_NATIVE_VIDEO", "INTERSTITIAL_NATIVE_IMAGE", "INTERSTITIAL_NATIVE_CAROUSEL",
        "INTERSTITIAL_NATIVE_PLAYABLE", "INTERSTITIAL_VIDEO_LEADING_PLAYABLE",
        "DYNAMIC_INTERSTITIAL", "CHAINED_INT_AD",
    )
    private val rewardedTypes = setOf(
        "REWARDED_VIDEO", "REWARDED_PLAYABLE", "REWARDED_CAROUSEL", "DYNAMIC_REWARDED_VIDEO",
        "CHAINED_RV_AD", "VIDEO_LEADING_PLAYABLE", "REWARDED_IMAGE",
    )

    fun classify(viewType: Any?): Kind {
        val name = when (viewType) {
            is Enum<*> -> viewType.name
            is String -> viewType
            else -> return Kind.UNKNOWN
        }
        return when (name) {
            in interstitialTypes -> Kind.INTERSTITIAL
            in rewardedTypes -> Kind.REWARDED
            else -> Kind.UNKNOWN
        }
    }

    fun classifyActivity(viewType: Any?, activity: Any): Kind {
        val kinds = linkedSetOf(classify(viewType)).apply { remove(Kind.UNKNOWN) }
        // Newer SDKs marshal the Intent extras. Their stable activity API owns
        // the decoded enum, so inspect only that direct API field and its enum fields.
        for (field in fields(activity.javaClass).filter { isActivityApi(it.type) }.take(4)) {
            val api = read(field, activity) ?: continue
            for (apiField in fields(api.javaClass)) {
                if (!apiField.type.isEnum && apiField.type != Enum::class.java) continue
                val kind = classify(read(apiField, api))
                if (kind != Kind.UNKNOWN) kinds.add(kind)
            }
        }
        // Conflicting actual SDK metadata must not turn a rewarded ad into a plain one.
        return kinds.singleOrNull() ?: Kind.UNKNOWN
    }

    private fun isActivityApi(type: Class<*>): Boolean {
        val seen = HashSet<Class<*>>()
        fun matches(current: Class<*>?): Boolean {
            if (current == null || !seen.add(current)) return false
            return current.name == ACTIVITY_API || current.interfaces.any(::matches) || matches(current.superclass)
        }
        return matches(type)
    }

    private fun fields(type: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && !current.name.startsWith("android.") &&
            !current.name.startsWith("java.") && result.size < 48) {
            for (field in current.declaredFields) {
                if (result.size >= 48) break
                if (!Modifier.isStatic(field.modifiers) && !field.type.isPrimitive) result.add(field)
            }
            current = current.superclass
        }
        return result
    }

    private fun read(field: Field, owner: Any): Any? = runCatching {
        field.isAccessible = true
        field.get(owner)
    }.getOrNull()

    /** Unknown activity types keep the same reward protection as known rewarded ads. */
    fun shouldClose(kind: Kind, completeReward: () -> Boolean): Boolean =
        kind == Kind.INTERSTITIAL || completeReward()
}
