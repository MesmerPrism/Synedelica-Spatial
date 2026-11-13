// SPDX-License-Identifier: AGPL-3.0-or-later
// Authored admission regression; cancels before worker allocation of any AudioRecord.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AndroidAudioRuntimeAdmissionTest {
    private class Receiver : ProductAudioCaptureReceiver {
        var callbacks = 0
        override fun onStarted() { callbacks++ }
        override fun onPcm(pcm: ShortArray, count: Int, capturedAtNanos: Long) { callbacks++ }
        override fun onFailure() { callbacks++ }
    }

    @Test fun newRuntimeCannotBypassOutstandingDefaultProcessMicrophone() {
        val first = AndroidAudioRecordRuntime()
        val replacement = AndroidAudioRecordRuntime()
        val receiver = Receiver()
        val session = first.create(31, receiver) // create admits only; does not allocate recorder.
        val blocked = runCatching { replacement.create(32, Receiver()) }.isFailure
        assertTrue("A second runtime must share process microphone admission", blocked)
        val retired = CountDownLatch(1)
        session.stop { retired.countDown() } // cancel before start => actual no-recorder path.
        assertTrue(retired.await(2, TimeUnit.SECONDS))
        assertEquals(0, receiver.callbacks)
        val next = replacement.create(32, Receiver())
        val nextRetired = CountDownLatch(1)
        next.stop { nextRetired.countDown() }
        assertTrue(nextRetired.await(2, TimeUnit.SECONDS))
    }
}
