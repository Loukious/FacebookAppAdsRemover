package tn.loukious.facebookappadsremover.hooks

import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.Window
import io.github.libxposed.api.XposedInterface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class HdrHookTest {
    private val hooks = HashMap<Executable, XposedInterface.Hooker>()
    private val prefsField = Settings::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private var previousPrefs: Any? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var logs: MockedStatic<Log>
    private val key = "color-transfer-request"

    @Before fun prepare() {
        logs = Mockito.mockStatic(Log::class.java)
        previousPrefs = prefsField.get(null)
        prefs = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(prefs.contains(Settings.APPEARANCE_NO_HDR)).thenReturn(true)
        Mockito.`when`(prefs.getBoolean(Settings.APPEARANCE_NO_HDR, false)).thenReturn(true)
        prefsField.set(null, prefs)
        resetInstallation()
    }

    @After fun cleanup() {
        prefsField.set(null, previousPrefs)
        resetInstallation()
        logs.close()
    }

    private fun resetInstallation() {
        HdrHook::class.java.getDeclaredField("installed").apply { isAccessible = true }.setBoolean(null, false)
        HdrHook::class.java.getDeclaredField("surfaceFallbackInstalled")
            .apply { isAccessible = true }.setBoolean(null, false)
        (HdrHook::class.java.getDeclaredField("reported").apply { isAccessible = true }.get(null) as MutableSet<*>).clear()
    }

    private inline fun <reified T> proxy(crossinline invoke: (Method, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method, args ?: emptyArray())
        } as T

    private fun install(surfaceHooksAvailable: Boolean = true) {
        val module = proxy<XposedInterface> { method, args ->
            check(method.name == "hook")
            val target = args[0] as Executable
            if (!surfaceHooksAvailable && (target.declaringClass == SurfaceView::class.java ||
                    target.declaringClass.name.startsWith("android.view.SurfaceControl"))) {
                throw UnsupportedOperationException("surface hooks unavailable")
            }
            proxy<XposedInterface.HookBuilder> { action, values ->
                check(action.name == "intercept")
                hooks[target] = values[0] as XposedInterface.Hooker
                proxy<XposedInterface.HookHandle> { _, _ -> null }
            }
        }
        assertTrue(HdrHook.install(module))
    }

    private class Call(val method: Method, val receiver: Any?, val args: List<Any?>,
                       val result: Any? = null, val proceed: () -> Unit = {}) {
        val forwarded = ArrayList<List<Any?>>()
        val chain: XposedInterface.Chain = Proxy.newProxyInstance(
            XposedInterface.Chain::class.java.classLoader, arrayOf(XposedInterface.Chain::class.java),
        ) { _, target, values ->
            when (target.name) {
                "getExecutable" -> method
                "getThisObject" -> receiver
                "getArgs" -> args
                "getArg" -> args[values!![0] as Int]
                "proceed" -> {
                    val actual = if (values.isNullOrEmpty()) args else (values[0] as Array<*>).toList()
                    forwarded.add(actual)
                    proceed()
                    result
                }
                else -> error("Unexpected chain call: ${target.name}")
            }
        } as XposedInterface.Chain
    }

    private fun run(call: Call): Any? = hooks.getValue(call.method).intercept(call.chain)
    private fun method(cls: Class<*>, name: String, count: Int) =
        cls.declaredMethods.single { it.name == name && it.parameterCount == count }
    private fun configure(descrambler: Boolean = false) = MediaCodec::class.java.declaredMethods.single {
        it.name == "configure" && it.parameterCount == 4 &&
            (it.parameterTypes[2] == Int::class.javaPrimitiveType) == descrambler
    }
    private fun format(mime: String) = Mockito.mock(MediaFormat::class.java).also {
        Mockito.`when`(it.getString(MediaFormat.KEY_MIME)).thenReturn(mime)
    }
    private fun codec(transfer: Int) = Mockito.mock(MediaCodec::class.java).also { codec ->
        val accepted = Mockito.mock(MediaFormat::class.java)
        Mockito.`when`(accepted.containsKey(key)).thenReturn(true)
        Mockito.`when`(accepted.getInteger(key)).thenReturn(transfer)
        Mockito.`when`(codec.inputFormat).thenReturn(accepted)
    }
    private fun warning(message: String) = logs.verify { Log.w("FBAR.Hdr", message, null) }

    @Test fun acceptedDecoderRequestIsCheckedAfterConfigureInBothOverloads() {
        install()
        for (descrambler in listOf(false, true)) {
            val format = format("video/hevc")
            val codec = codec(3)
            val surface = Mockito.mock(Surface::class.java)
            val args = if (descrambler) listOf(format, surface, 0, null) else listOf(format, surface, null, 0)
            val result = Any()
            val call = Call(configure(descrambler), codec, args, result) {
                Mockito.verify(format).setInteger(key, 3)
                Mockito.verify(codec, Mockito.never()).getInputFormat()
            }
            assertSame(result, run(call))
            assertEquals(listOf(args), call.forwarded)
            Mockito.verify(codec).getInputFormat()
            Mockito.verify(format, Mockito.never()).setInteger(MediaFormat.KEY_COLOR_TRANSFER, 3)
        }
        logs.verify { Log.i("FBAR.Hdr", "decoder SDR request accepted") }
    }

    @Test fun rejectedRequestUsesSurfaceFallbackWithoutInterruptingPlayback() {
        install()
        val format = format("video/hevc")
        val call = Call(configure(), codec(0), listOf(format, Mockito.mock(Surface::class.java), null, 0))
        run(call)
        assertEquals(1, call.forwarded.size)
        warning("decoder SDR request unsupported; SDR surface headroom fallback enabled")
    }

    @Test fun unavailableSurfaceFallbackIsReportedRatherThanClaimingHdrIsDisabled() {
        install(surfaceHooksAvailable = false)
        val call = Call(configure(), codec(0), listOf(format("video/hevc"), null, null, 0))
        run(call)
        assertEquals(1, call.forwarded.size)
        warning("decoder SDR request unsupported; surface headroom unavailable; HDR may remain active")
    }

    @Test fun failedAcknowledgmentIsReportedAsUnverified() {
        install()
        val codec = Mockito.mock(MediaCodec::class.java)
        Mockito.`when`(codec.inputFormat).thenThrow(IllegalStateException("not readable"))
        run(Call(configure(), codec, listOf(format("video/hevc"), null, null, 0)))
        warning("decoder SDR request unverified; SDR surface headroom fallback enabled")
    }

    @Test fun missingAcknowledgmentKeyIsTreatedAsUnsupported() {
        install()
        val codec = Mockito.mock(MediaCodec::class.java)
        val accepted = Mockito.mock(MediaFormat::class.java)
        Mockito.`when`(codec.inputFormat).thenReturn(accepted)
        run(Call(configure(), codec, listOf(format("video/hevc"), null, null, 0)))
        warning("decoder SDR request unsupported; SDR surface headroom fallback enabled")
        Mockito.verify(accepted, Mockito.never()).getInteger(key)
    }

    @Test fun encodersAudioAndDisabledSettingKeepOriginalConfiguration() {
        install()
        for ((mime, flags) in listOf("video/avc" to MediaCodec.CONFIGURE_FLAG_ENCODE, "audio/aac" to 0)) {
            val format = format(mime)
            val codec = codec(0)
            val call = Call(configure(), codec, listOf(format, null, null, flags))
            run(call)
            assertEquals(listOf(call.args), call.forwarded)
            Mockito.verify(format, Mockito.never()).setInteger(key, 3)
            Mockito.verify(codec, Mockito.never()).getInputFormat()
        }
        Mockito.`when`(prefs.getBoolean(Settings.APPEARANCE_NO_HDR, false)).thenReturn(false)
        val format = format("video/hevc")
        val codec = codec(0)
        run(Call(configure(), codec, listOf(format, null, null, 0)))
        Mockito.verify(format, Mockito.never()).setInteger(key, 3)
        Mockito.verify(codec, Mockito.never()).getInputFormat()
    }

    @Test fun configurationFailurePropagatesWithoutReadingTheCodec() {
        install()
        val codec = codec(0)
        val failure = IllegalStateException("original configure failure")
        val call = Call(configure(), codec, listOf(format("video/hevc"), null, null, 0)) { throw failure }
        try {
            run(call)
            fail("Expected configure failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        Mockito.verify(codec, Mockito.never()).getInputFormat()
    }

    @Test fun headroomCapsUseOneAndPreserveTheSurfaceAndBufferRatio() {
        install()
        val surface = Mockito.mock(SurfaceControl::class.java)
        val window = Call(method(Window::class.java, "setDesiredHdrHeadroom", 1), null, listOf(0f))
        run(window)
        assertEquals(listOf(1f), window.forwarded.single())
        val layer = Call(method(SurfaceControl.Transaction::class.java, "setDesiredHdrHeadroom", 2), null, listOf(surface, 5f))
        run(layer)
        assertEquals(listOf(surface, 1f), layer.forwarded.single())
        val brightness = Call(method(SurfaceControl.Transaction::class.java, "setExtendedRangeBrightness", 3), null, listOf(surface, 2.5f, 5f))
        run(brightness)
        assertEquals(listOf(surface, 2.5f, 1f), brightness.forwarded.single())
        Mockito.`when`(prefs.getBoolean(Settings.APPEARANCE_NO_HDR, false)).thenReturn(false)
        val disabled = Call(brightness.method, null, brightness.args)
        run(disabled)
        assertEquals(brightness.args, disabled.forwarded.single())
    }

    @Test fun newlyCreatedSurfaceGetsCapBeforeBeingReturnedAndTransactionIsClosed() {
        install()
        val surface = Mockito.mock(SurfaceControl::class.java)
        Mockito.mockConstruction(SurfaceControl.Transaction::class.java).use { construction ->
            assertSame(surface, run(Call(method(SurfaceControl.Builder::class.java, "build", 0), null, emptyList(), surface)))
            val transaction = construction.constructed().single()
            val ordered = Mockito.inOrder(transaction)
            ordered.verify(transaction).setDesiredHdrHeadroom(surface, 1f)
            ordered.verify(transaction).apply()
            ordered.verify(transaction).close()
        }
    }

    @Test fun attachedSurfaceViewIsCappedWithoutAnApplicationHdrRequest() {
        install()
        val view = Mockito.mock(SurfaceView::class.java)
        val call = Call(method(SurfaceView::class.java, "onAttachedToWindow", 0), view, emptyList())
        run(call)
        assertEquals(1, call.forwarded.size)
        Mockito.verify(view).setDesiredHdrHeadroom(1f)
    }

    @Test fun failedSurfaceCapKeepsTheSurfaceAndClosesItsTransaction() {
        install()
        val surface = Mockito.mock(SurfaceControl::class.java)
        Mockito.mockConstruction(SurfaceControl.Transaction::class.java) { transaction, _ ->
            Mockito.doThrow(IllegalStateException("apply failed")).`when`(transaction).apply()
        }.use { construction ->
            assertSame(surface, run(Call(method(SurfaceControl.Builder::class.java, "build", 0), null, emptyList(), surface)))
            Mockito.verify(construction.constructed().single()).close()
        }
    }

    @Test fun disabledSettingDoesNotAllocateACappingTransaction() {
        install()
        Mockito.`when`(prefs.getBoolean(Settings.APPEARANCE_NO_HDR, false)).thenReturn(false)
        val surface = Mockito.mock(SurfaceControl::class.java)
        Mockito.mockConstruction(SurfaceControl.Transaction::class.java).use { construction ->
            assertSame(surface, run(Call(method(SurfaceControl.Builder::class.java, "build", 0), null, emptyList(), surface)))
            assertTrue(construction.constructed().isEmpty())
        }
    }
}
