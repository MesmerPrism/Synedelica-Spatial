// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored capture ownership tests; Android recorder code is not required.
package io.github.mesmerprism.synedelica.demo.spatial

import org.junit.Assert.*
import org.junit.Test

class CaptureLifecycleTest {
    private class Harness {
        var allowed = false
        val permissions = mutableListOf<(Boolean) -> Unit>()
        val ended = mutableListOf<(CaptureEnd) -> Unit>()
        val starts = mutableListOf<Long>()
        val effects = mutableListOf<Long?>()
        var stops = 0
        var busy = false
        val life = CaptureLifecycle(object : CapturePermissionPort {
            override fun granted() = allowed
            override fun request(result: (Boolean) -> Unit) { permissions.add(result) }
        }, object : CapturePort {
            override fun start(generation: Long, onEnded: (CaptureEnd) -> Unit): CaptureSession {
                if (busy) throw CaptureBusyException()
                starts.add(generation); ended.add(onEnded)
                return object : CaptureSession { override fun stop() { stops++ } }
            }
        }, object : CaptureEffectPort {
            override fun start(generation: Long) { effects.add(generation) }
            override fun stop() { effects.add(null) }
        }, {})
        init { life.foreground(true); life.focus(true) }
    }
    @Test fun deniedPermissionNeverCaptures() {
        val h = Harness(); h.life.start(); h.permissions.single()(false)
        assertTrue(h.starts.isEmpty()); assertEquals(CaptureState.Idle, h.life.state)
    }
    @Test fun replacementHostWaitsForProcessCaptureAdmission() {
        val h = Harness(); h.allowed = true; h.busy = true; h.life.start()
        assertEquals(CaptureState.Retiring, h.life.state)
        assertTrue(h.starts.isEmpty())
        h.busy = false; h.life.retryPendingStart()
        assertEquals(CaptureState.Running, h.life.state)
        assertEquals(1, h.starts.size)
    }
    @Test fun duplicateStartAndRestartWaitForCleanup() {
        val h = Harness(); h.allowed = true; h.life.start(); h.life.start()
        assertEquals(1, h.starts.size)
        h.life.stop(); h.life.start(); assertEquals(1, h.starts.size)
        h.ended[0](CaptureEnd.Released); assertEquals(2, h.starts.size)
        h.ended[0](CaptureEnd.Released); assertEquals(2, h.starts.size)
        assertTrue(h.starts[1] > h.starts[0])
    }
    @Test fun releaseFailureBlocksReplacementPermanently() {
        val h = Harness(); h.allowed = true; h.life.start(); h.life.stop(); h.life.start()
        h.ended[0](CaptureEnd.ReleaseFailed)
        h.life.start(); h.life.stop(); h.life.start()
        assertEquals(CaptureState.Failed, h.life.state)
        assertEquals(1, h.starts.size); assertNull(h.effects.last())
    }
    @Test fun noReleaseAcknowledgmentNeverStartsReplacement() {
        val h = Harness(); h.allowed = true; h.life.start(); h.life.stop(); h.life.start()
        assertEquals(CaptureState.Retiring, h.life.state)
        assertEquals(1, h.starts.size)
        assertNull(h.effects.last())
    }
    @Test fun delayedPermissionCannotRestartStoppedOrDestroyedCapture() {
        for (destroy in listOf(false, true)) {
            val h = Harness(); h.life.start()
            if (destroy) h.life.destroy() else h.life.stop()
            h.permissions.single()(true); assertTrue(h.starts.isEmpty())
        }
    }
    @Test fun focusPauseAndDestroyRetireAndRequireExplicitStart() {
        for (event in listOf<(CaptureLifecycle) -> Unit>({ it.focus(false) }, { it.foreground(false) }, { it.destroy() })) {
            val h = Harness(); h.allowed = true; h.life.start(); event(h.life)
            assertEquals(1, h.stops); assertNull(h.effects.last())
            h.life.focus(true); h.life.foreground(true); h.ended[0](CaptureEnd.Released)
            assertEquals(1, h.starts.size)
        }
    }
}
