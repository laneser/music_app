package com.cellocoach.core

import kotlin.math.sqrt

/**
 * Monophonic pitch detector using the **Normalized Square Difference Function**
 * (NSDF), i.e. the McLeod Pitch Method (MPM). This is a from-scratch port of the
 * algorithm the original browser used via the **Pitchy** library
 * (`pitchy` / `pitchfinder` McLeod estimator), moved fully in-process so the
 * Android app no longer needs the Web Audio API + HTTPS/secure-context plumbing
 * described in CONTRACTS.md.
 *
 * In the original architecture the browser ran Pitchy and POSTed `{hz, rms}` to
 * Flask; here [detect] does the same work directly on a [FloatArray] of mono PCM
 * samples in `[-1, 1]`.
 *
 * ## How it works
 * 1. Reject silence early via RMS energy (a noise floor) — returns null.
 * 2. Compute the NSDF `n'(tau)` for all candidate lags. NSDF normalizes the raw
 *    autocorrelation by the signal energy in the two overlapping windows, giving
 *    a clarity value in roughly `[-1, 1]` that is far more robust to volume than
 *    plain autocorrelation.
 * 3. Pick **key maxima** (the first local maximum after each positive zero
 *    crossing) and choose the highest one above a fraction of the global max
 *    (the standard MPM "k" heuristic) — this avoids octave errors where the very
 *    first peak (lag 0) always dominates.
 * 4. Parabolic interpolation around the chosen peak gives a sub-sample lag for
 *    sub-Hz frequency accuracy.
 * 5. `hz = sampleRate / lag`; reject anything outside `[fmin, fmax]` or below the
 *    [clarityThreshold] (returns null — treated as noise by the rest of the app).
 *
 * Pure computation: no Android dependencies, fully JVM unit-testable.
 *
 * @param sampleRate input sample rate in Hz.
 * @param fmin lowest accepted fundamental (CELLO_FMIN in the Python source).
 * @param fmax highest accepted fundamental (CELLO_FMAX in the Python source).
 * @param clarityThreshold minimum NSDF clarity of the chosen peak; below this the
 *   frame is considered to have no clear pitch.
 */
class PitchDetector(
    val sampleRate: Int = 44100,
    val fmin: Double = 60.0,        // CELLO_FMIN
    val fmax: Double = 1100.0,      // CELLO_FMAX
    val clarityThreshold: Double = 0.9,
) {

    /** Anything quieter than this RMS is treated as silence (no pitch). */
    private val rmsSilenceFloor = 0.01

    /**
     * MPM peak-picking constant: a key maximum counts as the winner only if it is
     * at least this fraction of the largest key maximum. Picking the *first* such
     * peak (lowest lag → correct octave) is what suppresses octave errors.
     */
    private val peakPickRatio = 0.8

    /**
     * Detect the fundamental frequency of [samples] (mono PCM, ideally in
     * `[-1, 1]`).
     *
     * @return frequency in Hz, or `null` for silence, noise, low clarity, or a
     *   result outside `[fmin, fmax]`.
     */
    fun detect(samples: FloatArray): Float? {
        val n = samples.size
        if (n < 2) return null

        // 1. Silence gate via RMS energy.
        var sumSq = 0.0
        for (s in samples) sumSq += s.toDouble() * s
        val rms = sqrt(sumSq / n)
        if (rms < rmsSilenceFloor) return null

        // Search only the lag range that can yield a frequency in [fmin, fmax].
        // hz = sampleRate / lag  =>  lag = sampleRate / hz.
        val minLag = (sampleRate / fmax).toInt().coerceAtLeast(1)
        val maxLag = (sampleRate / fmin).toInt().coerceAtMost(n - 1)
        if (maxLag <= minLag) return null

        // 2. NSDF over the candidate lag range.
        //    n'(tau) = 2 * r(tau) / m(tau)
        //    r(tau)  = sum_{i} x[i] * x[i+tau]
        //    m(tau)  = sum_{i} (x[i]^2 + x[i+tau]^2)
        val nsdf = DoubleArray(maxLag + 1)
        for (tau in minLag..maxLag) {
            var acf = 0.0   // r(tau)
            var div = 0.0   // m(tau)
            val limit = n - tau
            for (i in 0 until limit) {
                val a = samples[i].toDouble()
                val b = samples[i + tau].toDouble()
                acf += a * b
                div += a * a + b * b
            }
            nsdf[tau] = if (div > 0.0) 2.0 * acf / div else 0.0
        }

        // 3. Key maxima: first local max after each positive-going zero crossing.
        val maxima = collectKeyMaxima(nsdf, minLag, maxLag)
        if (maxima.isEmpty()) return null

        val globalMax = maxima.maxOf { it.value }
        if (globalMax <= 0.0) return null

        // Choose the first key maximum that clears peakPickRatio * globalMax.
        val threshold = peakPickRatio * globalMax
        val chosen = maxima.firstOrNull { it.value >= threshold } ?: return null

        // 4. Parabolic interpolation for a sub-sample lag and refined clarity.
        val (refinedLag, clarity) = parabolicRefine(nsdf, chosen.lag)
        if (clarity < clarityThreshold) return null
        if (refinedLag <= 0.0) return null

        // 5. Convert lag -> Hz and range-check.
        val hz = sampleRate / refinedLag
        if (hz < fmin || hz > fmax) return null
        return hz.toFloat()
    }

    /** A local maximum of the NSDF: its integer [lag] and NSDF [value]. */
    private data class KeyMaximum(val lag: Int, val value: Double)

    /**
     * Collect the **key maxima** of the NSDF within `[minLag, maxLag]`: after each
     * upward zero crossing we take the single highest sample until the next
     * downward zero crossing. This is the McLeod peak set used to avoid the
     * trivial lag-0 peak and reduce octave errors.
     */
    private fun collectKeyMaxima(nsdf: DoubleArray, minLag: Int, maxLag: Int): List<KeyMaximum> {
        val maxima = ArrayList<KeyMaximum>()
        var tau = minLag

        // Advance past any initial positive region so we start at a zero crossing.
        while (tau <= maxLag && nsdf[tau] > 0.0) tau++

        while (tau <= maxLag) {
            // Skip the negative region until the next positive-going crossing.
            while (tau <= maxLag && nsdf[tau] <= 0.0) tau++
            if (tau > maxLag) break

            // Track the highest sample until we dip back to/below zero.
            var bestLag = tau
            var bestVal = nsdf[tau]
            while (tau <= maxLag && nsdf[tau] > 0.0) {
                if (nsdf[tau] > bestVal) {
                    bestVal = nsdf[tau]
                    bestLag = tau
                }
                tau++
            }
            maxima.add(KeyMaximum(bestLag, bestVal))
        }
        return maxima
    }

    /**
     * Refine an integer-lag peak with parabolic interpolation over the three
     * samples `nsdf[lag-1], nsdf[lag], nsdf[lag+1]`.
     *
     * @return the interpolated (lag, clarity) pair. Falls back to the integer lag
     *   and value at the array edges.
     */
    private fun parabolicRefine(nsdf: DoubleArray, lag: Int): Pair<Double, Double> {
        if (lag <= 0 || lag >= nsdf.size - 1) return lag.toDouble() to nsdf[lag]
        val y0 = nsdf[lag - 1]
        val y1 = nsdf[lag]
        val y2 = nsdf[lag + 1]
        val denom = y0 - 2.0 * y1 + y2
        if (denom == 0.0) return lag.toDouble() to y1
        val delta = 0.5 * (y0 - y2) / denom
        val refinedLag = lag + delta
        // Vertex value of the fitted parabola = refined clarity estimate.
        val clarity = y1 - 0.25 * (y0 - y2) * delta
        return refinedLag to clarity
    }
}
