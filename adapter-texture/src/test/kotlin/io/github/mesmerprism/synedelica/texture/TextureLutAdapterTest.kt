// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored synthetic contract tests; no recovered profile data.
package io.github.mesmerprism.synedelica.texture

import io.github.mesmerprism.synedelica.core.*
import org.junit.Assert.*
import org.junit.Test

class TextureLutAdapterTest {
    private val source = AudioSnapshot(4, 8, 1_000_000_000, AudioClockDomain.BootTime, true, 0f, 0f, 0)
    private class Sink : TextureLutSink {
        var samples: ByteArray? = null
        var clears = 0
        override fun upload(upload: TextureLutUpload) { samples = upload.copyRgb8() }
        override fun remove() { clears++ }
    }
    @Test fun identityPreservesEveryCubePointAndCaptureIdentity() {
        val frame = LutFrame.identity(source)
        val upload = TextureLutAdapter.prepare(frame)
        assertEquals(16, upload.dimension)
        assertEquals(1, upload.contractVersion)
        assertEquals(LutOrder.RedFastestBlueOutermost, upload.order)
        assertEquals(ColorEncoding.EncodedStraightRgb8, upload.encoding)
        assertEquals(source.generation, upload.generation)
        assertEquals(source.sequence, upload.sequence)
        assertEquals(source.capturedAtNanos, upload.capturedAtNanos)
        assertEquals(AudioClockDomain.BootTime, upload.clockDomain)
        val bytes = upload.copyRgb8()
        for (b in 0..15) for (g in 0..15) for (r in 0..15) {
            val offset = ((b * 16 + g) * 16 + r) * 3
            assertEquals(r * 17, bytes[offset].toInt() and 255)
            assertEquals(g * 17, bytes[offset + 1].toInt() and 255)
            assertEquals(b * 17, bytes[offset + 2].toInt() and 255)
        }
        bytes.fill(0)
        assertEquals(255, upload.copyRgb8().last().toInt() and 255)
    }
    @Test fun delayBeyondBoundRemovesInsteadOfUploadingAndDoesNotRetimestamp() {
        val upload = TextureLutAdapter.prepare(LutFrame.identity(source))
        val sink = Sink()
        assertTrue(TextureLutAdapter.submit(upload, 4, source.capturedAtNanos + 250_000_000, sink))
        sink.samples = null
        assertFalse(TextureLutAdapter.submit(upload, 4, source.capturedAtNanos + 250_000_001, sink))
        assertNull(sink.samples)
        assertEquals(1, sink.clears)
        assertEquals(source.capturedAtNanos, upload.capturedAtNanos)
    }
    @Test fun futureAndWrongGenerationAndInvalidIdentityFailClosed() {
        val sink = Sink()
        val upload = TextureLutAdapter.prepare(LutFrame.identity(source))
        assertFalse(TextureLutAdapter.submit(upload, 5, source.capturedAtNanos, sink))
        assertFalse(TextureLutAdapter.submit(upload, 4, source.capturedAtNanos - 1, sink))
        assertFalse(TextureLutAdapter.submit(TextureLutUpload(-1, 8, 1, AudioClockDomain.BootTime, ByteArray(12288)), -1, 1, sink))
        assertEquals(3, sink.clears)
    }
    @Test fun inputByteArrayIsOwnedAndDimensionsValidated() {
        val bytes = ByteArray(12288) { 127 }
        val upload = TextureLutUpload(4, 8, 1, AudioClockDomain.BootTime, bytes)
        bytes.fill(0)
        assertEquals(127, upload.copyRgb8()[0].toInt())
        try { TextureLutUpload(4, 8, 1, AudioClockDomain.BootTime, ByteArray(2)); fail("wrong dimensions") }
        catch (_: IllegalArgumentException) { }
    }
}
