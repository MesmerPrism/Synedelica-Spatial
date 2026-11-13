// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.texture

import io.github.mesmerprism.synedelica.core.AudioClockDomain
import io.github.mesmerprism.synedelica.core.LutFrame

enum class LutOrder { RedFastestBlueOutermost }
enum class ColorEncoding { EncodedStraightRgb8 }

/** CPU-only value. RGB8, red-fastest cube points, no texture/eye/alpha ownership. */
class TextureLutUpload(
    val generation: Long,
    val sequence: Long,
    val capturedAtNanos: Long,
    val clockDomain: AudioClockDomain,
    rgb8: ByteArray,
) {
    val contractVersion: Int = 1
    val dimension: Int = 16
    val order: LutOrder = LutOrder.RedFastestBlueOutermost
    val encoding: ColorEncoding = ColorEncoding.EncodedStraightRgb8
    private val samples = rgb8.copyOf()
    init { require(samples.size == dimension * dimension * dimension * 3) }
    fun copyRgb8(): ByteArray = samples.copyOf()
}

/** Host must submit synchronously here, or revalidate again at its actual native call. */
interface TextureLutSink {
    fun upload(upload: TextureLutUpload)
    fun remove()
}

object TextureLutAdapter {
    fun prepare(frame: LutFrame): TextureLutUpload = TextureLutUpload(
        frame.generation, frame.sequence, frame.capturedAtNanos, frame.clockDomain,
        frame.copySamples().map(Int::toByte).toByteArray(),
    )

    /** Host separately rejects sequence regression against its last submitted sequence. */
    fun submit(upload: TextureLutUpload, currentGeneration: Long, nowNanos: Long,
        sink: TextureLutSink): Boolean {
        val fresh = upload.generation >= 0 && upload.sequence >= 0 &&
            upload.generation == currentGeneration && upload.capturedAtNanos > 0 &&
            upload.clockDomain == AudioClockDomain.BootTime &&
            nowNanos >= upload.capturedAtNanos &&
            nowNanos - upload.capturedAtNanos <= 250_000_000L
        if (!fresh) { sink.remove(); return false }
        sink.upload(upload)
        return true // Synchronous callback delivered, not GPU completion or effective readback.
    }
}
