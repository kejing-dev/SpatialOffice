package io.embodit.spatialoffice.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialAudioEffectPlayer
import io.embodit.spatialoffice.platform.TAG

/**
 * SpatialUI 6.2.8 plays its button click through `android.view.SpaceView.playSoundEffect(View, int)` on a background
 * coroutine. System images older than that method (the PICO 6.1.0 emulator image of 2026-09-01 on kj-home-pc, and any
 * headset firmware of that age) throw NoSuchMethodError there and the whole app dies on the first real button press
 * (v0.3.0 self-test, logcat -b crash 2026-11-21 06:53:35). The SDK player cannot be wrapped (the call fails later, on
 * its own coroutine), so: where the method exists the SDK's own player is used unchanged; where it does not, buttons
 * stay silent instead of crashing. Logged once as `SOUND system click …`.
 */
object SafeSound {
    val supported: Boolean by lazy {
        val ok = runCatching {
            Class.forName("android.view.SpaceView").getMethod("playSoundEffect", android.view.View::class.java, Int::class.javaPrimitiveType)
        }.isSuccess
        Log.i(TAG, "SOUND system click sound ${if (ok) "available" else "not available on this system image: buttons stay silent"}")
        ok
    }

    val silent = object : SpatialAudioEffectPlayer {
        override fun playSystem(effect: com.pico.spatial.ui.platform.SpatialSoundEffect) {}
    }
}

/** Gives [content] the SDK's click-sound player, or a silent one on a system image that cannot play it ([SafeSound]). */
@Composable
fun SafeClickSound(content: @Composable () -> Unit) {
    if (SafeSound.supported) { content(); return }
    val silent = remember { SafeSound.silent }
    CompositionLocalProvider(LocalAudioEffectPlayer provides silent) { content() }
}
