// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored for Synedelica Spatial; no recovered application source.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

/** Physical capture evidence. Missing evidence remains pending unless failure is explicit. */
data class ProductAudioCaptureRetirement(
    val captureReleased: Boolean,
    val workerTerminated: Boolean,
    val callbacksDrained: Boolean,
    val failure: String? = null,
) {
    val successful: Boolean get() = captureReleased && workerTerminated && callbacksDrained && failure == null
}

data class ProductAudioRetirementResult(
    val captureReleased: Boolean,
    val workerTerminated: Boolean,
    val callbacksDrained: Boolean,
    val sinksRetired: Boolean,
    val failure: String? = null,
) {
    val successful: Boolean get() = captureReleased && workerTerminated && callbacksDrained && sinksRetired && failure == null
}

/**
 * App-owned participant for the carrier's existing Whole/Original retirement.
 * The carrier joins this exact ticket before its terminal decision and fence clear.
 * The completion view cannot complete or cancel the owner's private source future.
 */
class ProductAudioRetirementTicket internal constructor(
    val generation: Long,
    source: CompletableFuture<ProductAudioRetirementResult>,
) {
    val contractVersion: Int = 2
    val completion: CompletionStage<ProductAudioRetirementResult> = ReadOnlyStage(source)

    private class ReadOnlyStage<T>(private val source: CompletableFuture<T>) : CompletionStage<T> by source {
        override fun toCompletableFuture(): CompletableFuture<T> = source.thenApply { it }
    }
}

/** One process admission, held through physical release and every consumer retirement. */
class ProductAudioAdmissionGate {
    class Lease internal constructor(val generation: Long)
    private var active: Lease? = null
    private var terminalFailure = false
    val busy: Boolean @Synchronized get() = active != null
    val failed: Boolean @Synchronized get() = terminalFailure

    @Synchronized fun tryAcquire(): Lease? {
        if (active != null || terminalFailure) return null
        return Lease(generations.incrementAndGet()).also { active = it }
    }

    /** Foreign, old and replayed leases cannot free a current owner or repair a failure. */
    @Synchronized fun complete(lease: Lease, released: Boolean) {
        if (active !== lease || terminalFailure) return
        if (released) active = null else terminalFailure = true
    }

    private companion object { val generations = AtomicLong() }
}

/** Survives Activity recreation; app owner and raw microphone have separate admission domains. */
object ProductAudioProcessAdmission {
    val appOwner = ProductAudioAdmissionGate()
    val microphone = ProductAudioAdmissionGate()
}
