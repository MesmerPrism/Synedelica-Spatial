// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.core

/** Pure mapping from a BOOTTIME hardware frame anchor to a borrowed chunk's first sample. */
object AudioCaptureTime {
    /**
     * Returns a positive BOOTTIME stamp only when the anchor itself is not in
     * the future and the derived first sample is no older than 250ms. Frame
     * distance is converted to nanoseconds with checked integer arithmetic;
     * overflow is rejected rather than wrapped, and division truncates the
     * sub-nanosecond remainder. The caller supplies one consistent clock domain.
     */
    fun firstSample(
        firstFrame: Long,
        anchorFrame: Long,
        anchorNanos: Long,
        sampleRate: Int,
        nowNanos: Long,
    ): Long? {
        if (firstFrame < 0 || anchorFrame < firstFrame || sampleRate <= 0 ||
            nowNanos < 0 || anchorNanos <= 0 || anchorNanos > nowNanos) return null
        val capturedAt = try {
            val frames = Math.subtractExact(anchorFrame, firstFrame)
            val elapsedNanos = Math.multiplyExact(frames, 1_000_000_000L) / sampleRate
            Math.subtractExact(anchorNanos, elapsedNanos)
        } catch (_: ArithmeticException) {
            return null
        }
        if (capturedAt <= 0 || capturedAt > nowNanos || nowNanos - capturedAt > 250_000_000L) return null
        return capturedAt
    }
}
