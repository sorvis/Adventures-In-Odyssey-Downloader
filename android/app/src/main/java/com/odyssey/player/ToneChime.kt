package com.odyssey.player

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.odyssey.debug.DebugLogger
import javax.inject.Inject

/**
 * The hand-off chime, as a synthesized tone.
 *
 * A generated tone rather than a bundled sound file: it adds no asset,
 * no licensing question, and routes through STREAM_MUSIC so it comes
 * out of whatever the episode was playing through — headphones, a car
 * head unit — instead of the phone speaker. The tradeoff is that it
 * sounds like a device beep rather than a designed cue; swapping in a
 * res/raw sound later means replacing this [Chime] binding and nothing
 * else.
 *
 * Volume is held well below full: the chime lands in the silence after
 * the player pauses, so it does not need to compete with anything, and
 * at full scale it is startling on headphones.
 */
class ToneChime @Inject constructor() : Chime {

    override fun play() {
        // ToneGenerator's constructor throws RuntimeException when the
        // audio resource can't be acquired (another app holding it, or
        // an emulator with no audio HAL). A missing chime must never
        // take down the episode hand-off, so nothing here may throw.
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, DURATION_MS)
            // release() mid-tone truncates it, so hold the generator
            // until the tone has finished sounding.
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } },
                (DURATION_MS + RELEASE_GRACE_MS).toLong(),
            )
        }.onFailure {
            DebugLogger.e("ToneChime", "could not sound the up-next chime", it)
        }
    }

    private companion object {
        /** Percent of stream volume; ToneGenerator's scale is 0..100. */
        const val VOLUME = 55
        const val DURATION_MS = 300
        const val RELEASE_GRACE_MS = 150
    }
}
