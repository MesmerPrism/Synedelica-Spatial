// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.core

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

enum class PaletteFamily { AuthoredRgbWave }

data class StyleSettings(
    val family: PaletteFamily = PaletteFamily.AuthoredRgbWave,
    val strength: Float = 1f,
    val quietStrength: Float = 0.15f,
    val clockSpeed: Float = 0.1f,
)

interface StyleEngine {
    fun advance(source: AudioSnapshot, nowNanos: Long, deltaSeconds: Float, settings: StyleSettings): LutFrame
    fun reset()
}

/**
 * A newly authored RGB sine deformation, with no recovered palettes or profiles.
 * Calls are serialized by the host. Fresh silence permits quiet animation;
 * every validation failure returns identity and clears animation phase while
 * retaining accepted ordering high-water marks. Same sequence must retain its
 * timestamp. A newer sequence may share a timestamp with preceding windows.
 */
class QuietStyle : StyleEngine {
    private var generation: Long? = null
    private var sequence = -1L
    private var timestamp = -1L
    private var phase = 0.0
    private val bands = BandState()

    override fun advance(source: AudioSnapshot, nowNanos: Long, deltaSeconds: Float, settings: StyleSettings): LutFrame {
        val current = generation
        if (source.generation < 0 || (current != null && source.generation < current)) return failClosed(source)
        if (current == null || source.generation > current) {
            reset()
            generation = source.generation
        }
        if (!source.valid || source.sequence < 0 || source.capturedAtNanos <= 0 ||
            source.clockDomain != AudioClockDomain.BootTime || nowNanos < source.capturedAtNanos ||
            nowNanos - source.capturedAtNanos > 250_000_000L ||
            !source.amplitude.isFinite() || source.amplitude !in 0f..1f ||
            !source.rms.isFinite() || source.rms !in 0f..1f || source.onsetCount !in 0L..255L ||
            source.sequence < sequence || source.capturedAtNanos < timestamp ||
            (source.sequence == sequence && source.capturedAtNanos != timestamp) ||
            !settings.strength.isFinite() || settings.strength !in 0f..1f ||
            !settings.quietStrength.isFinite() || settings.quietStrength !in 0f..1f ||
            !settings.clockSpeed.isFinite() || settings.clockSpeed !in 0f..10f ||
            !deltaSeconds.isFinite() || deltaSeconds < 0f) return failClosed(source)

        sequence = source.sequence
        timestamp = source.capturedAtNanos
        // A long scheduling pause cannot create an unbounded phase jump.
        val boundedDelta = deltaSeconds.coerceAtMost(1f)
        phase = (phase + boundedDelta * settings.clockSpeed * 2.0 * PI) % (2.0 * PI)
        bands.advance(source.onsetCount, boundedDelta, settings.strength > 0f)
        if (settings.strength == 0f) return LutFrame.identity(source)

        val audio = maxOf(source.amplitude, source.rms).toDouble()
        val gain = settings.strength * (settings.quietStrength + (1.0 - settings.quietStrength) * audio)
        val rgb = IntArray(16 * 16 * 16 * 3)
        var offset = 0
        for (blue in 0..15) for (green in 0..15) for (red in 0..15) {
            val r = red / 15.0
            val g = green / 15.0
            val b = blue / 15.0
            // Envelope fixes cube endpoints while smoothly deforming interior channels.
            rgb[offset++] = wave(r, g, phase, gain)
            rgb[offset++] = wave(g, b, phase + 2.0 * PI / 3.0, gain)
            rgb[offset++] = wave(b, r, phase + 4.0 * PI / 3.0, gain)
        }
        return LutFrame(source.generation, source.sequence, source.capturedAtNanos, source.clockDomain, rgb)
    }

    override fun reset() {
        generation = null
        sequence = -1
        timestamp = -1
        phase = 0.0
        bands.reset()
    }

    private fun failClosed(source: AudioSnapshot): LutFrame {
        phase = 0.0
        bands.clear()
        return LutFrame.identity(source)
    }

    private fun wave(channel: Double, neighbor: Double, angle: Double, gain: Double): Int =
        ((channel + gain * (4.0 * channel * (1.0 - channel)) *
            (0.18 * sin(angle + neighbor * PI) + 0.12 * bands.sample(channel))) * 255.0)
            .roundToInt().coerceIn(0, 255)
}
