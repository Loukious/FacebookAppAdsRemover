package tn.loukious.facebookappadsremover.hooks

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Plays every video at the speed picked in the module settings.
 *
 * Facebook's own speed menu changes the speed of the video on screen only;
 * the next video starts at 1x again. This applies the chosen speed each time
 * the player starts a video.
 *
 * Nothing is looked up by an obfuscated name. The player class is the one
 * [VideoResumeHook] already finds, its start event is the analytics method
 * `maybeTrackVideoStart` (a real name that survives obfuscation), and the
 * speed setter is the only `void (float)` method the player declares, which
 * is what the speed menu itself calls. If that shape is not unique on some
 * Facebook version the hook stays off and says so in the log.
 */
object VideoSpeedHook {
    private const val TAG = "FBAR.VideoSpeed"

    /** Chosen speed, "off" or empty = Facebook's own. */
    private val SPEEDS = mapOf("1.25" to 1.25f, "1.5" to 1.5f, "1.75" to 1.75f, "2" to 2f, "2.5" to 2.5f, "3" to 3f)

    @Volatile private var setter: Method? = null

    /** The configured speed, or null while the setting is off or unreadable. */
    fun configuredSpeed(): Float? = SPEEDS[Settings.getString(Settings.VIDEO_SPEED, "off")]

    /**
     * Hooks [start] (the player's `maybeTrackVideoStart`) of [playerClass].
     * Returns false when the speed setter cannot be identified.
     */
    fun install(module: XposedInterface, playerClass: Class<*>, start: Method?): Boolean {
        if (start == null) return false
        val candidates = playerClass.declaredMethods.filter {
            it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Float.TYPE &&
                it.returnType == java.lang.Void.TYPE &&
                !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers)
        }
        val method = candidates.singleOrNull() ?: run {
            L.w(TAG, "speed setter not unique on ${playerClass.name}: " +
                candidates.joinToString { it.name }.ifEmpty { "none" })
            return false
        }
        method.isAccessible = true
        setter = method
        return runCatching {
            module.hook(start).intercept(StartHooker)
            L.i(TAG, "video speed hook installed on ${playerClass.name}.${method.name}")
            true
        }.getOrElse {
            L.w(TAG, "video speed hook failed", it)
            false
        }
    }

    private object StartHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val speed = configuredSpeed() ?: return result
            val player = chain.thisObject ?: return result
            runCatching { setter?.invoke(player, speed) }
                .onFailure { L.w(TAG, "setting video speed failed", it) }
            return result
        }
    }
}
