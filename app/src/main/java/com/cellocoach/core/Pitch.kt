package com.cellocoach.core

/**
 * One pitch reading. [hz] is null when no clear pitch was detected (silence /
 * noise). [rms] is the frame loudness, used by the tuning UI to know the
 * student is actually bowing.
 *
 * This is the single value that flows from the audio layer into the practice
 * engine — equivalent to the `{hz, rms}` payload the browser POSTed to the
 * Flask server in the original project, but in-process.
 */
data class PitchFrame(
    val hz: Float?,
    val rms: Float,
)

/**
 * Source of [PitchFrame]s. The real implementation wraps `AudioRecord` +
 * autocorrelation; tests inject a fake that replays a scripted sequence.
 *
 * Keeping mic capture behind this interface is what lets the whole practice
 * flow be exercised in Robolectric/JVM tests with **no real microphone** — the
 * one piece the task flagged as needing a mock.
 */
interface PitchSource {
    /** Begin producing frames. [onFrame] is invoked once per detector tick. */
    fun start(onFrame: (PitchFrame) -> Unit)

    /** Stop producing frames and release any audio resources. */
    fun stop()
}

/** Injectable monotonic clock (nanoseconds). Real code uses System.nanoTime; */
/** tests advance it deterministically so the time-based follower is testable. */
fun interface Clock {
    fun nowNanos(): Long
}

val SystemClock = Clock { System.nanoTime() }
