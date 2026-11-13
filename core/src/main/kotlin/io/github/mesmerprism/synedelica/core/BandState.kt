// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored for cleared Synedelica Spatial; no recovered profile data.
package io.github.mesmerprism.synedelica.core

import kotlin.math.PI
import kotlin.math.cos

/** Bounded scalar animation state; no audio samples or recovered band profiles. */
internal class BandState {
    private data class Band(val origin: Double, val age: Double)
    private val bands = ArrayList<Band>()
    private var consumedOnsets = 0L

    val activeCount: Int get() = bands.size

    /** Each new cumulative onset contributes at most one of eight 0.75s waves. */
    fun advance(onsets: Long, deltaSeconds: Float, enabled: Boolean) {
        val added = (onsets - consumedOnsets).coerceIn(0L, 8L).toInt()
        val previousCount = consumedOnsets
        consumedOnsets = onsets
        if (!enabled) {
            clear()
            return
        }
        for (index in bands.lastIndex downTo 0) {
            val band = bands[index]
            val age = band.age + deltaSeconds
            if (age >= 0.75) bands.removeAt(index) else bands[index] = band.copy(age = age)
        }
        repeat(added) { index ->
            if (bands.size == 8) bands.removeAt(0)
            val origin = ((previousCount + index + 1) % 8) / 8.0
            bands.add(Band(origin, 0.0))
        }
    }

    /** Sum of traveling cosine bands normalized by the fixed live-band bound. */
    fun sample(position: Double): Double {
        var value = 0.0
        for (band in bands) {
            val progress = band.age / 0.75
            value += (1.0 - progress) * cos(2.0 * PI * (position - band.origin - progress)) / 8.0
        }
        return value.coerceIn(-1.0, 1.0)
    }

    /** Clear live effects without replaying an already consumed observation. */
    fun clear() { bands.clear() }

    fun reset() {
        clear()
        consumedOnsets = 0
    }
}
