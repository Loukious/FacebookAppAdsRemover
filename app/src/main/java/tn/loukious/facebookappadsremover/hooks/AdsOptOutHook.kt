package tn.loukious.facebookappadsremover.hooks

import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import tn.loukious.facebookappadsremover.core.AdSurface
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * M2.6 ads-opt-out spoof — port of original-mod hook #1 of the EQRQ installer
 * (GhTaOHdq4kOQbdazOJvf.EQRQYtm1nsiKbj6wysU, libnc.so.c 118019–118734):
 *
 *     hookAllMethods(findClass("X.1wO"), "A03", GhTa$Hk8o7CmpWEV6is6f24TW)
 *
 * X.1wO is FB's BasicAds opt-in/ad-free-session status class
 * (updateBasicAdsOptInStatusAndTime$…basicads). Its ad-free-session getter
 * (mod build: A03(); stock 576.0.0.42.73: A02() — indices shift per release)
 * returns `mobileConfigGate && afsos.get()` where afsos is the persisted
 * "ad-free session opt status" pref (default false). The mod's replacement
 * resolved java.lang.Boolean.TRUE via JNI (field name string at rodata
 * 0x5c4ee = "TRUE") and returned it whenever the swHOME_ADS pref was on.
 *
 * Effect: FB itself believes an ad-free session is active, so the server
 * stops serving sponsored content — including the sponsored circles in the
 * Stories tray, which no client-side feed filter ever saw.
 *
 * Nothing references an obfuscated name: the class is found by its unique
 * "adprefs/" literal, and the getters are resolved structurally (boolean,
 * zero-arg, non-static). Both boolean getters are spoofed — afsos (the mod's
 * exact target) and baos ("basic ads opt status", same supplier factory,
 * default false) — since obfuscated method indices drift between builds and
 * both states push FB toward serving no ads.
 */
object AdsOptOutHook {

    private const val TAG = "FBAR.AdsOptOut"

    /** Anchor literal — unique in the secondary dex (only C268821b uses it). */
    private const val CLASS_ANCHOR = "adprefs/"

    /** Cache key for the discovered class name (MethodCache.store classes map). */
    const val CACHE_KEY = "ads.basicAdsOptIn"

    /** Resolved-once getters (the mod cached its field lookups too). */
    private val spoofed = ArrayList<Method>()

    /**
     * Finds the BasicAds opt-in class via DexKit and hooks its boolean
     * getters — the exact hook the mod installed.
     *
     * @return the class name when found, for the discovery cache.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): String? {
        val hits = runCatching {
            bridge.findClass {
                matcher { addUsingString(CLASS_ANCHOR, StringMatchType.Equals) }
            }
        }.getOrElse {
            L.w(TAG, "DexKit query failed for ads-opt-out class", it)
            return null
        }
        val className = hits.firstOrNull()?.name
        if (className == null) {
            L.w(TAG, "NOT_FOUND BasicAds opt-in class (anchor: $CLASS_ANCHOR)")
            return null
        }
        return if (hookGetters(module, classLoader, className)) className else null
    }

    /** Cache-hit path: hook the previously discovered class directly. */
    fun installCached(module: XposedInterface, classLoader: ClassLoader, className: String): Boolean =
        hookGetters(module, classLoader, className)

    private fun hookGetters(module: XposedInterface, classLoader: ClassLoader, className: String): Boolean {
        val cls = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        if (cls == null) {
            L.w(TAG, "class resolve failed: $className")
            return false
        }
        // Structural resolution — obfuscated names drift per release:
        // boolean, zero-arg, non-static declared methods are exactly the
        // ad-free-session getter (A02) and the basic-ads opt-status getter
        // (A03) in 576.0.0.42.73.
        val getters = cls.declaredMethods.filter {
            it.returnType == java.lang.Boolean.TYPE &&
                it.parameterCount == 0 &&
                !Modifier.isStatic(it.modifiers)
        }
        if (getters.isEmpty()) {
            L.w(TAG, "no boolean zero-arg getters on $className — not the opt-in class?")
            return false
        }
        var installed = 0
        for (g in getters) {
            runCatching {
                g.isAccessible = true
                module.hook(g).intercept(SpoofHook)
                spoofed.add(g)
                installed++
            }.onFailure { L.w(TAG, "hook failed on $className.${g.name}", it) }
        }
        if (installed == 0) return false
        L.i(TAG, "hooked $installed ad-free-session getter(s) on $className: " +
                getters.joinToString { it.name } + "()")
        return true
    }

    /**
     * Hk8o beforeHookedMethod port: the getter is GLOBAL (not scoped to feed,
     * Stories or Reels). Only spoof ad-free when every ad family is selected;
     * enabling just Story ads must not suppress News Feed or Marketplace ads.
     */
    private object SpoofHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (spoofEnabled()) return java.lang.Boolean.TRUE
            return chain.proceed()
        }
    }

    private fun spoofEnabled(): Boolean =
        Settings.blockAdsOn(setOf(AdSurface.NEWS_FEED, AdSurface.STORIES, AdSurface.REELS)) &&
            Settings.getBoolean(Settings.ADS_MARKETPLACE, true) &&
            Settings.getBoolean(Settings.ADS_GAME_ADS, true)
}
