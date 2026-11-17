package tn.loukious.facebookappadsremover.hooks

import android.content.res.Configuration
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Display
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.lang.reflect.Modifier

/**
 * Keeps Facebook out of HDR.
 *
 * Facebook shows HDR photos and videos in HDR and also turns ordinary video
 * into HDR ("inverse tone mapping"). Either way the screen switches to its
 * HDR mode, which on many phones raises the brightness and drops the
 * eye-comfort filter. Facebook decides all of it from what Android says about
 * the screen, so nothing of Facebook's own is hooked here, only the platform:
 *
 * - the screen reports no HDR ([Display.isHdr], the HDR types of
 *   [Display.HdrCapabilities] and [Display.Mode], the HDR/SDR ratio,
 *   [Configuration.isScreenHdr]);
 * - a window is not put into the HDR colour mode and gets no HDR headroom,
 *   whether that is asked of the window or of its layout params;
 * - a video decoder is asked to deliver SDR and its accepted input format is checked;
 * - independent video surfaces are capped at SDR headroom where the platform
 *   supports it, including when the decoder cannot tone-map the HDR stream.
 *
 * Older platforms without either facility retain playback and report the limitation.
 * HDR color metadata is preserved: labelling an HDR buffer as SDR is not tone mapping.
 *
 * Methods a phone's Android version does not have are skipped.
 */
object HdrHook {
    private const val TAG = "FBAR.Hdr"
    /** ActivityInfo.COLOR_MODE_HDR. */
    private const val COLOR_MODE_HDR = 2
    /** MediaFormat.KEY_COLOR_TRANSFER_REQUEST and COLOR_TRANSFER_SDR_VIDEO (API 31). */
    private const val KEY_TRANSFER_REQUEST = "color-transfer-request"
    private const val TRANSFER_SDR = 3

    private const val SDR_HEADROOM = 1f // 0f means automatic HDR, not SDR.
    private const val TRANSACTION = "android.view.SurfaceControl\$Transaction"
    private const val SURFACE_BUILDER = "android.view.SurfaceControl\$Builder"

    @Volatile private var installed = false
    @Volatile private var surfaceFallbackInstalled = false
    private var surfaceCap: Method? = null
    private var transactionCtor: Constructor<*>? = null
    private var transactionApply: Method? = null
    private var transactionClose: Method? = null
    private val reported = Collections.synchronizedSet(HashSet<String>())

    private fun enabled() = Settings.getBoolean(Settings.APPEARANCE_NO_HDR, false)

    @Synchronized
    fun install(module: XposedInterface): Boolean {
        if (installed) return true
        var count = 0
        count += answer(module, Display::class.java, "isHdr", false)
        count += answer(module, Display::class.java, "isHdrSdrRatioAvailable", false)
        count += answer(module, Display::class.java, "getHdrSdrRatio", 1f)
        count += answer(module, Display.HdrCapabilities::class.java, "getSupportedHdrTypes", IntArray(0))
        count += answer(module, Display.Mode::class.java, "getSupportedHdrTypes", IntArray(0))
        count += answer(module, Configuration::class.java, "isScreenHdr", false)
        // A window gets these through Window or straight through its layout params.
        for (cls in listOf(Window::class.java, WindowManager.LayoutParams::class.java)) {
            count += hook(module, methods(cls, "setColorMode", 1), ColorMode)
            count += hook(module, methods(cls, "setDesiredHdrHeadroom", 1), Headroom)
        }
        count += installSurfaceCaps(module)
        count += hook(module, methods(MediaCodec::class.java, "configure", 4), Decoder)
        installed = count > 0
        if (installed) L.i(TAG, "HDR off: $count platform hooks; surface fallback=$surfaceFallbackInstalled")
        return installed
    }

    private fun installSurfaceCaps(module: XposedInterface): Int {
        var count = 0
        val viewSetter = methods(SurfaceView::class.java, "setDesiredHdrHeadroom", 1)
            .singleOrNull { it.parameterTypes[0] == Float::class.javaPrimitiveType }
        if (viewSetter != null) {
            count += hook(module, listOf(viewSetter), Headroom)
            // Apply the default even if Facebook never calls the HDR setter. Attaching
            // occurs after subclass construction and before the first surface layout.
            count += hook(module, methods(SurfaceView::class.java, "onAttachedToWindow", 0), object : Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (enabled()) runCatching { viewSetter.invoke(chain.thisObject, SDR_HEADROOM) }
                        .onFailure { L.w(TAG, "surface HDR cap failed", it) }
                    return result
                }
            })
        }
        val transaction = runCatching { Class.forName(TRANSACTION) }.getOrNull() ?: return count
        val cap = methods(transaction, "setDesiredHdrHeadroom", 2)
            .singleOrNull { it.parameterTypes[1] == Float::class.javaPrimitiveType }
        if (cap != null) {
            count += hook(module, listOf(cap), Headroom)
            runCatching {
                surfaceCap = cap
                transactionCtor = transaction.getDeclaredConstructor().apply { isAccessible = true }
                transactionApply = transaction.getDeclaredMethod("apply")
                transactionClose = transaction.getDeclaredMethod("close")
                val builder = Class.forName(SURFACE_BUILDER)
                val builds = methods(builder, "build", 0)
                val added = hook(module, builds, SurfaceBuilt)
                surfaceFallbackInstalled = added > 0
                count += added
            }.onFailure { L.w(TAG, "surface HDR cap unavailable", it) }
        }
        // This setter overrides desired headroom. Preserve the buffer's real ratio,
        // changing only its requested display brightness to avoid incorrect colors.
        count += hook(module, methods(transaction, "setExtendedRangeBrightness", 3), ExtendedBrightness)
        return count
    }

    private fun capSurface(surface: Any) {
        val ctor = transactionCtor ?: return
        runCatching {
            val transaction = ctor.newInstance()
            try {
                surfaceCap!!.invoke(transaction, surface, SDR_HEADROOM)
                transactionApply!!.invoke(transaction)
            } finally {
                transactionClose!!.invoke(transaction)
            }
        }.onFailure { L.w(TAG, "surface HDR cap failed", it) }
    }

    private object SurfaceBuilt : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (enabled() && result != null) capSurface(result)
            return result
        }
    }

    private fun methods(cls: Class<*>, name: String, params: Int): List<Method> =
        cls.declaredMethods.filter {
            it.name == name && it.parameterTypes.size == params && !Modifier.isAbstract(it.modifiers)
        }

    private fun hook(module: XposedInterface, methods: List<Method>, hooker: Hooker): Int {
        var count = 0
        for (m in methods) {
            runCatching {
                m.isAccessible = true
                module.hook(m).intercept(hooker)
                count++
            }.onFailure { L.w(TAG, "hook ${m.declaringClass.simpleName}.${m.name} failed", it) }
        }
        return count
    }

    /** Makes the no-argument method [name] of [cls] return [value] while the switch is on. */
    private fun answer(module: XposedInterface, cls: Class<*>, name: String, value: Any): Int =
        hook(module, methods(cls, name, 0), object : Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? =
                if (enabled()) value else chain.proceed()
        })

    private object ColorMode : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled() || chain.args.getOrNull(0) != COLOR_MODE_HDR) return chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = 0 // ActivityInfo.COLOR_MODE_DEFAULT
            return chain.proceed(args)
        }
    }

    private object Headroom : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val args = chain.args.toTypedArray()
            val index = args.indexOfFirst { it is Float }
            if (index < 0) return chain.proceed()
            args[index] = SDR_HEADROOM
            return chain.proceed(args)
        }
    }

    private object ExtendedBrightness : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val args = chain.args.toTypedArray()
            args[2] = SDR_HEADROOM
            return chain.proceed(args)
        }
    }

    private object Decoder : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val format = chain.args.getOrNull(0) as? MediaFormat ?: return chain.proceed()
            val flags = chain.args.filterIsInstance<Int>().firstOrNull() ?: 0
            val requested = runCatching {
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true ||
                    flags and MediaCodec.CONFIGURE_FLAG_ENCODE != 0
                ) false else {
                    format.setInteger(KEY_TRANSFER_REQUEST, TRANSFER_SDR)
                    true
                }
            }.getOrDefault(false)
            // Configure first: only its resulting input format acknowledges support.
            // Codec failures propagate unchanged; do not inspect an unconfigured codec.
            val result = chain.proceed()
            if (requested) {
                val transfer = runCatching {
                    (chain.thisObject as? MediaCodec)?.inputFormat?.let { accepted ->
                        if (accepted.containsKey(KEY_TRANSFER_REQUEST)) accepted.getInteger(KEY_TRANSFER_REQUEST) else 0
                    }
                }.getOrNull()
                val status = if (transfer == TRANSFER_SDR) "decoder SDR request accepted" else {
                    val state = if (transfer == null) "unverified" else "unsupported"
                    val fallback = if (surfaceFallbackInstalled) "SDR surface headroom fallback enabled"
                        else "surface headroom unavailable; HDR may remain active"
                    "decoder SDR request $state; $fallback"
                }
                if (reported.add(status)) {
                    if (transfer == TRANSFER_SDR) L.i(TAG, status) else L.w(TAG, status)
                }
            }
            return result
        }
    }
}
