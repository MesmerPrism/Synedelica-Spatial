// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored for Synedelica Spatial; no recovered application source.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Activity-owned, foreground-only runtime. No service, permission request, PCM recording,
 * network or native texture submission. One runtime instance per ProductAudioFeatureOwner.
 * Hardware/Android qualification is the embedding integrator's separate responsibility.
 */
class AndroidAudioRecordRuntime : ProductAudioCaptureRuntime {
    private val gate = Any()
    private var outstanding: Session? = null

    override fun create(generation: Long, receiver: ProductAudioCaptureReceiver): ProductAudioCaptureSession =
        synchronized(gate) {
            check(outstanding == null) { "Prior capture has not acknowledged release" }
            Session(generation, receiver).also { outstanding = it }
        }

    private inner class Session(
        private val generation: Long,
        private val receiver: ProductAudioCaptureReceiver,
    ) : ProductAudioCaptureSession {
        private val stateGate = Any()
        private val cancelled = AtomicBoolean(false)
        private var started = false
        private var workerLaunched = false
        private var finished: Boolean? = null
        private var completion: ((Boolean) -> Unit)? = null
        private var completionDelivered = false

        override fun start() {
            val launch = synchronized(stateGate) {
                check(!started) { "Capture session already started" }
                started = true
                claimWorker()
            }
            if (launch) launchWorker()
        }

        override fun stop(onClosed: (Boolean) -> Unit) {
            // Logical cancellation does not wait for AudioRecord, a worker or a callback.
            cancelled.set(true)
            val ready: Boolean?
            val launch: Boolean
            synchronized(stateGate) {
                if (completion != null) return
                completion = onClosed
                ready = finished
                if (ready != null) completionDelivered = true
                launch = ready == null && claimWorker()
            }
            if (launch) launchWorker() // Also retires a session cancelled before start().
            if (ready != null) onClosed(ready)
        }

        private fun claimWorker(): Boolean {
            if (workerLaunched) return false
            workerLaunched = true
            return true
        }

        private fun launchWorker() {
            try {
                Thread({ capture() }, "synedelica-audio-$generation").start()
            } catch (_: Exception) {
                // No recorder was allocated. A failed thread launch must not strand retirement.
                finish(true)
                if (!cancelled.get()) receiver.onFailure()
            }
        }

        @SuppressLint("MissingPermission") // Only the owning foreground Start path admits this session.
        private fun capture() {
            var recorder: AudioRecord? = null
            val pcm = ShortArray(256)
            var released = true
            try {
                if (!cancelled.get()) {
                    val minimum = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    )
                    check(minimum > 0) { "Audio input configuration unavailable" }
                    recorder = AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum, 8192))
                        .build()
                    check(recorder.state == AudioRecord.STATE_INITIALIZED)
                    if (!cancelled.get()) {
                        recorder.startRecording()
                        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        if (!cancelled.get()) receiver.onStarted()
                        val timestamp = AudioTimestamp()
                        var consumedFrames = 0L
                        var lastProgress = SystemClock.elapsedRealtimeNanos()
                        while (!cancelled.get()) {
                            check(SystemClock.elapsedRealtimeNanos() - lastProgress <= NO_PROGRESS_NANOS) {
                                "Audio input produced no fresh timestamped PCM"
                            }
                            val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                            if (count < 0) error("Audio input failed")
                            if (count == 0) {
                                LockSupport.parkNanos(2_000_000L)
                            } else {
                                val firstFrame = consumedFrames
                                consumedFrames += count
                                try {
                                    if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                                        val now = SystemClock.elapsedRealtimeNanos()
                                        val capturedAt = ProductAudioCaptureTime.firstSample(
                                            firstFrame, timestamp.framePosition, timestamp.nanoTime,
                                            recorder.sampleRate, now,
                                        )
                                        // Missing, stale or inconsistent anchors drop PCM. Never stamp it as now.
                                        if (capturedAt != null && !cancelled.get()) {
                                            lastProgress = now
                                            receiver.onPcm(pcm, count, capturedAt)
                                        }
                                    }
                                } finally { pcm.fill(0) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                if (!cancelled.get()) receiver.onFailure()
            } finally {
                pcm.fill(0)
                recorder?.let {
                    try {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                    } catch (_: Exception) { /* release remains mandatory after stop failure */ }
                    try { it.release() } catch (_: Exception) { released = false }
                }
                finish(released)
            }
        }

        private fun finish(released: Boolean) {
            // Only a successful release opens the runtime gate for a replacement recorder.
            synchronized(gate) { if (released && outstanding === this) outstanding = null }
            val notify: ((Boolean) -> Unit)?
            synchronized(stateGate) {
                if (finished != null) return
                finished = released
                notify = if (!completionDelivered) completion else null
                if (notify != null) completionDelivered = true
            }
            // Never acquire the feature owner's lock while holding either runtime monitor.
            notify?.invoke(released)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val NO_PROGRESS_NANOS = 1_000_000_000L
    }
}
