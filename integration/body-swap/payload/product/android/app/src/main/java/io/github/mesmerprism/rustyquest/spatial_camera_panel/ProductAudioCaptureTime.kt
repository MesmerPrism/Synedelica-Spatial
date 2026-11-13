// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored for Synedelica Spatial; no recovered application source.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import io.github.mesmerprism.synedelica.core.AudioCaptureTime

/** Original first-frame BOOTTIME estimate, not a read-completion/arrival timestamp. */
object ProductAudioCaptureTime {
    fun firstSample(
        consumedBeforeRead: Long,
        anchorFramePosition: Long,
        anchorNanos: Long,
        sampleRate: Int,
        nowNanos: Long,
    ): Long? {
        return AudioCaptureTime.firstSample(consumedBeforeRead, anchorFramePosition,
            anchorNanos, sampleRate, nowNanos)
    }
}
