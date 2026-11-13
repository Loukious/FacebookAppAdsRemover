package tn.loukious.facebookappadsremover.hooks

import com.facebook.ads.internal.api.AudienceNetworkActivityApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAdLifecyclePolicyTest {
    // SDK enum fields are obfuscated; Enum.name remains the serialized ad-format name.
    enum class SdkViewType {
        INTERSTITIAL_NATIVE_VIDEO, REWARDED_VIDEO, FULL_SCREEN_VIDEO,
    }

    interface InheritedActivityApi : AudienceNetworkActivityApi
    class SdkApi(val decodedViewType: SdkViewType) : InheritedActivityApi
    open class ActivityWrapper(val api: InheritedActivityApi)

    @Test fun marshalledIntentUsesTheActualDecodedSdkApiEnum() {
        val activity = ActivityWrapper(SdkApi(SdkViewType.INTERSTITIAL_NATIVE_VIDEO))
        val kind = GameAdLifecyclePolicy.classifyActivity(null, activity)
        assertEquals(GameAdLifecyclePolicy.Kind.INTERSTITIAL, kind)
        assertTrue(GameAdLifecyclePolicy.shouldClose(kind) { false })
    }

    @Test fun inheritedActivityApiFieldsAndInterfacesRemainSupported() {
        class LegacyWrapper(api: InheritedActivityApi) : ActivityWrapper(api)
        val activity = LegacyWrapper(SdkApi(SdkViewType.REWARDED_VIDEO))
        val kind = GameAdLifecyclePolicy.classifyActivity(null, activity)
        assertEquals(GameAdLifecyclePolicy.Kind.REWARDED, kind)
        assertFalse(GameAdLifecyclePolicy.shouldClose(kind) { false })
    }

    @Test fun unrelatedObjectGraphsCannotClassifyAnUnknownActivity() {
        class UnrelatedWrapper(val child: ActivityWrapper)
        val activity = UnrelatedWrapper(ActivityWrapper(SdkApi(SdkViewType.INTERSTITIAL_NATIVE_VIDEO)))
        assertEquals(GameAdLifecyclePolicy.Kind.UNKNOWN, GameAdLifecyclePolicy.classifyActivity(null, activity))
    }

    @Test fun conflictingDirectAndDecodedSdkFormatsKeepTheRewardGate() {
        val activity = ActivityWrapper(SdkApi(SdkViewType.REWARDED_VIDEO))
        val kind = GameAdLifecyclePolicy.classifyActivity(SdkViewType.INTERSTITIAL_NATIVE_VIDEO, activity)
        assertEquals(GameAdLifecyclePolicy.Kind.UNKNOWN, kind)
        assertFalse(GameAdLifecyclePolicy.shouldClose(kind) { false })
    }

    @Test fun ordinaryInterstitialActivityClosesWithoutCallingARewardListener() {
        var rewardCalls = 0
        val kind = GameAdLifecyclePolicy.classify(SdkViewType.INTERSTITIAL_NATIVE_VIDEO)
        assertTrue(GameAdLifecyclePolicy.shouldClose(kind) { rewardCalls++; false })
        assertEquals(0, rewardCalls)
    }

    @Test fun allShippedOrdinaryInterstitialFormatsKeepTheCloseFallback() {
        for (name in listOf(
            "INTERSTITIAL_NATIVE_VIDEO", "INTERSTITIAL_NATIVE_IMAGE", "INTERSTITIAL_NATIVE_CAROUSEL",
            "INTERSTITIAL_NATIVE_PLAYABLE", "INTERSTITIAL_VIDEO_LEADING_PLAYABLE",
            "DYNAMIC_INTERSTITIAL", "CHAINED_INT_AD",
        )) {
            assertTrue(name, GameAdLifecyclePolicy.shouldClose(GameAdLifecyclePolicy.classify(name)) { false })
        }
    }

    @Test fun rewardedActivityClosesOnlyAfterSuccessfulCompletion() {
        val kind = GameAdLifecyclePolicy.classify(SdkViewType.REWARDED_VIDEO)
        assertFalse(GameAdLifecyclePolicy.shouldClose(kind) { false })
        assertTrue(GameAdLifecyclePolicy.shouldClose(kind) { true })
    }

    @Test fun formatsWithoutRewardedInTheirNameStillRequireCompletion() {
        for (name in listOf("VIDEO_LEADING_PLAYABLE", "CHAINED_RV_AD")) {
            assertEquals(GameAdLifecyclePolicy.Kind.REWARDED, GameAdLifecyclePolicy.classify(name))
            assertFalse(GameAdLifecyclePolicy.shouldClose(GameAdLifecyclePolicy.classify(name)) { false })
        }
    }

    @Test fun unknownAndAmbiguousActivityFormatsCannotCloseWithoutCompletion() {
        for (value in listOf(null, SdkViewType.FULL_SCREEN_VIDEO, "REMOTE_ACTIVITY_LAUNCHER", "INTERSTITIAL_UNKNOWN")) {
            assertEquals(GameAdLifecyclePolicy.Kind.UNKNOWN, GameAdLifecyclePolicy.classify(value))
            assertFalse(GameAdLifecyclePolicy.shouldClose(GameAdLifecyclePolicy.classify(value)) { false })
            assertTrue(GameAdLifecyclePolicy.shouldClose(GameAdLifecyclePolicy.classify(value)) { true })
        }
    }
}
