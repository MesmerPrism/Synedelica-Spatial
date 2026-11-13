// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored real-thread join regressions; no recorder, PCM, device or original fixtures.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ProductAudioWorkerLifetimeTest {
    @Test fun operationNeverBeganFailureCallbackDrainsBeforeNoResourceAcknowledgement() {
        var operationCalled = false
        val events = mutableListOf<String>()
        val lifetime = ProductAudioWorkerLifetime(
            operation = { operationCalled = true; true },
            onTerminated = { assertTrue(it.successful); events += "ack" },
            onStartFailure = { events += "failure" },
            workerFactory = { action -> object : Thread(action) {
                override fun start() { error("synthetic thread start failure") }
            } },
        )
        assertFalse(lifetime.start())
        assertFalse(operationCalled)
        assertEquals(listOf("failure", "ack"), events)
    }

    @Test fun releaseAloneDoesNotAcknowledgeBeforeWorkerAndCallbackReturn() {
        val callbackEntered = CountDownLatch(1)
        val allowCallbackReturn = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val captureWorker = AtomicReference<Thread>()
        val result = AtomicReference<ProductAudioCaptureRetirement>()
        ProductAudioWorkerLifetime({
            captureWorker.set(Thread.currentThread())
            callbackEntered.countDown()
            check(allowCallbackReturn.await(2, TimeUnit.SECONDS))
            true // Recorder release has completed; returning proves callback drain.
        }, { value -> result.set(value); acknowledged.countDown() }).start()
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
        assertFalse(acknowledged.await(40, TimeUnit.MILLISECONDS))
        allowCallbackReturn.countDown()
        assertTrue(acknowledged.await(2, TimeUnit.SECONDS))
        assertFalse(captureWorker.get().isAlive)
        assertTrue(result.get().captureReleased)
        assertTrue(result.get().workerTerminated)
        assertTrue(result.get().callbacksDrained)
        assertTrue(result.get().successful)
    }

    @Test fun releaseFailureIsTerminalEvenAfterActualWorkerJoin() {
        val done = CountDownLatch(1)
        val result = AtomicReference<ProductAudioCaptureRetirement>()
        ProductAudioWorkerLifetime({ false }, { result.set(it); done.countDown() }).start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(result.get().captureReleased)
        assertTrue(result.get().workerTerminated)
        assertFalse(result.get().successful)
        assertNotNull(result.get().failure)
    }

    @Test fun unhandledWorkerFailureCannotProveRelease() {
        val done = CountDownLatch(1)
        val result = AtomicReference<ProductAudioCaptureRetirement>()
        ProductAudioWorkerLifetime({ error("test capture failure") }, { result.set(it); done.countDown() }).start()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(result.get().successful)
        assertFalse(result.get().captureReleased)
        assertTrue(result.get().workerTerminated)
        assertNotNull(result.get().failure)
    }
}
