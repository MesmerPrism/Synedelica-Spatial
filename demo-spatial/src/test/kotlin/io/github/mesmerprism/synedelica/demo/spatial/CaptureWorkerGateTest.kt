// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored process ownership tests; no Android runtime required.
package io.github.mesmerprism.synedelica.demo.spatial

import org.junit.Assert.*
import org.junit.Test

class CaptureWorkerGateTest {
    @Test fun admissionSurvivesHostRecreationAndRejectsOldCompletions() {
        val gate = CaptureWorkerGate()
        val oldHost = gate.tryAcquire()!!
        assertNull(gate.tryAcquire())
        gate.complete(oldHost, true)
        val newHost = gate.tryAcquire()!!
        assertTrue(newHost.generation > oldHost.generation)
        gate.complete(oldHost, true)
        assertNull(gate.tryAcquire())
        gate.complete(newHost, true)
        assertNotNull(gate.tryAcquire())
    }
    @Test fun failedReleaseBlocksEveryReplacement() {
        val gate = CaptureWorkerGate(); val lease = gate.tryAcquire()!!
        gate.complete(lease, false)
        assertNull(gate.tryAcquire())
        gate.complete(lease, true)
        assertNull(gate.tryAcquire())
        assertTrue(gate.failed)
    }
}
