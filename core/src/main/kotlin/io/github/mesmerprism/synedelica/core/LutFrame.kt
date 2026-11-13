// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.core

/** Owned RGB8 channels: blue outer, green middle, red inner; RGB per point. */
class LutFrame(
    val generation: Long,
    val sequence: Long,
    val capturedAtNanos: Long,
    val clockDomain: AudioClockDomain,
    rgbBytesBgrOrder: IntArray,
) {
    private val samples: IntArray

    init {
        require(rgbBytesBgrOrder.size == 16 * 16 * 16 * 3) { "A 16-cube requires 12288 channels" }
        require(rgbBytesBgrOrder.all { it in 0..255 }) { "Channels must be RGB8" }
        samples = rgbBytesBgrOrder.copyOf()
    }

    fun copySamples(): IntArray = samples.copyOf()

    companion object {
        fun identity(source: AudioSnapshot): LutFrame {
            val rgb = IntArray(16 * 16 * 16 * 3)
            var offset = 0
            for (blue in 0..15) for (green in 0..15) for (red in 0..15) {
                rgb[offset++] = red * 17
                rgb[offset++] = green * 17
                rgb[offset++] = blue * 17
            }
            return LutFrame(source.generation, source.sequence, source.capturedAtNanos, source.clockDomain, rgb)
        }
    }
}
