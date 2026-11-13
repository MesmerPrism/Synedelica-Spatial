// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored for Synedelica Spatial; no recovered application source.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import io.github.mesmerprism.synedelica.core.AudioSnapshot
import io.github.mesmerprism.synedelica.core.StyleSettings

/** Pure host contract; no Android, physical GPU, microphone or compositor qualification. */
object ProductAudioFeatureOwnerContractTest {
    private class Permission : ProductAudioPermission {
        var granted = false
        var reads = 0
        val requests = mutableListOf<(Boolean) -> Unit>()
        override fun isGranted(): Boolean { reads++; return granted }
        override fun request(result: (Boolean) -> Unit) { requests += result }
        fun answer(value: Boolean) { granted = value; requests.last()(value) }
    }
    private class Runtime : ProductAudioCaptureRuntime {
        val sessions = mutableListOf<Session>()
        val generations = mutableListOf<Long>()
        override fun create(generation: Long, receiver: ProductAudioCaptureReceiver): ProductAudioCaptureSession {
            generations += generation
            return Session(receiver).also { sessions += it }
        }
        class Session(val receiver: ProductAudioCaptureReceiver) : ProductAudioCaptureSession {
            var starts = 0
            var stops = 0
            var closed: ((ProductAudioCaptureRetirement) -> Unit)? = null
            override fun start() { starts++; receiver.onStarted() }
            override fun stop(onClosed: (ProductAudioCaptureRetirement) -> Unit) { stops++; closed = onClosed }
            fun pcm(at: Long = 1_000_000_000L) = receiver.onPcm(ShortArray(256) { 1_000 }, 256, at)
        }
    }
    private class Sink(val automatic: Boolean = true) : ProductAudioSink {
        val values = mutableListOf<AudioSnapshot>()
        val retired = mutableListOf<Long>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        override fun onAudio(snapshot: AudioSnapshot, settings: StyleSettings) { values += snapshot }
        override fun retireAudio(generation: Long, onRetired: (Boolean) -> Unit) {
            retired += generation
            acknowledgements += onRetired
            if (automatic) onRetired(true)
        }
    }
    private class Rig(admission: ProductAudioAdmissionGate = ProductAudioAdmissionGate()) {
        val permission = Permission()
        val runtime = Runtime()
        var nativeReads = 0
        val owner = ProductAudioFeatureOwner(permission, runtime,
            nativeReadback = { nativeReads++; true }, admission = admission)
        val controls = ProductAudioControls(owner)
        init { owner.onResume(); owner.onFocusChanged(true) }
        fun grantedStart() { permission.granted = true; controls.start() }
    }
    @JvmStatic fun main(args: Array<String>) {
        val cases = listOf(
            "statusIsPureAfterPermissionRevocation" to ::statusIsPureAfterPermissionRevocation,
            "permissionRequiresExplicitStartAndDenialLeavesCameraUntouched" to ::permissionRequiresExplicitStartAndDenialLeavesCameraUntouched,
            "duplicateStartAndSourceChangeKeepOneCapture" to ::duplicateStartAndSourceChangeKeepOneCapture,
            "fanoutUsesOneImmutableObservationAndDisconnectKeepsCapture" to ::fanoutUsesOneImmutableObservationAndDisconnectKeepsCapture,
            "retirementNeedsCaptureAndPhysicalSinkAcknowledgements" to ::retirementNeedsCaptureAndPhysicalSinkAcknowledgements,
            "releaseAloneDoesNotProveWorkerTerminationOrDrain" to ::releaseAloneDoesNotProveWorkerTerminationOrDrain,
            "failedAcknowledgementsAreSticky" to ::failedAcknowledgementsAreSticky,
            "lifecycleStopsSynchronouslyAndRejectsOldPermission" to ::lifecycleStopsSynchronouslyAndRejectsOldPermission,
            "failureRetiresSessionAndReportsTruth" to ::failureRetiresSessionAndReportsTruth,
            "noAudioRetirementIsImmediatelyCompleteAndReadOnly" to ::noAudioRetirementIsImmediatelyCompleteAndReadOnly,
            "detachedSinkRetirementJoinsTheSameWhole" to ::detachedSinkRetirementJoinsTheSameWhole,
            "recreatedActivityCannotOverlapPriorRuntime" to ::recreatedActivityCannotOverlapPriorRuntime,
            "detachedSinkBeforeCaptureWaitsForAck" to ::detachedSinkBeforeCaptureWaitsForAck,
            "permissionGrantWaitsForDetachedPhysicalSink" to ::permissionGrantWaitsForDetachedPhysicalSink,
            "batchedSinkAckDuringPermissionGrantResumesRequestedStart" to ::batchedSinkAckDuringPermissionGrantResumesRequestedStart,
            "completedTicketCannotWedgePermissionRestart" to ::completedTicketCannotWedgePermissionRestart,
            "reentrantThrowCannotDropReplacementSink" to ::reentrantThrowCannotDropReplacementSink,
            "sinkOnlyRetirementBlocksRecreatedOwner" to ::sinkOnlyRetirementBlocksRecreatedOwner,
            "sinkOnlyFailureIsSticky" to ::sinkOnlyFailureIsSticky,
            "admissionGateRejectsStaleAndReplayedLeases" to ::admissionGateRejectsStaleAndReplayedLeases,
            "originalCaptureTimeNeverUsesReadCompletionAsFreshness" to ::originalCaptureTimeNeverUsesReadCompletionAsFreshness,
            "controlsValidateSettingsAndExposeUnknownNativeState" to ::controlsValidateSettingsAndExposeUnknownNativeState,
        )
        val failures = mutableListOf<String>()
        for ((name, run) in cases) {
            val failure = runCatching { run() }.exceptionOrNull()
            if (failure != null) {
                failures += name
                System.err.println("FAILED: $name")
                failure.printStackTrace()
            }
        }
        check(failures.isEmpty()) { "Failed contract cases: ${failures.joinToString()}" }
        println("ProductAudioFeatureOwnerContractTest: ${cases.size} cases passed")
    }
    private fun statusIsPureAfterPermissionRevocation() {
        val r = Rig(); r.grantedStart()
        val before = r.owner.status()
        val reads = r.permission.reads
        r.permission.granted = false
        repeat(3) { check(r.owner.status() == before) { "status must only observe cached state" } }
        check(r.permission.reads == reads && r.nativeReads == 0)
        check(r.runtime.sessions.single().stops == 0)
        r.owner.reconcilePermission()
        check(r.owner.status().permission == ProductAudioPermissionState.Denied)
        check(!r.owner.status().enabled && r.runtime.sessions.single().stops == 1)
        r.runtime.sessions.single().closed!!(ProductAudioCaptureRetirement(true, true, true))
    }
    private fun permissionRequiresExplicitStartAndDenialLeavesCameraUntouched() {
        val r = Rig(); r.owner.observeCameraSource("raw-solo")
        check(r.permission.requests.isEmpty() && r.runtime.sessions.isEmpty())
        r.controls.start(); r.controls.start()
        check(r.permission.requests.size == 1)
        check(r.owner.status().permission == ProductAudioPermissionState.Pending)
        r.permission.answer(false)
        val status = r.controls.status()
        check(!status.enabled && status.requested && status.permission == ProductAudioPermissionState.Denied)
        check(status.sourceObservation == "raw-solo" && r.runtime.sessions.isEmpty())
        r.controls.stop()
    }
    private fun duplicateStartAndSourceChangeKeepOneCapture() {
        val r = Rig(); r.grantedStart()
        repeat(3) { r.controls.start(); r.owner.observeCameraSource("source-$it") }
        check(r.runtime.sessions.size == 1 && r.runtime.sessions.single().starts == 1)
        check(r.controls.status().enabled)
    }
    private fun fanoutUsesOneImmutableObservationAndDisconnectKeepsCapture() {
        val r = Rig(); val a = Sink(); val b = Sink()
        r.owner.registerSink("texture", a); r.owner.registerSink("spatial", b)
        r.grantedStart(); val session = r.runtime.sessions.single(); session.pcm()
        check(a.values.size == 1 && b.values.size == 1 && a.values.single() === b.values.single())
        check(a.values.single().capturedAtNanos == 1_000_000_000L)
        check(a.values.single().generation == r.owner.status().generation)
        check(a.values.single().sequence == 1L)
        r.owner.unregisterSink("texture"); session.pcm(1_010_000_000L)
        check(a.values.size == 1 && b.values.size == 2 && a.retired.isNotEmpty())
        check(r.runtime.sessions.size == 1 && session.stops == 0)
    }
    private fun retirementNeedsCaptureAndPhysicalSinkAcknowledgements() {
        for (microphoneFirst in listOf(true, false)) {
            val r = Rig(); val sink = Sink(false); r.owner.registerSink("sink", sink)
            r.grantedStart(); val old = r.runtime.sessions.single(); old.pcm()
            val generation = r.owner.status().generation
            val ticket = r.owner.retire()
            check(ticket.contractVersion == 2 && ticket.generation >= generation)
            check(r.owner.retire() === ticket)
            check(!r.owner.status().enabled && r.owner.status().pendingCleanup)
            check(old.stops == 1 && sink.retired.size == 1)
            old.pcm(); check(sink.values.size == 1)
            r.controls.start()
            if (microphoneFirst) old.closed!!(ProductAudioCaptureRetirement(true, true, true)) else sink.acknowledgements.single()(true)
            check(!ticket.completion.toCompletableFuture().isDone)
            check(r.runtime.sessions.size == 1 && r.owner.status().pendingCleanup)
            if (microphoneFirst) sink.acknowledgements.single()(true) else old.closed!!(ProductAudioCaptureRetirement(true, true, true))
            val result = ticket.completion.toCompletableFuture().join()
            check(result.captureReleased && result.sinksRetired && result.successful && result.failure == null)
            check(r.runtime.sessions.size == 2 && r.owner.status().enabled)
            old.closed!!(ProductAudioCaptureRetirement(false, false, false, "release-failed")); sink.acknowledgements.single()(false); old.receiver.onFailure(); old.pcm()
            check(r.owner.status().enabled && r.runtime.sessions[1].stops == 0)
            r.runtime.sessions[1].pcm(2_000_000_000L)
            check(sink.values.last().sequence == 1L)
        }
    }
    private fun releaseAloneDoesNotProveWorkerTerminationOrDrain() {
        val r = Rig(); r.grantedStart(); val session = r.runtime.sessions.single()
        val ticket = r.owner.retire(); r.controls.start()
        session.closed!!(ProductAudioCaptureRetirement(true, false, false))
        check(!ticket.completion.toCompletableFuture().isDone && r.runtime.sessions.size == 1)
        session.closed!!(ProductAudioCaptureRetirement(true, true, false))
        check(!ticket.completion.toCompletableFuture().isDone && r.runtime.sessions.size == 1)
        session.closed!!(ProductAudioCaptureRetirement(true, true, true))
        val result = ticket.completion.toCompletableFuture().join()
        check(result.captureReleased && result.workerTerminated && result.callbacksDrained && result.successful)
        check(r.runtime.sessions.size == 2)
    }
    private fun failedAcknowledgementsAreSticky() {
        for (captureFails in listOf(true, false)) {
            val gate = ProductAudioAdmissionGate(); val r = Rig(gate); val sink = Sink(false)
            r.owner.registerSink("sink", sink); r.grantedStart()
            val session = r.runtime.sessions.single(); val ticket = r.owner.retire()
            if (captureFails) session.closed!!(ProductAudioCaptureRetirement(false, false, false, "release-failed")) else sink.acknowledgements.single()(false)
            if (captureFails) sink.acknowledgements.single()(true) else session.closed!!(ProductAudioCaptureRetirement(true, true, true))
            val result = ticket.completion.toCompletableFuture().join()
            check(!result.successful && result.failure != null)
            check(result.captureReleased == !captureFails && result.sinksRetired == captureFails)
            check(gate.failed && gate.busy && r.owner.status().pendingCleanup)
            session.closed!!(ProductAudioCaptureRetirement(true, true, true)); sink.acknowledgements.single()(true); r.controls.start()
            check(r.owner.retire() === ticket && r.runtime.sessions.size == 1)
            check(ticket.completion.toCompletableFuture().join() == result)
        }
    }
    private fun lifecycleStopsSynchronouslyAndRejectsOldPermission() {
        for (action in listOf<(ProductAudioFeatureOwner) -> Unit>(
            { it.onPause() }, { it.onFocusChanged(false) }, { it.onDestroy() }
        )) {
            val r = Rig(); val sink = Sink(false); r.owner.registerSink("sink", sink)
            r.grantedStart(); action(r.owner)
            val ticket = r.owner.retire()
            check(!r.owner.status().enabled && !r.owner.status().requested)
            check(sink.retired.size == 1 && r.runtime.sessions.single().stops == 1)
            r.runtime.sessions.single().pcm(); check(sink.values.isEmpty())
            r.runtime.sessions.single().closed!!(ProductAudioCaptureRetirement(true, true, true)); sink.acknowledgements.single()(true)
            check(ticket.completion.toCompletableFuture().join().successful)
        }
        val r = Rig(); r.controls.start(); r.owner.onPause(); r.permission.answer(true)
        check(r.runtime.sessions.isEmpty())
        r.owner.onResume(); check(r.runtime.sessions.isEmpty())
        r.owner.onDestroy(); r.controls.start(); check(r.runtime.sessions.isEmpty())
        val noAudio = Rig(); noAudio.controls.stop(); noAudio.owner.onDestroy()
        check(noAudio.owner.status().capture == ProductAudioPhase.Destroyed) { "completed retirement must report destroyed lifecycle" }
    }
    private fun failureRetiresSessionAndReportsTruth() {
        val r = Rig(); r.grantedStart(); val old = r.runtime.sessions.single()
        old.receiver.onFailure()
        check(!r.owner.status().enabled && r.owner.status().pendingCleanup)
        check(r.owner.status().failure == "capture-failed")
        old.closed!!(ProductAudioCaptureRetirement(true, true, true))
        check(!r.owner.status().pendingCleanup && !r.owner.status().requested)
    }
    private fun noAudioRetirementIsImmediatelyCompleteAndReadOnly() {
        val r = Rig(); val ticket = r.owner.retire()
        check(ticket.contractVersion == 2)
        check(ticket.completion.toCompletableFuture().join().successful)
        check(!r.owner.status().pendingCleanup && r.runtime.sessions.isEmpty())
        val active = Rig(); active.grantedStart(); val pending = active.owner.retire()
        val callerCopy = pending.completion.toCompletableFuture()
        callerCopy.complete(ProductAudioRetirementResult(true, true, true, true, null))
        check(!pending.completion.toCompletableFuture().isDone)
        check(active.owner.status().pendingCleanup)
        active.runtime.sessions.single().closed!!(ProductAudioCaptureRetirement(true, true, true))
        check(pending.completion.toCompletableFuture().join().successful)
    }
    /** Test participant models the integration rule: joins happen before terminal/fence clear. */
    private class Whole(ticket: ProductAudioRetirementTicket) {
        var videoRetired = false
        private var audioRetired = false
        var terminal = false
            private set
        var fenceCleared = false
            private set
        init { ticket.completion.thenAccept { audioRetired = it.successful; advance() } }
        fun videoAck() { videoRetired = true; advance() }
        private fun advance() { if (videoRetired && audioRetired) { terminal = true; fenceCleared = true } }
    }
    private fun detachedSinkRetirementJoinsTheSameWhole() {
        val r = Rig(); val detached = Sink(false); val replacement = Sink(false)
        r.owner.registerSink("sink", detached); r.grantedStart()
        r.owner.registerSink("sink", replacement)
        check(detached.retired.size == 1 && r.runtime.sessions.single().stops == 0)
        val ticket = r.owner.retire(); val whole = Whole(ticket); whole.videoAck()
        r.runtime.sessions.single().closed!!(ProductAudioCaptureRetirement(true, true, true)); replacement.acknowledgements.single()(true)
        check(!whole.terminal && !whole.fenceCleared && !ticket.completion.toCompletableFuture().isDone)
        detached.acknowledgements.single()(true)
        check(whole.terminal && whole.fenceCleared)
    }
    private fun recreatedActivityCannotOverlapPriorRuntime() {
        val p1 = Permission().also { it.granted = true }; val p2 = Permission().also { it.granted = true }
        val rt1 = Runtime(); val rt2 = Runtime(); val sink = Sink(false)
        val old = ProductAudioFeatureOwner(p1, rt1); old.onResume(); old.onFocusChanged(true)
        old.registerSink("sink", sink); old.start(); old.onDestroy()
        val next = ProductAudioFeatureOwner(p2, rt2); next.onResume(); next.onFocusChanged(true); next.start()
        check(rt2.sessions.isEmpty())
        rt1.sessions.single().closed!!(ProductAudioCaptureRetirement(true, true, true)); next.start(); check(rt2.sessions.isEmpty())
        sink.acknowledgements.single()(true); next.start(); check(rt2.sessions.size == 1)
        check(rt2.generations.single() > rt1.generations.single())
        next.stop(); rt2.sessions.single().closed!!(ProductAudioCaptureRetirement(true, true, true))
    }
    private fun detachedSinkBeforeCaptureWaitsForAck() {
        val r = Rig(); val sink = Sink(false)
        r.owner.registerSink("sink", sink); r.owner.unregisterSink("sink"); r.grantedStart()
        check(r.runtime.sessions.isEmpty() && r.owner.status().pendingCleanup)
        sink.acknowledgements.single()(true)
        check(r.runtime.sessions.size == 1 && r.owner.status().enabled)
    }
    private fun permissionGrantWaitsForDetachedPhysicalSink() {
        val r = Rig(); val detached = Sink(false)
        r.owner.registerSink("sink", detached)
        r.controls.start(); check(r.permission.requests.size == 1)
        r.owner.unregisterSink("sink"); r.permission.answer(true)
        check(r.runtime.sessions.isEmpty()) { "permission result must wait for detached physical sink ACK" }
        check(r.owner.status().pendingCleanup && r.owner.status().requested)
        detached.acknowledgements.single()(true)
        check(r.runtime.sessions.size == 1 && r.owner.status().enabled)
    }
    private fun batchedSinkAckDuringPermissionGrantResumesRequestedStart() {
        val r = Rig(); val detached = Sink(false)
        val batch = object : ProductAudioSink {
            override fun onAudio(snapshot: AudioSnapshot, settings: StyleSettings) = Unit
            override fun retireAudio(generation: Long, onRetired: (Boolean) -> Unit) {
                detached.acknowledgements.single()(true)
                onRetired(true)
            }
        }
        r.owner.registerSink("sink", detached); r.controls.start()
        r.owner.registerSink("sink", batch); r.permission.answer(true)
        check(r.runtime.sessions.size == 1 && r.owner.status().enabled) {
            "synchronous batched physical ACK must preserve requested Start"
        }
    }
    private fun completedTicketCannotWedgePermissionRestart() {
        val r = Rig(); r.controls.stop(); r.controls.start()
        check(r.permission.requests.size == 1)
        val stale = r.permission.requests.single()
        r.controls.stop(); r.controls.start()
        check(r.permission.requests.size == 2) { "completed ticket must not wedge a new permission request" }
        r.permission.granted = true; stale(true); check(r.runtime.sessions.isEmpty())
        r.permission.answer(true); check(r.runtime.sessions.size == 1)
    }
    private fun reentrantThrowCannotDropReplacementSink() {
        val r = Rig(); val replacement = Sink(false)
        val original = object : ProductAudioSink {
            override fun onAudio(snapshot: AudioSnapshot, settings: StyleSettings) {
                r.owner.registerSink("sink", replacement)
                error("original sink failed after replacing its registration")
            }
            override fun retireAudio(generation: Long, onRetired: (Boolean) -> Unit) { onRetired(true) }
        }
        r.owner.registerSink("sink", original); r.grantedStart()
        val session = r.runtime.sessions.single(); session.pcm()
        val ticket = r.owner.retire()
        check(replacement.retired.size == 1) { "throwing old sink must not drop reentrant replacement" }
        session.closed!!(ProductAudioCaptureRetirement(true, true, true))
        check(!ticket.completion.toCompletableFuture().isDone)
        replacement.acknowledgements.single()(true)
        check(ticket.completion.toCompletableFuture().join().successful)
    }
    private fun sinkOnlyRetirementBlocksRecreatedOwner() {
        val gate = ProductAudioAdmissionGate(); val old = Rig(gate); val sink = Sink(false)
        old.owner.registerSink("sink", sink)
        old.owner.unregisterSink("sink")
        old.owner.onDestroy(); val ticket = old.owner.retire()
        check(old.runtime.sessions.isEmpty() && gate.busy)
        val next = Rig(gate); next.grantedStart()
        check(next.runtime.sessions.isEmpty() && !ticket.completion.toCompletableFuture().isDone)
        val rejected = Sink()
        check(runCatching { next.owner.registerSink("busy", rejected) }.isFailure)
        check(rejected.retired.isEmpty())
        sink.acknowledgements.single()(true)
        check(ticket.completion.toCompletableFuture().join().successful)
        next.controls.start(); check(next.runtime.sessions.size == 1)
        next.controls.stop(); next.runtime.sessions.single().closed!!(ProductAudioCaptureRetirement(true, true, true))
    }
    private fun sinkOnlyFailureIsSticky() {
        val gate = ProductAudioAdmissionGate(); val old = Rig(gate); val sink = Sink(false)
        old.owner.registerSink("sink", sink); val ticket = old.owner.retire()
        sink.acknowledgements.single()(false)
        check(gate.busy && gate.failed && !ticket.completion.toCompletableFuture().join().successful)
        sink.acknowledgements.single()(true)
        val next = Rig(gate); next.grantedStart(); check(next.runtime.sessions.isEmpty())
        check(runCatching { next.owner.registerSink("failed", Sink()) }.isFailure)
        check(!ticket.completion.toCompletableFuture().join().successful)
    }
    private fun admissionGateRejectsStaleAndReplayedLeases() {
        val gate = ProductAudioAdmissionGate(); val other = ProductAudioAdmissionGate()
        val old = gate.tryAcquire()!!; check(gate.busy && !gate.failed && gate.tryAcquire() == null)
        val foreign = other.tryAcquire()!!; check(foreign.generation > old.generation)
        gate.complete(foreign, false); check(!gate.failed && gate.busy)
        gate.complete(old, true); val next = gate.tryAcquire()!!
        check(next.generation > foreign.generation)
        gate.complete(old, false); check(gate.busy && !gate.failed)
        gate.complete(next, false); gate.complete(next, true)
        check(gate.failed && gate.busy && gate.tryAcquire() == null)
        other.complete(foreign, true)
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
        val r = Rig(); check(r.controls.status().effectiveNativeLut == null && r.nativeReads == 0)
        r.owner.observeNativeLut(0L, true); check(r.controls.status().effectiveNativeLut == null)
        r.grantedStart(); val oldGeneration = r.owner.status().generation
        r.owner.observeNativeLut(oldGeneration, false); check(r.controls.status().effectiveNativeLut == false)
        r.owner.observeNativeLut(oldGeneration, true); check(r.controls.status().effectiveNativeLut == true)
        r.owner.retire(); check(r.controls.status().effectiveNativeLut == null)
        r.owner.observeNativeLut(oldGeneration, true); check(r.controls.status().effectiveNativeLut == null)
        r.controls.start(); r.runtime.sessions.single().closed!!(ProductAudioCaptureRetirement(true, true, true))
        val currentGeneration = r.owner.status().generation
        check(currentGeneration > oldGeneration && r.controls.status().effectiveNativeLut == null)
        r.owner.observeNativeLut(oldGeneration, true); check(r.controls.status().effectiveNativeLut == null)
        r.owner.observeNativeLut(currentGeneration, true); check(r.controls.status().effectiveNativeLut == true)
        r.owner.observeNativeLut(currentGeneration, null); check(r.controls.status().effectiveNativeLut == null)
        check(!r.controls.updateSettings(StyleSettings(strength = Float.NaN)))
        check(r.controls.updateSettings(StyleSettings(strength = 0.5f)))
        check(r.controls.status().settings.strength == 0.5f)
        check(ProductAudioControls.START_ACTION == "audio.start")
        check(ProductAudioControls.STOP_ACTION == "audio.stop")
        check(ProductAudioControls.STATUS_ACTION == "audio.status")
    }
}
