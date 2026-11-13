// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored for Synedelica Spatial; no recovered application source.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import io.github.mesmerprism.synedelica.core.StyleSettings

/** Typed UI/CLI binding. The actual caller supplies its foreground permission/lifecycle owner. */
class ProductAudioControls(private val owner: ProductAudioFeatureOwner) {
    fun start(): ProductAudioStatus = owner.start()
    fun stop(): ProductAudioStatus = owner.stop()
    fun status(): ProductAudioStatus = owner.status()
    fun updateSettings(settings: StyleSettings): Boolean = owner.updateSettings(settings)

    companion object {
        const val START_ACTION = "audio.start"
        const val STOP_ACTION = "audio.stop"
        const val STATUS_ACTION = "audio.status"
        const val SETTINGS_ACTION = "audio.settings"
    }
}
