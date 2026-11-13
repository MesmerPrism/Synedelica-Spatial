// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored process-wide scalar admission; never retains a host, recorder, or PCM.
package io.github.mesmerprism.synedelica.demo.spatial

import java.util.concurrent.atomic.AtomicLong

internal object CaptureGenerations {
    private val counter = AtomicLong()
    fun next(): Long = counter.incrementAndGet().also { check(it > 0) }
}

class CaptureBusyException : IllegalStateException("Previous process capture is still releasing")
class CaptureReleaseFailedException : IllegalStateException("Process capture release failed")
class CaptureLease internal constructor(val generation: Long)

/** Injectable admission shared by production capture instances, including recreated hosts. */
class CaptureWorkerGate {
    private var active: CaptureLease? = null
    private var highWater = 0L
    @Volatile var failed = false
        private set

    @Synchronized fun tryAcquire(generation: Long = CaptureGenerations.next()): CaptureLease? {
        if (failed || active != null || generation <= highWater) return null
        highWater = generation
        return CaptureLease(generation).also { active = it }
    }
    @Synchronized fun complete(lease: CaptureLease, released: Boolean) {
        if (failed || active !== lease) return
        if (released) active = null else failed = true
    }
}

internal object ProcessCaptureOwnership { val gate = CaptureWorkerGate() }
