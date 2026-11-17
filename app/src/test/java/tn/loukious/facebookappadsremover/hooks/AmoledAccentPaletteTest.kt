package tn.loukious.facebookappadsremover.hooks

import android.content.ComponentCallbacks
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Proxy

class AmoledAccentPaletteTest {
    private val prefsField = Settings::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private val resourcesField = AmoledThemeHook::class.java.getDeclaredField("accentResources")
        .apply { isAccessible = true }
    private val snapshotField = AmoledThemeHook::class.java.getDeclaredField("accentSnapshot")
        .apply { isAccessible = true }
    private val guard = AmoledThemeHook::class.java.getDeclaredField("readingAccentPalette")
        .apply { isAccessible = true }.get(null) as ThreadLocal<*>
    private val init = AmoledThemeHook::class.java.getDeclaredMethod("initAccentPalette", Context::class.java)
        .apply { isAccessible = true }
    private val accent = AmoledThemeHook::class.java.getDeclaredMethod("accent").apply { isAccessible = true }
    private val resultHook = Class.forName(
        "tn.loukious.facebookappadsremover.hooks.AmoledThemeHook\$UntokenedResultHook",
    ).getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) as XposedInterface.Hooker

    private val ids = intArrayOf(
        android.R.color.system_accent1_0, android.R.color.system_accent1_10,
        android.R.color.system_accent1_50, android.R.color.system_accent1_100,
        android.R.color.system_accent1_200, android.R.color.system_accent1_300,
        android.R.color.system_accent1_400, android.R.color.system_accent1_500,
        android.R.color.system_accent1_600, android.R.color.system_accent1_700,
        android.R.color.system_accent1_800, android.R.color.system_accent1_900,
        android.R.color.system_accent1_1000,
    )
    private val darkNeutral = 0xFF1B1B1B.toInt()
    private val baseline = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFFFBFF.toInt(), 0xFFFCE8FF.toInt(), 0xFFF6D7FF.toInt(),
        0xFFE4B5F5.toInt(), 0xFFC899D7.toInt(), 0xFFAC7DBB.toInt(), 0xFF9162A0.toInt(),
        0xFF764986.toInt(), 0xFF5D316D.toInt(), 0xFF441956.toInt(), darkNeutral,
        0xFF000000.toInt(),
    )

    private var previousPrefs: Any? = null
    private var previousResources: Any? = null
    private var previousSnapshot: Any? = null
    private var previousGuard: Any? = null
    private lateinit var logs: MockedStatic<Log>
    private lateinit var systemResources: MockedStatic<Resources>
    private lateinit var prefs: SharedPreferences
    private lateinit var caller: Context
    private lateinit var app: Context
    private lateinit var resources: Resources
    private lateinit var callerResources: Resources
    private lateinit var globalResources: Resources
    private lateinit var assets: AssetManager
    private lateinit var colors: IntArray
    private lateinit var callback: ComponentCallbacks
    private var onRead: (Int) -> Unit = {}

    @Before fun prepare() {
        logs = Mockito.mockStatic(Log::class.java)
        systemResources = Mockito.mockStatic(Resources::class.java)
        previousPrefs = prefsField.get(null)
        previousResources = resourcesField.get(null)
        previousSnapshot = snapshotField.get(null)
        previousGuard = guard.get()
        resourcesField.set(null, null)
        snapshotField.set(null, null)
        guard.remove()

        prefs = Mockito.mock(SharedPreferences::class.java)
        for (key in listOf(Settings.APPEARANCE_AMOLED, Settings.APPEARANCE_ACCENT)) {
            Mockito.`when`(prefs.contains(key)).thenReturn(true)
            Mockito.`when`(prefs.getBoolean(key, false)).thenReturn(true)
        }
        prefsField.set(null, prefs)

        caller = Mockito.mock(Context::class.java)
        app = Mockito.mock(Context::class.java)
        resources = Mockito.mock(Resources::class.java)
        callerResources = Mockito.mock(Resources::class.java)
        globalResources = Mockito.mock(Resources::class.java)
        assets = Mockito.mock(AssetManager::class.java)
        colors = baseline.copyOf()
        Mockito.`when`(caller.applicationContext).thenReturn(app)
        Mockito.`when`(caller.resources).thenReturn(callerResources)
        Mockito.`when`(app.resources).thenReturn(resources)
        Mockito.`when`(resources.assets).thenAnswer { assets }
        systemResources.`when`<Resources> { Resources.getSystem() }.thenReturn(globalResources)
        Mockito.doAnswer { call -> callback = call.getArgument(0); null }
            .`when`(app).registerComponentCallbacks(Mockito.any(ComponentCallbacks::class.java))

        for ((index, id) in ids.withIndex()) {
            // Emulate the framework seam actually installed on getColor. A
            // direct resource stub would miss recursive AMOLED recoloring.
            Mockito.`when`(resources.getColor(id, null)).thenAnswer {
                val raw = colors[index]
                onRead(index)
                throughColorHook(raw)
            }
        }
    }

    @After fun cleanup() {
        prefsField.set(null, previousPrefs)
        resourcesField.set(null, previousResources)
        snapshotField.set(null, previousSnapshot)
        @Suppress("UNCHECKED_CAST")
        (guard as ThreadLocal<Any?>).apply {
            if (previousGuard == null) remove() else set(previousGuard)
        }
        systemResources.close()
        logs.close()
    }

    private fun throughColorHook(raw: Int): Int {
        val chain = Proxy.newProxyInstance(
            XposedInterface.Chain::class.java.classLoader, arrayOf(XposedInterface.Chain::class.java),
        ) { _, method, _ ->
            check(method.name == "proceed") { "Unexpected chain method: ${method.name}" }
            raw
        } as XposedInterface.Chain
        return resultHook.intercept(chain) as Int
    }

    private fun initialize() { init.invoke(AmoledThemeHook, caller) }
    private fun palette(): IntArray? = accent.invoke(AmoledThemeHook) as IntArray?
    private fun changedPalette() = baseline.copyOf().apply {
        this[7] = 0xFF467244.toInt()
        this[11] = 0xFF102010.toInt()
    }

    @Test fun readsHostApplicationOverlayShadesWithoutRecursiveAmoledRecoloring() {
        initialize()
        val palette = palette()!!
        assertArrayEquals(baseline, palette)
        assertEquals(darkNeutral, palette[11])
        // This token selects tone 10, proving the preserved shade reaches the
        // accent rule rather than only being stored in a cache.
        assertEquals(darkNeutral, AccentColorRule.resolver(0xFF001934.toInt(), "BLUE_LINK", palette))
        assertEquals(AmoledColorRule.BLACK, resources.getColor(android.R.color.system_accent1_900, null))
        assertNull(guard.get())

        Mockito.verify(app).getResources()
        Mockito.verify(caller, Mockito.never()).getResources()
        systemResources.verify({ Resources.getSystem() }, Mockito.never())
        Mockito.verifyNoInteractions(callerResources, globalResources)
        assertSame(palette, palette())
    }

    @Test fun configurationChangeReloadsOverlayColorsWithTheSameAssets() {
        initialize()
        val previous = palette()!!
        colors = changedPalette()
        callback.onConfigurationChanged(Mockito.mock(Configuration::class.java))
        val refreshed = palette()!!
        assertArrayEquals(colors, refreshed)
        assertArrayEquals(baseline, previous)
        assertNotSame(previous, refreshed)
    }

    @Test fun assetReplacementReloadsOverlayColorsWithoutACallback() {
        initialize()
        val previous = palette()!!
        assets = Mockito.mock(AssetManager::class.java)
        colors = changedPalette()
        val refreshed = palette()!!
        assertArrayEquals(colors, refreshed)
        assertNotSame(previous, refreshed)
        assertSame(refreshed, palette())
    }

    @Test fun assetReplacementDuringReadDiscardsMixedShadesAndRetriesLater() {
        initialize()
        val replacement = changedPalette()
        onRead = { index ->
            if (index == 6) {
                assets = Mockito.mock(AssetManager::class.java)
                colors = replacement
                onRead = {}
            }
        }
        assertNull(palette())
        assertNull(snapshotField.get(null))
        assertNull(guard.get())
        assertArrayEquals(replacement, palette())
    }

    @Test fun failedReadClearsTheGuardAndConfigurationChangeAllowsRecovery() {
        initialize()
        onRead = { index -> if (index == 3) throw IllegalStateException("overlay read failed") }
        assertNull(palette())
        assertNull(guard.get())
        assertEquals(AmoledColorRule.BLACK, resources.getColor(android.R.color.system_accent1_900, null))

        onRead = {}
        callback.onConfigurationChanged(Mockito.mock(Configuration::class.java))
        assertArrayEquals(baseline, palette())
    }

    @Test fun disabledAccentSkipsPaletteLoadingWhileOrdinaryAmoledStillApplies() {
        initialize()
        Mockito.`when`(prefs.getBoolean(Settings.APPEARANCE_ACCENT, false)).thenReturn(false)
        assertNull(palette())
        for (id in ids) Mockito.verify(resources, Mockito.never()).getColor(id, null)
        assertEquals(AmoledColorRule.BLACK, throughColorHook(darkNeutral))
    }
}
