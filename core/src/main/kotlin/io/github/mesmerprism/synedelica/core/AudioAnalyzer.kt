// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Synchronous fixed-window mono PCM analysis. [accept] borrows its array only for
 * the call; the instance keeps scalar accumulators, never samples or recordings.
 * The host serializes calls and supplies BOOTTIME timestamps and generations.
 *
 * A 256-sample default is an authored low-latency aggregation choice, not a
 * frequency analysis claim. Peak and RMS divide signed PCM by 32768. Each full
 * window emits once, with the timestamp of its final contributing call and a
 * generation-local sequence starting at one. Chunk fragmentation does not alter
 * sample aggregates. Onset count saturates at 255 and counts windows whose RMS
 * is at least 0.1 and rises by at least 0.05 from the preceding window.
 */
class AudioAnalyzer(private val windowSize: Int = 256) {
    init { require(windowSize > 0) { "windowSize must be positive" } }

    private var generation: Long? = null
    private var lastTimestamp = -1L
    private var sequence = 0L
    private var partialCount = 0
    private var squareSum = 0.0
    private var peak = 0f
    private var previousRms = 0f
    private var onsets = 0L

    /**
     * Reads only indices `0 until count`; [count] must be within the array.
     * Zero samples emit nothing. Generation advance drops partial windows and
     * restarts sequence/onsets. Regressed/negative metadata or a zero timestamp emits one invalid
     * zero-valued observation and drops transients, keeping accepted high-water
     * marks. Clock freshness is the consuming style/adapter's responsibility.
     */
    fun accept(samples: ShortArray, count: Int, generation: Long, capturedAtNanos: Long): List<AudioSnapshot> {
        require(count in 0..samples.size) { "count must be within samples" }
        val current = this.generation
        if (generation < 0 || capturedAtNanos <= 0 || (current != null && generation < current) ||
            (generation == current && capturedAtNanos < lastTimestamp)) {
            clearTransients()
            return listOf(AudioSnapshot(generation, sequence, capturedAtNanos,
                AudioClockDomain.BootTime, false, 0f, 0f, 0))
        }
        if (current == null || generation > current) {
            reset()
            this.generation = generation
        }
        lastTimestamp = capturedAtNanos
        val completed = ArrayList<AudioSnapshot>()
        for (index in 0 until count) {
            val normalized = samples[index].toInt() / 32768.0
            squareSum += normalized * normalized
            peak = maxOf(peak, abs(normalized).toFloat())
            partialCount++
            if (partialCount == windowSize) {
                val rms = sqrt(squareSum / windowSize).toFloat().coerceIn(0f, 1f)
                if (rms >= 0.1f && rms - previousRms >= 0.05f) onsets = (onsets + 1).coerceAtMost(255)
                previousRms = rms
                sequence++
                completed.add(AudioSnapshot(generation, sequence, capturedAtNanos,
                    AudioClockDomain.BootTime, true, peak.coerceIn(0f, 1f), rms, onsets))
                partialCount = 0
                squareSum = 0.0
                peak = 0f
            }
        }
        return completed
    }

    fun reset() {
        generation = null
        lastTimestamp = -1
        sequence = 0
        clearTransients()
    }

    private fun clearTransients() {
        partialCount = 0
        squareSum = 0.0
        peak = 0f
        previousRms = 0f
        onsets = 0
    }
}
