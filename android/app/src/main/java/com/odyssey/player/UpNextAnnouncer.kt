package com.odyssey.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** How long the hand-off window stays open, in seconds. */
const val UP_NEXT_WINDOW_SECONDS = 5

/** Shown when a title is missing, so the prompt still reads as a sentence. */
const val UP_NEXT_FALLBACK_TITLE = "the next episode"

/** What the UI shows while the hand-off window is open. */
data class UpNextPrompt(
    val episodeId: Long,
    val title: String,
    val secondsRemaining: Int,
)

/**
 * The sound played at the hand-off.
 *
 * An interface for two reasons: tests stay silent, and the announcer
 * itself holds no Android types — which is what keeps it in the fast
 * bare-kotlinc test lane instead of the Robolectric one.
 */
interface Chime {
    fun play()
}

/**
 * The gap between two episodes of an album.
 *
 * Auto-advance used to be instantaneous, which made it easy to drift
 * three episodes past where you fell asleep. This opens a short window
 * instead: a chime fires, the UI gets an "Up next" prompt counting
 * down, and playback only resumes when the window elapses. Cancel
 * during the window and the next episode stays loaded but paused — so
 * nothing is lost, it just stops rolling.
 *
 * The announcer does not touch the player itself; it calls back when
 * the window elapses. That keeps the MediaController (main-thread
 * affine, un-fakeable in a unit test) on the PlayerController side of
 * the line.
 */
@Singleton
class UpNextAnnouncer @Inject constructor(private val chime: Chime) {

    private val _prompt = MutableStateFlow<UpNextPrompt?>(null)
    val prompt: StateFlow<UpNextPrompt?> = _prompt.asStateFlow()

    private var countdown: Job? = null

    /**
     * Chimes, opens the window, and invokes [onProceed] once if it runs
     * out. Supersedes any window already open — rapid skipping must not
     * leave two countdowns racing to call [onProceed].
     *
     * A [windowSeconds] of zero proceeds immediately without ever
     * publishing a prompt, which is the natural "no window" degenerate
     * case rather than a special branch.
     */
    fun announce(
        scope: CoroutineScope,
        episodeId: Long,
        title: String,
        windowSeconds: Int = UP_NEXT_WINDOW_SECONDS,
        onProceed: suspend () -> Unit,
    ) {
        countdown?.cancel()
        chime.play()
        countdown = scope.launch {
            for (remaining in windowSeconds downTo 1) {
                _prompt.value = UpNextPrompt(episodeId, title, remaining)
                delay(1_000L)
            }
            // Clear before proceeding so the banner is gone by the time
            // audio comes back, not a frame after it.
            _prompt.value = null
            onProceed()
        }
    }

    /** Closes the window without proceeding. The next episode stays cued. */
    fun cancel() {
        countdown?.cancel()
        countdown = null
        _prompt.value = null
    }
}

/**
 * Title for the prompt. Media3 hands back a CharSequence that is null
 * for streams whose metadata never resolved, and we have seen blank
 * ones from NAS rows that were mirrored without a title.
 */
fun upNextTitle(raw: CharSequence?): String {
    val text = raw?.toString()?.trim()
    return if (text.isNullOrEmpty()) UP_NEXT_FALLBACK_TITLE else text
}
