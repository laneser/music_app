package com.cellocoach.audio

import com.cellocoach.core.PitchFrame
import com.cellocoach.core.PitchSource

/**
 * Scriptable [PitchSource] for tests, Compose previews, and offline demos.
 *
 * This is the "mock microphone" the contract calls for: instead of capturing
 * real audio it replays a deterministic sequence of [PitchFrame]s, so the whole
 * practice/calibration flow can be driven from a Robolectric Compose test with
 * no emulator audio hardware.
 *
 * Two ways to drive it:
 *  - **Scripted**: pass [frames] to the constructor and call [start]; every frame
 *    is delivered once, in order, synchronously on the calling thread.
 *  - **Manual / step**: call [emit] yourself after [start] to push one frame at a
 *    time. This is what UI tests use — emit a frame, advance the test clock /
 *    recompose, assert, repeat — giving full control over timing.
 *
 * Delivery is synchronous (no background thread) precisely so tests stay
 * deterministic: when [emit] returns, the listener has already run.
 */
class FakePitchSource(
    private val frames: List<PitchFrame> = emptyList(),
) : PitchSource {

    private var listener: ((PitchFrame) -> Unit)? = null

    /** True between [start] and [stop]; [emit] is a no-op when stopped. */
    var isRunning: Boolean = false
        private set

    /**
     * Begin emitting. Registers [onFrame], then synchronously plays back any
     * frames supplied to the constructor.
     */
    override fun start(onFrame: (PitchFrame) -> Unit) {
        listener = onFrame
        isRunning = true
        for (f in frames) onFrame(f)
    }

    /**
     * Push a single frame to the registered listener. Used by tests to drive the
     * engine one tick at a time. Ignored if [start] hasn't been called or after
     * [stop].
     */
    fun emit(frame: PitchFrame) {
        if (isRunning) listener?.invoke(frame)
    }

    /** Convenience: emit a voiced frame at [hz] (default loudness above silence). */
    fun emitHz(hz: Float?, rms: Float = 0.2f) = emit(PitchFrame(hz, rms))

    override fun stop() {
        isRunning = false
        listener = null
    }
}
