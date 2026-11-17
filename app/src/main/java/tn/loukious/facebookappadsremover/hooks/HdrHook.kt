package tn.loukious.facebookappadsremover.hooks

import android.content.res.Configuration
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Display
import android.view.Window
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Method
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
 * - a video decoder is asked to deliver SDR, so a video that really is HDR is
 *   tone-mapped by the decoder where the phone can do that (Android 12+).
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

    @Volatile private var installed = false

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
        count += hook(module, methods(MediaCodec::class.java, "configure", 4), Decoder)
        installed = count > 0
        if (installed) L.i(TAG, "HDR off: $count platform hooks")
        return installed
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
            args[0] = 0f // no preference: the window is not HDR anyway
            return chain.proceed(args)
        }
    }

    private object Decoder : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (enabled()) runCatching {
                val format = chain.args.getOrNull(0) as? MediaFormat
                val flags = chain.args.filterIsInstance<Int>().firstOrNull() ?: 0
                val video = format?.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                if (video && flags and MediaCodec.CONFIGURE_FLAG_ENCODE == 0) {
                    format!!.setInteger(KEY_TRANSFER_REQUEST, TRANSFER_SDR)
                }
            }
            return chain.proceed()
        }
    }
}
