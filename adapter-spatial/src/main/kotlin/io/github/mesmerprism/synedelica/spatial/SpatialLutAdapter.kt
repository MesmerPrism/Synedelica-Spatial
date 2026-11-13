// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored scheduling/validation, independently implemented from the cleared ABI.
package io.github.mesmerprism.synedelica.spatial

import io.github.mesmerprism.synedelica.core.AudioClockDomain
import io.github.mesmerprism.synedelica.core.LutFrame
import java.util.concurrent.atomic.AtomicReference

interface MainThreadPort {
    fun dispatch(action: () -> Unit)
    fun checkMainThread()
}

/** Scene owns the prepared handle. Implementations must synchronously apply or remove it. */
interface SceneLutPort {
    fun prepare(samples: IntArray): Any
    fun setPassthroughLut(lut: Any?)
}

/** Injectable BOOTTIME and main-thread boundary; all Scene operations happen in dispatch. */
class SpatialLutAdapter(
    private val main: MainThreadPort,
    private val bootTimeNanos: () -> Long,
    private val scene: SceneLutPort,
) {
    private data class Run(val generation: Long)
    private val active = AtomicReference<Run?>(null)
    private val transition = Any()
    // These fields are exclusively main-thread-owned.
    private var applied: LutFrame? = null
    private var retained: Any? = null
    private var observedRun: Run? = null
    private var sequence = -1L
    private var capturedAt = -1L

    fun start(generation: Long) {
        require(generation >= 0)
        val run = Run(generation)
        synchronized(transition) { active.set(run) }
        main.dispatch { if (active.get() === run) clear() }
    }

    fun submit(frame: LutFrame) {
        val run = active.get() ?: return
        main.dispatch {
            main.checkMainThread()
            if (active.get() !== run) return@dispatch
            if (observedRun !== run) {
                observedRun = run
                sequence = -1
                capturedAt = -1
            }
            if (!usable(frame, run) || frame.sequence < sequence || frame.capturedAtNanos < capturedAt ||
                (frame.sequence == sequence && frame.capturedAtNanos != capturedAt)) {
                clear()
                return@dispatch
            }
            sequence = frame.sequence
            capturedAt = frame.capturedAtNanos
            val handle = scene.prepare(frame.copySamples())
            // Preparation may take time; source time is never replaced with a newer timestamp.
            synchronized(transition) {
                if (active.get() !== run) return@dispatch
                if (!usable(frame, run)) { clear(); return@dispatch }
                // Stop uses this same monitor, so it cannot return while an old
                // Scene submission is in flight. Dispatch never holds the monitor.
                scene.setPassthroughLut(handle)
                retained = handle
                applied = frame
            }
        }
    }

    /** Host watchdog calls this even when no further audio block arrives. */
    fun invalidateIfStale() {
        val run = active.get() ?: return
        main.dispatch {
            main.checkMainThread()
            if (active.get() === run && applied?.let { !usable(it, run) } == true) clear()
        }
    }

    fun stop() {
        synchronized(transition) { active.set(null) }
        main.dispatch { if (active.get() == null) clear() }
    }

    private fun usable(frame: LutFrame, run: Run): Boolean {
        val now = bootTimeNanos()
        return frame.generation == run.generation && frame.clockDomain == AudioClockDomain.BootTime &&
            frame.sequence >= 0 && frame.capturedAtNanos > 0 && now >= frame.capturedAtNanos &&
            now - frame.capturedAtNanos <= 250_000_000L
    }

    private fun clear() {
        main.checkMainThread()
        try { scene.setPassthroughLut(null) } finally { applied = null; retained = null }
    }
}
