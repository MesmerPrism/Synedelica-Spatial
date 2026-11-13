// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored worker-termination observer; no recorder or PCM ownership.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * operation owns capture and all synchronous capture callbacks through release.
 * Completion is observed on a separate joining thread, never the capture worker's
 * finally block. A join/observer failure cannot manufacture an EOF acknowledgment.
 */
class ProductAudioWorkerLifetime(
    private val operation: () -> Boolean,
    private val onTerminated: (ProductAudioCaptureRetirement) -> Unit,
    private val onStartFailure: () -> Unit = {},
    private val workerFactory: (Runnable) -> Thread = { Thread(it, "synedelica-capture") },
) {
    private val started = AtomicBoolean(false)

    fun start(): Boolean {
        check(started.compareAndSet(false, true)) { "Worker lifetime already started" }
        val released = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val worker: Thread
        try {
            worker = workerFactory(Runnable {
                try { released.set(operation()) }
                catch (_: Exception) { failure.set("capture-worker-failed") }
            })
            worker.start()
        }
        catch (_: Exception) {
            // Factory returns an unstarted worker; operation never began.
            // Notify and drain the start failure BEFORE acknowledging no resources.
            try { onStartFailure() }
            catch (_: Exception) {
                onTerminated(ProductAudioCaptureRetirement(true, true, true, "capture-start-notification-failed"))
                return false
            }
            onTerminated(ProductAudioCaptureRetirement(true, true, true))
            return false
        }
        try {
            Thread({
                try { worker.join() }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    onTerminated(ProductAudioCaptureRetirement(false, false, false, "capture-worker-join-interrupted"))
                    return@Thread
                }
                onTerminated(ProductAudioCaptureRetirement(released.get(), true, true,
                    failure.get() ?: if (released.get()) null else "capture-release-failed"))
            }, "synedelica-capture-join").start()
        } catch (_: Exception) {
            onTerminated(ProductAudioCaptureRetirement(false, false, false, "capture-worker-observer-failed"))
            return false
        }
        return true
    }
}
