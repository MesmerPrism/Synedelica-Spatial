// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored for Synedelica Spatial; no recovered application source.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import io.github.mesmerprism.synedelica.core.AudioSnapshot
import io.github.mesmerprism.synedelica.core.StyleSettings

/** Host-only contract suite. Hardware timing, native compositor and Android permissions remain unqualified. */
object ProductAudioFeatureOwnerContractTest {
    private class Permission : ProductAudioPermission {
        var granted = false
        val requests = mutableListOf<(Boolean) -> Unit>()
        override fun isGranted() = granted
        override fun request(result: (Boolean) -> Unit) { requests += result }
        fun answer(value: Boolean) { granted = value; requests.last()(value) }
    }
    private class Runtime : ProductAudioCaptureRuntime {
        val sessions = mutableListOf<Session>()
        override fun create(generation: Long, receiver: ProductAudioCaptureReceiver): ProductAudioCaptureSession =
            Session(receiver).also { sessions += it }
        class Session(val receiver: ProductAudioCaptureReceiver) : ProductAudioCaptureSession {
            var starts = 0
            var stops = 0
            var closed: ((Boolean) -> Unit)? = null
            override fun start() { starts++; receiver.onStarted() }
            override fun stop(onClosed: (Boolean) -> Unit) { stops++; closed = onClosed }
            fun pcm(at: Long = 1_000_000_000L) = receiver.onPcm(ShortArray(256) { 1_000 }, 256, at)
        }
    }
    private class Sink : ProductAudioSink {
        val values = mutableListOf<AudioSnapshot>()
        val removed = mutableListOf<Long>()
        override fun onAudio(snapshot: AudioSnapshot, settings: StyleSettings) { values += snapshot }
        override fun removeAudio(generation: Long) { removed += generation }
    }
    private class Rig {
        val permission = Permission()
        val runtime = Runtime()
        val owner = ProductAudioFeatureOwner(permission, runtime)
        val controls = ProductAudioControls(owner)
        init { owner.onResume(); owner.onFocusChanged(true) }
        fun grantedStart() { permission.granted = true; controls.start() }
    }
    @JvmStatic fun main(args: Array<String>) {
        permissionRequiresExplicitStartAndDenialLeavesCameraUntouched()
        duplicateStartAndSourceChangeKeepOneCapture()
        fanoutUsesOneImmutableObservationAndDisconnectKeepsCapture()
        stopRejectsLatePcmAndWaitsForCleanupBeforeRestart()
        lifecycleStopsSynchronouslyAndRejectsOldPermission()
        failureRetiresSessionAndReportsTruth()
        cleanupFailureNeverAdmitsRestart()
        originalCaptureTimeNeverUsesReadCompletionAsFreshness()
        controlsValidateSettingsAndExposeUnknownNativeState()
        println("ProductAudioFeatureOwnerContractTest: 9 cases passed")
    }
    private fun permissionRequiresExplicitStartAndDenialLeavesCameraUntouched() {
        val r = Rig()
        r.owner.observeCameraSource("raw-solo")
        check(r.permission.requests.isEmpty())
        check(r.runtime.sessions.isEmpty())
        r.controls.start()
        r.controls.start()
        check(r.permission.requests.size == 1)
        check(r.owner.status().permission == ProductAudioPermissionState.Pending)
        r.permission.answer(false)
        val status = r.controls.status()
        check(!status.enabled && status.requested)
        check(status.permission == ProductAudioPermissionState.Denied)
        check(status.sourceObservation == "raw-solo")
        check(r.runtime.sessions.isEmpty())
    }
    private fun duplicateStartAndSourceChangeKeepOneCapture() {
        val r = Rig(); r.grantedStart()
        repeat(3) { r.controls.start(); r.owner.observeCameraSource("source-$it") }
        check(r.runtime.sessions.size == 1)
        check(r.runtime.sessions.single().starts == 1)
        check(r.controls.status().enabled)
    }
    private fun fanoutUsesOneImmutableObservationAndDisconnectKeepsCapture() {
        val r = Rig(); val a = Sink(); val b = Sink()
        r.owner.registerSink("texture", a); r.owner.registerSink("spatial", b)
        r.grantedStart(); val session = r.runtime.sessions.single(); session.pcm()
        check(a.values.size == 1 && b.values.size == 1)
        check(a.values.single() === b.values.single())
        check(a.values.single().capturedAtNanos == 1_000_000_000L)
        check(a.values.single().generation == r.owner.status().generation)
        r.owner.unregisterSink("texture"); session.pcm(1_010_000_000L)
        check(a.values.size == 1 && b.values.size == 2)
        check(a.removed.isNotEmpty())
        check(r.runtime.sessions.size == 1 && session.stops == 0)
    }
    private fun stopRejectsLatePcmAndWaitsForCleanupBeforeRestart() {
        val r = Rig(); val sink = Sink(); r.owner.registerSink("texture", sink)
        r.grantedStart(); val old = r.runtime.sessions.single(); old.pcm()
        val generation = r.owner.status().generation
        r.controls.stop()
        check(sink.removed.last() > generation)
        check(r.controls.status().pendingCleanup && !r.controls.status().enabled)
        old.pcm(); check(sink.values.size == 1)
        r.controls.start(); check(r.runtime.sessions.size == 1)
        old.closed!!(true)
        check(r.runtime.sessions.size == 2 && r.controls.status().enabled)
        old.pcm(); old.receiver.onFailure(); old.closed!!(true)
        check(r.controls.status().enabled)
        check(r.runtime.sessions[1].stops == 0)
        r.runtime.sessions[1].pcm(2_000_000_000L)
        check(sink.values.size == 2)
        check(sink.values.last().sequence == 1L)
    }
    private fun lifecycleStopsSynchronouslyAndRejectsOldPermission() {
        for (action in listOf<(ProductAudioFeatureOwner) -> Unit>(
            { it.onPause() }, { it.onFocusChanged(false) }, { it.onDestroy() }
        )) {
            val r = Rig(); val sink = Sink(); r.owner.registerSink("sink", sink)
            r.grantedStart(); action(r.owner)
            check(!r.owner.status().enabled && !r.owner.status().requested)
            check(sink.removed.isNotEmpty() && r.runtime.sessions.single().stops == 1)
            r.runtime.sessions.single().pcm(); check(sink.values.isEmpty())
        }
        val r = Rig(); r.controls.start(); r.owner.onPause(); r.permission.answer(true)
        check(r.runtime.sessions.isEmpty())
        r.owner.onResume(); check(r.runtime.sessions.isEmpty())
        r.owner.onDestroy(); r.controls.start(); check(r.runtime.sessions.isEmpty())
    }
    private fun failureRetiresSessionAndReportsTruth() {
        val r = Rig(); r.grantedStart(); val old = r.runtime.sessions.single()
        old.receiver.onFailure()
        check(!r.owner.status().enabled && r.owner.status().pendingCleanup)
        check(r.owner.status().failure == "capture-failed")
        old.closed!!(true)
        check(!r.owner.status().pendingCleanup && !r.owner.status().requested)
    }
    private fun cleanupFailureNeverAdmitsRestart() {
        val r = Rig(); r.grantedStart(); val old = r.runtime.sessions.single()
        r.controls.stop(); r.controls.start(); old.closed!!(false)
        check(r.owner.status().pendingCleanup)
        check(r.owner.status().failure == "cleanup-failed")
        check(!r.owner.status().enabled)
        r.controls.start(); old.closed!!(true)
        check(r.runtime.sessions.size == 1)
    }
    private fun originalCaptureTimeNeverUsesReadCompletionAsFreshness() {
        check(ProductAudioCaptureTime.firstSample(0, 480, 1_010_000_000L, 48_000, 1_020_000_000L) == 1_000_000_000L)
        check(ProductAudioCaptureTime.firstSample(0, 48_000, 2_000_000_000L, 48_000, 1_100_000_000L) == null)
        check(ProductAudioCaptureTime.firstSample(0, 48_000, 2_000_000_000L, 48_000, 2_010_000_000L) == null)
        check(ProductAudioCaptureTime.firstSample(480, 0, 1_000_000_000L, 48_000, 1_005_000_000L) == null)
        check(ProductAudioCaptureTime.firstSample(0, 0, -1L, 48_000, 1_005_000_000L) == null)
        check(ProductAudioCaptureTime.firstSample(0, 0, 1_000_000_000L, 48_000, 1_250_000_000L) == 1_000_000_000L)
        check(ProductAudioCaptureTime.firstSample(0, 0, 1_000_000_000L, 48_000, 1_250_000_001L) == null)
    }
    private fun controlsValidateSettingsAndExposeUnknownNativeState() {
        val r = Rig()
        check(r.controls.status().effectiveNativeLut == null)
        check(!r.controls.updateSettings(StyleSettings(strength = Float.NaN)))
        check(r.controls.updateSettings(StyleSettings(strength = 0.5f)))
        check(r.controls.status().settings.strength == 0.5f)
        check(ProductAudioControls.START_ACTION == "audio.start")
        check(ProductAudioControls.STOP_ACTION == "audio.stop")
        check(ProductAudioControls.STATUS_ACTION == "audio.status")
    }
}
