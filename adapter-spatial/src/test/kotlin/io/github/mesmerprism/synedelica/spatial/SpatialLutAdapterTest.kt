// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored tests; no reference application code or palette data.
package io.github.mesmerprism.synedelica.spatial

import io.github.mesmerprism.synedelica.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class SpatialLutAdapterTest {
    private class Harness {
        var now = 1_000_000_000L
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val calls = mutableListOf<Any?>()
        var onMain = false
        var onPrepare: () -> Unit = {}
        var onSet: (Any?) -> Unit = {}
        val adapter = SpatialLutAdapter(object : MainThreadPort {
            override fun dispatch(action: () -> Unit) { queue.add(action) }
            override fun checkMainThread() { check(onMain) }
        }, { now }, object : SceneLutPort {
            override fun prepare(samples: IntArray): Any { check(onMain); onPrepare(); return samples }
            override fun setPassthroughLut(lut: Any?) { check(onMain); onSet(lut); calls.add(lut) }
        })
        fun drain() {
            onMain = true
            try { while (true) (queue.poll() ?: break).invoke() } finally { onMain = false }
        }
        fun frame(generation: Long = 1, age: Long = 0): LutFrame = LutFrame.identity(
            AudioSnapshot(generation, 1, now - age, AudioClockDomain.BootTime, true, 0f, 0f, 0))
    }

    @Test fun freshBoundaryAndOwnedSamples() {
        val h = Harness(); h.adapter.start(1); h.drain()
        h.adapter.submit(h.frame(age = 250_000_000)); h.drain()
        assertNotNull(h.calls.last())
        h.now++; h.adapter.invalidateIfStale(); h.drain(); assertNull(h.calls.last())
    }
    @Test fun rechecksAfterScenePreparation() {
        val h = Harness(); h.adapter.start(1); h.drain()
        h.onPrepare = { h.now += 250_000_001 }
        h.adapter.submit(h.frame()); h.drain(); assertNull(h.calls.last())
    }
    @Test fun stoppedQueuedWorkCannotReviveAfterRestart() {
        val h = Harness(); h.adapter.start(1); h.adapter.submit(h.frame())
        h.adapter.stop(); h.adapter.start(2); h.drain()
        assertTrue(h.calls.all { it == null })
        h.adapter.submit(h.frame(2)); h.drain(); assertNotNull(h.calls.last())
    }
    @Test fun stopWaitsForInFlightSceneCallBeforeReturning() {
        val h = Harness(); h.adapter.start(1); h.drain()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        h.onSet = { if (it != null) { inside.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        h.adapter.submit(h.frame())
        val sceneThread = Thread { h.drain() }.apply { start() }
        check(inside.await(5, TimeUnit.SECONDS))
        val stoppingThread = Thread { stopping.countDown(); h.adapter.stop(); stopped.countDown() }.apply { start() }
        check(stopping.await(5, TimeUnit.SECONDS))
        try { assertFalse(stopped.await(100, TimeUnit.MILLISECONDS)) }
        finally { release.countDown(); sceneThread.join(5000); stoppingThread.join(5000) }
        assertEquals(0L, stopped.count)
        h.drain(); assertNull(h.calls.last())
    }
    @Test fun zeroTimestampAndRegressionRemove() {
        val h = Harness(); h.adapter.start(1); h.drain()
        fun frame(sequence: Long, time: Long) = LutFrame.identity(AudioSnapshot(1, sequence, time, AudioClockDomain.BootTime, true, 0f, 0f, 0))
        h.adapter.submit(frame(2, h.now)); h.drain(); assertNotNull(h.calls.last())
        for (bad in listOf(frame(3, 0), frame(1, h.now), frame(3, h.now - 1), frame(2, h.now - 1))) {
            h.adapter.submit(bad); h.drain(); assertNull(h.calls.last())
        }
        h.now++
        h.adapter.submit(frame(2, h.now)); h.drain(); assertNull(h.calls.last())
        h.adapter.submit(frame(2, h.now - 1)); h.drain(); assertNotNull(h.calls.last())
    }
    @Test fun mismatchFutureAndStaleRemove() {
        val h = Harness(); h.adapter.start(1); h.drain()
        for (frame in listOf(h.frame(2), h.frame(age = -1), h.frame(age = 250_000_001))) {
            h.adapter.submit(frame); h.drain(); assertNull(h.calls.last())
        }
    }
}
