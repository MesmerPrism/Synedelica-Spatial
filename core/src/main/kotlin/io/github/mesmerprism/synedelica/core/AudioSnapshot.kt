// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.core

enum class AudioClockDomain { BootTime }

data class AudioSnapshot(
    val generation: Long,
    val sequence: Long,
    val capturedAtNanos: Long,
    val clockDomain: AudioClockDomain,
    val valid: Boolean,
    val amplitude: Float,
    val rms: Float,
    val onsetCount: Long,
)
