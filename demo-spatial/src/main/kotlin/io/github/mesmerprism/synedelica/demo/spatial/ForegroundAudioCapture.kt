// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored using standard Android AudioRecord/AudioTimestamp APIs.
// Borrowed PCM exists only within one capture worker and is cleared after synchronous analysis.
package io.github.mesmerprism.synedelica.demo.spatial

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mesmerprism.synedelica.core.AudioAnalyzer
import io.github.mesmerprism.synedelica.core.AudioCaptureTime
import io.github.mesmerprism.synedelica.core.QuietStyle
import io.github.mesmerprism.synedelica.core.StyleSettings
import io.github.mesmerprism.synedelica.spatial.SpatialLutAdapter
import java.util.concurrent.atomic.AtomicBoolean

/** Exactly one worker per acknowledged lifecycle session; no files, network, or PCM queues. */
class ForegroundAudioCapture(
    private val adapter: SpatialLutAdapter,
    private val gate: CaptureWorkerGate = ProcessCaptureOwnership.gate,
) : CapturePort {
    private val main = Handler(Looper.getMainLooper())
    override fun start(generation: Long, onEnded: (CaptureEnd) -> Unit): CaptureSession {
        val lease = gate.tryAcquire(generation) ?: throw if (gate.failed)
            CaptureReleaseFailedException() else CaptureBusyException()
        val stopped = AtomicBoolean(false)
        val thread = Thread({
            var recorder: AudioRecord? = null
            val samples = ShortArray(256)
            val analyzer = AudioAnalyzer()
            val style = QuietStyle()
            try {
                val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "No foreground microphone format" }
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum, samples.size * 2 * 4))
                    .build()
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                if (!stopped.get()) {
                    recorder.startRecording()
                    val timestamp = AudioTimestamp()
                    var consumedFrames = 0L
                    var previousSourceTime: Long? = null
                    var lastProgress = SystemClock.elapsedRealtimeNanos()
                    while (!stopped.get()) {
                        check(SystemClock.elapsedRealtimeNanos() - lastProgress <= NO_PROGRESS_NANOS) {
                            "Microphone produced no fresh timestamped PCM"
                        }
                        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0) { "Microphone read failed: $count" }
                        if (count == 0) { Thread.sleep(5); continue }
                        val firstFrame = consumedFrames
                        consumedFrames += count
                        // Map the first PCM frame against the original hardware BOOTTIME
                        // anchor before analysis. A missing/stale anchor drops this block.
                        if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                            val now = SystemClock.elapsedRealtimeNanos()
                            val capturedAt = AudioCaptureTime.firstSample(firstFrame, timestamp.framePosition,
                                timestamp.nanoTime, recorder.sampleRate, now)
                            if (capturedAt != null && !stopped.get()) {
                                lastProgress = now
                                for (snapshot in analyzer.accept(samples, count, generation, capturedAt)) {
                                    val delta = previousSourceTime?.let {
                                        ((snapshot.capturedAtNanos - it).coerceAtLeast(0) / 1_000_000_000.0).toFloat()
                                    } ?: 0f
                                    previousSourceTime = snapshot.capturedAtNanos
                                    val frame = style.advance(snapshot, SystemClock.elapsedRealtimeNanos(), delta, StyleSettings())
                                    if (!stopped.get()) adapter.submit(frame)
                                }
                            }
                        }
                        samples.fill(0)
                    }
                }
            } catch (_: Exception) {
                // Retirement removes modulation; no implicit restart follows a capture error.
            } finally {
                stopped.set(true)
                var released = recorder == null
                try {
                    recorder?.let {
                        try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
                        catch (_: Exception) { /* Still attempt release. */ }
                        try { it.release(); released = true }
                        catch (_: Exception) { released = false }
                    }
                } finally {
                    samples.fill(0)
                    analyzer.reset()
                    style.reset()
                    val result = if (released) CaptureEnd.Released else CaptureEnd.ReleaseFailed
                    gate.complete(lease, released)
                    main.post { onEnded(result) }
                }
            }
        }, "synedelica-demo-capture")
        try { thread.start() } catch (failure: Throwable) {
            // The worker has not begun and therefore cannot own an AudioRecord.
            gate.complete(lease, true)
            throw failure
        }
        return object : CaptureSession { override fun stop() { stopped.set(true) } }
    }
    private companion object {
        const val RATE = 48_000
        const val NO_PROGRESS_NANOS = 1_000_000_000L
    }
}
