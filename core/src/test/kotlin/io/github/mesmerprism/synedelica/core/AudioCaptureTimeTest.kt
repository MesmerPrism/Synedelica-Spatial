// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.core

import org.junit.Assert.*
import org.junit.Test

class AudioCaptureTimeTest {
    @Test fun mapsFirstSampleFromValidHardwareFrameAnchor() {
        assertEquals(1_050_000_000L,
            AudioCaptureTime.firstSample(45_600, 48_000, 1_100_000_000, 48_000, 1_100_000_000))
        assertEquals(1_100_000_000L,
            AudioCaptureTime.firstSample(48_000, 48_000, 1_100_000_000, 48_000, 1_100_000_000))
    }

    @Test fun acceptsInclusiveAgeBoundaryAndExpiresOldQueuedAudio() {
        assertEquals(750_000_000L,
            AudioCaptureTime.firstSample(0, 12_000, 1_000_000_000, 48_000, 1_000_000_000))
        assertNull(AudioCaptureTime.firstSample(0, 12_001, 1_000_000_000, 48_000, 1_000_000_000))
        assertNull(AudioCaptureTime.firstSample(0, 48_000, 2_000_000_000, 48_000, 2_000_000_000))
    }

    @Test fun rejectsFutureAnchorEvenIfDerivedFirstSampleWouldAppearFresh() {
        assertNull(AudioCaptureTime.firstSample(0, 48_000, 2_000_000_000, 48_000, 1_100_000_000))
        assertNull(AudioCaptureTime.firstSample(0, 0, 1_100_000_001, 48_000, 1_100_000_000))
    }

    @Test fun rejectsInvalidAnchorFramesClockAndSampleRate() {
        for (anchor in listOf(0L, -1L)) {
            assertNull(AudioCaptureTime.firstSample(0, 0, anchor, 48_000, 1_000_000_000))
        }
        assertNull(AudioCaptureTime.firstSample(2, 1, 1_000_000_000, 48_000, 1_000_000_000))
        assertNull(AudioCaptureTime.firstSample(-1, 0, 1_000_000_000, 48_000, 1_000_000_000))
        assertNull(AudioCaptureTime.firstSample(0, -1, 1_000_000_000, 48_000, 1_000_000_000))
        assertNull(AudioCaptureTime.firstSample(0, 0, 1_000_000_000, 48_000, -1))
        for (rate in listOf(0, -1)) {
            assertNull(AudioCaptureTime.firstSample(0, 0, 1_000_000_000, rate, 1_000_000_000))
        }
    }

    @Test fun rejectsOverflowAndNonpositiveDerivedTime() {
        assertNull(AudioCaptureTime.firstSample(0, Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE))
        assertNull(AudioCaptureTime.firstSample(0, 1, 1, 1, 1))
        assertNull(AudioCaptureTime.firstSample(0, 1, 1_000_000_000, 1, 1_000_000_000))
    }
}
