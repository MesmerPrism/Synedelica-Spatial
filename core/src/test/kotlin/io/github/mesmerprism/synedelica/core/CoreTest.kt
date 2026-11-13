// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun source(generation: Long = 1, sequence: Long = 1, time: Long = 1_000_000_000,
        valid: Boolean = true, amplitude: Float = 0.7f, rms: Float = 0.4f,
        onset: Long = 1) = AudioSnapshot(generation, sequence, time, AudioClockDomain.BootTime,
            valid, amplitude, rms, onset)

    private fun identity(frame: LutFrame) = assertArrayEquals(
        LutFrame.identity(source(frame.generation, frame.sequence, frame.capturedAtNanos)).copySamples(),
        frame.copySamples())

    @Test fun completedWindowsIgnoreReadFragmentationAndCountBounds() {
        val pcm = shortArrayOf(0, 32767, -32768, 1000, 4000, -4000, 0, 0, 222, 333, 444, 555)
        val whole = AudioAnalyzer(4).accept(pcm, pcm.size, 2, 100)
        val fragmented = AudioAnalyzer(4)
        val chunks = listOf(pcm.copyOfRange(0, 1), pcm.copyOfRange(1, 6), pcm.copyOfRange(6, 12))
        val parts = chunks.flatMap { fragmented.accept(it, it.size, 2, 100) }
        assertEquals(whole, parts)
        assertEquals(3, whole.size)
        assertEquals(listOf(1L, 2L, 3L), whole.map { it.sequence })
        val limited = AudioAnalyzer(4).accept(pcm, 4, 2, 100)
        assertEquals(whole.take(1), limited)
    }

    @Test fun shortMinimumNormalizesWithoutOverflow() {
        val snapshot = AudioAnalyzer(4).accept(ShortArray(4) { Short.MIN_VALUE }, 4, 1, 10).single()
        assertEquals(1f, snapshot.amplitude, 0f)
        assertEquals(1f, snapshot.rms, 0f)
        assertTrue(snapshot.valid)
    }

    @Test fun borrowedPcmIsNotRetainedAndPartialWindowUsesOnlyScalars() {
        val analyzer = AudioAnalyzer(4)
        val borrowed = shortArrayOf(16384, 16384)
        assertTrue(analyzer.accept(borrowed, 2, 1, 10).isEmpty())
        borrowed.fill(0)
        val output = analyzer.accept(shortArrayOf(0, 0), 2, 1, 20).single()
        assertEquals(0.5f, output.amplitude, 0f)
        assertEquals(kotlin.math.sqrt(0.125).toFloat(), output.rms, 0.000001f)
        assertEquals(20L, output.capturedAtNanos)
        assertFalse(AudioAnalyzer::class.java.declaredFields.any { it.type.isArray })
    }

    @Test fun analyzerGenerationAdvanceDropsPartialWindowAndResetRestartsSequence() {
        val analyzer = AudioAnalyzer(4)
        analyzer.accept(shortArrayOf(32767, 32767), 2, 1, 10)
        val next = analyzer.accept(ShortArray(4), 4, 2, 20).single()
        assertEquals(0f, next.amplitude, 0f)
        assertEquals(1L, next.sequence)
        assertEquals(0L, next.onsetCount)
        analyzer.reset()
        assertEquals(1L, analyzer.accept(ShortArray(4), 4, 1, 1).single().sequence)
    }

    @Test fun analyzerRejectsRegressionsAndBoundsOnsets() {
        val analyzer = AudioAnalyzer(4)
        analyzer.accept(ShortArray(4), 4, 2, 20)
        assertFalse(analyzer.accept(ShortArray(4), 4, 1, 21).single().valid)
        assertFalse(analyzer.accept(ShortArray(4), 4, 2, 19).single().valid)
        val bounded = AudioAnalyzer(4)
        repeat(400) { index ->
            bounded.accept(ShortArray(4), 4, 1, index * 2L + 1)
            val peak = bounded.accept(ShortArray(4) { 32767 }, 4, 1, index * 2L + 2).single()
            assertTrue(peak.amplitude in 0f..1f && peak.rms in 0f..1f && peak.onsetCount in 0L..255L)
        }
    }

    @Test fun identityEnumeratesEveryPointInBlueGreenRedOrderAndOwnsArrays() {
        val original = source()
        val frame = LutFrame.identity(original)
        val rgb = frame.copySamples()
        assertEquals(4096 * 3, rgb.size)
        for (b in 0..15) for (g in 0..15) for (r in 0..15) {
            val offset = ((b * 16 + g) * 16 + r) * 3
            assertEquals(r * 17, rgb[offset]); assertEquals(g * 17, rgb[offset + 1]); assertEquals(b * 17, rgb[offset + 2])
        }
        val copy = LutFrame(1, 1, 1, AudioClockDomain.BootTime, rgb)
        rgb.fill(0)
        assertEquals(255, copy.copySamples().last())
        val output = copy.copySamples(); output.fill(0)
        assertEquals(255, copy.copySamples().last())
        assertEquals(original.capturedAtNanos, frame.capturedAtNanos)
    }

    @Test fun strengthZeroIsExactIdentityAndChannelsAlwaysBounded() {
        val style = QuietStyle()
        identity(style.advance(source(), 1_000_000_000, 0.3f, StyleSettings(strength = 0f)))
        for (i in 1..50) {
            val frame = style.advance(source(sequence = i.toLong(), time = 1_000_000_000L + i),
                1_000_000_000L + i, 0.1f, StyleSettings())
            assertTrue(frame.copySamples().all { it in 0..255 })
        }
    }

    @Test fun freshSilentInputAllowsQuietAnimationButExpiryIsIdentity() {
        val style = QuietStyle()
        val silence = source(amplitude = 0f, rms = 0f, onset = 0)
        val quiet = style.advance(silence, silence.capturedAtNanos, 0.1f, StyleSettings())
        assertFalse(quiet.copySamples().contentEquals(LutFrame.identity(silence).copySamples()))
        val boundary = style.advance(silence, silence.capturedAtNanos + 250_000_000, 0.1f, StyleSettings())
        assertFalse(boundary.copySamples().contentEquals(LutFrame.identity(silence).copySamples()))
        identity(style.advance(silence, silence.capturedAtNanos + 250_000_001, 0.1f, StyleSettings()))
        identity(style.advance(silence, silence.capturedAtNanos - 1, 0.1f, StyleSettings()))
    }

    @Test fun invalidAndRegressedObservationsFailClosedWithoutPoisoningHighWaterMark() {
        val style = QuietStyle()
        style.advance(source(generation = 2, sequence = 4), 1_000_000_000, 0.1f, StyleSettings())
        for (bad in listOf(source(generation = 1), source(generation = 2, sequence = 3),
            source(generation = 2, sequence = 5, time = 999_999_999), source(generation = 2, sequence = 4, time = 1_000_000_001),
            source(generation = 2, sequence = 5, valid = false), source(generation = 2, sequence = 5, amplitude = Float.NaN))) {
            identity(style.advance(bad, 1_000_000_001, 0.1f, StyleSettings()))
        }
        identity(style.advance(source(generation = 2, sequence = 3), 1_000_000_000, 0.1f, StyleSettings()))
    }

    @Test fun newGenerationAndExplicitResetStartAnimationFromFreshState() {
        val used = QuietStyle()
        used.advance(source(), 1_000_000_000, 1f, StyleSettings())
        val newSource = source(generation = 2)
        assertArrayEquals(QuietStyle().advance(newSource, 1_000_000_000, 0.1f, StyleSettings()).copySamples(),
            used.advance(newSource, 1_000_000_000, 0.1f, StyleSettings()).copySamples())
        used.reset()
        assertArrayEquals(QuietStyle().advance(source(), 1_000_000_000, 0.1f, StyleSettings()).copySamples(),
            used.advance(source(), 1_000_000_000, 0.1f, StyleSettings()).copySamples())
    }

    @Test fun invalidSettingsAndInvalidBandsFailClosed() {
        for (settings in listOf(StyleSettings(strength = Float.NaN), StyleSettings(quietStrength = -1f),
            StyleSettings(clockSpeed = 11f), StyleSettings(clockSpeed = Float.POSITIVE_INFINITY))) {
            identity(QuietStyle().advance(source(), 1_000_000_000, 0.1f, settings))
        }
        for (bad in listOf(source(rms = -0.1f), source(amplitude = 1.1f), source(onset = -1))) {
            identity(QuietStyle().advance(bad, 1_000_000_000, 0.1f, StyleSettings()))
        }
    }

    @Test fun zeroCaptureTimestampFailsClosed() {
        identity(QuietStyle().advance(source(time = 0), 0, 0.1f, StyleSettings()))
    }

    @Test fun onsetBandsAreBoundedAndExpireWithoutReplayingRepeatedObservations() {
        val bands = BandState()
        bands.advance(255, 0f, true)
        assertEquals(8, bands.activeCount)
        assertTrue(bands.sample(0.5).isFinite())
        assertTrue(bands.sample(0.5) in -1.0..1.0)
        bands.advance(255, 0.8f, true)
        assertEquals(0, bands.activeCount)
        bands.advance(255, 0f, true)
        assertEquals(0, bands.activeCount)
    }

    @Test fun disabledStrengthConsumesOnsetsAndNeverReplaysThem() {
        val bands = BandState()
        bands.advance(2, 0f, true)
        assertEquals(2, bands.activeCount)
        bands.advance(3, 0.1f, false)
        assertEquals(0, bands.activeCount)
        bands.advance(3, 0f, true)
        assertEquals(0, bands.activeCount)

        val style = QuietStyle()
        style.advance(source(onset = 5), 1_000_000_000, 0f, StyleSettings(strength = 0f, clockSpeed = 0f))
        val resumed = style.advance(source(onset = 5), 1_000_000_000, 0f, StyleSettings(clockSpeed = 0f))
        val quietOnly = QuietStyle().advance(source(onset = 0), 1_000_000_000, 0f, StyleSettings(clockSpeed = 0f))
        assertArrayEquals(quietOnly.copySamples(), resumed.copySamples())
    }

    @Test fun onsetBandsAffectFrameAndClearOnGenerationResetAndStaleSource() {
        val settings = StyleSettings(clockSpeed = 0f)
        val withOnset = QuietStyle().advance(source(onset = 1), 1_000_000_000, 0f, settings)
        val withoutOnset = QuietStyle().advance(source(onset = 0), 1_000_000_000, 0f, settings)
        assertFalse(withOnset.copySamples().contentEquals(withoutOnset.copySamples()))
        val used = QuietStyle()
        used.advance(source(onset = 4), 1_000_000_000, 0f, settings)
        val next = source(generation = 2, onset = 0)
        assertArrayEquals(QuietStyle().advance(next, 1_000_000_000, 0f, settings).copySamples(),
            used.advance(next, 1_000_000_000, 0f, settings).copySamples())
        used.reset()
        assertArrayEquals(withoutOnset.copySamples(), used.advance(source(onset = 0), 1_000_000_000, 0f, settings).copySamples())

        val stale = QuietStyle()
        val observation = source(onset = 3)
        stale.advance(observation, 1_000_000_000, 0f, settings)
        identity(stale.advance(observation, 1_250_000_001, 0f, settings))
        // A clock supplied earlier again must not replay the already consumed event.
        assertArrayEquals(withoutOnset.copySamples(), stale.advance(observation, 1_000_000_000, 0f, settings).copySamples())
    }
}
