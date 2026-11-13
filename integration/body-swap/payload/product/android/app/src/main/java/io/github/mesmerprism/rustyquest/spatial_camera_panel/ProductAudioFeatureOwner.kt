// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored for Synedelica Spatial; no recovered application source.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import io.github.mesmerprism.synedelica.core.AudioAnalyzer
import io.github.mesmerprism.synedelica.core.AudioSnapshot
import io.github.mesmerprism.synedelica.core.StyleSettings
import java.util.concurrent.CompletableFuture

/** Bound to the actual foreground Activity's permission launcher, never a global facade. */
interface ProductAudioPermission {
    fun isGranted(): Boolean
    /** Explicit Start only. Host marshals launcher to main and rechecks cancellation there. */
    fun request(result: (Boolean) -> Unit)
}
interface ProductAudioCaptureReceiver {
    fun onStarted()
    /** Borrowed PCM; consumed before return and never retained. Original BOOTTIME first-frame estimate. */
    fun onPcm(pcm: ShortArray, count: Int, capturedAtNanos: Long)
    fun onFailure()
}
interface ProductAudioCaptureSession {
    fun start()
    /**
     * Synchronously closes delivery, then reports actual release, worker termination and callback drain.
     * Incomplete evidence with no failure stays pending; explicit failure is terminal and sticky.
     * Must handle stop before start, never join a worker on the caller thread, and never throw.
     */
    fun stop(onClosed: (ProductAudioCaptureRetirement) -> Unit)
}
interface ProductAudioCaptureRuntime {
    /** No capture or receiver callbacks before start(). A throwing create owns no resources. */
    fun create(generation: Long, receiver: ProductAudioCaptureReceiver): ProductAudioCaptureSession
}
interface ProductAudioSink {
    /** Same immutable snapshot goes to each registered consumer; never rebase its metadata. */
    fun onAudio(snapshot: AudioSnapshot, settings: StyleSettings)
    /**
     * V2: install a synchronous publication barrier, then ACK only physical owning-resource/fence
     * retirement (or proven never-created). Scene/GPU work runs on its owning thread. No ACK means
     * pending indefinitely; false is terminal failure and can never authorize a replacement.
     */
    fun retireAudio(generation: Long, onRetired: (Boolean) -> Unit)
}

enum class ProductAudioPermissionState { Unknown, Pending, Granted, Denied }
enum class ProductAudioPhase { Idle, PermissionPending, Starting, Capturing, Stopping, CleanupFailed, Failed, Destroyed }
data class ProductAudioStatus(
    val requested: Boolean,
    val enabled: Boolean,
    val permission: ProductAudioPermissionState,
    val capture: ProductAudioPhase,
    val pendingCleanup: Boolean,
    val generation: Long,
    val sourceObservation: String?,
    val settings: StyleSettings,
    /** null means unknown, including when no native allocation/submission endpoint exists. */
    val effectiveNativeLut: Boolean?,
    val failure: String?,
)

/**
 * One foreground capture/analyzer shared by texture and Scene consumers. The process admission
 * survives Activity recreation and remains held through the complete physical retirement ticket.
 * The embedding carrier joins that exact ticket into its existing Whole before terminal/fence clear.
 */
class ProductAudioFeatureOwner(
    private val permission: ProductAudioPermission,
    private val runtime: ProductAudioCaptureRuntime,
    @Suppress("UNUSED_PARAMETER") nativeReadback: () -> Boolean? = { null },
    private val admission: ProductAudioAdmissionGate = ProductAudioProcessAdmission.appOwner,
) {
    private val monitor = Any()
    private val completions = mutableListOf<Pair<CompletableFuture<ProductAudioRetirementResult>, ProductAudioRetirementResult>>()
    private fun <T> locked(action: () -> T): T {
        try { return synchronized(monitor) { action() } }
        finally { if (!Thread.holdsLock(monitor)) publishCompletions() }
    }
    private fun publishCompletions() {
        while (true) {
            val ready = synchronized(monitor) {
                if (completions.isEmpty()) return
                completions.toList().also { completions.clear() }
            }
            for ((future, result) in ready) future.complete(result)
        }
    }
    private val analyzer = AudioAnalyzer(windowSize = 256)
    private val sinks = linkedMapOf<String, ProductAudioSink>()
    private val sinkRetirements = mutableListOf<SinkRetirement>()
    private var generation = 0L
    private var permissionEpoch = 0L
    private var resumed = false
    private var focused = false
    private var destroyed = false
    private var requested = false
    private var permissionState = ProductAudioPermissionState.Unknown
    private var phase = ProductAudioPhase.Idle
    private var settings = StyleSettings()
    private var source: String? = null
    private var nativeLut: Boolean? = null
    private var failure: String? = null
    private var active: ProductAudioCaptureSession? = null
    private var lease: ProductAudioAdmissionGate.Lease? = null
    private var retirement: Retirement? = null

    private class SinkRetirement(val sink: ProductAudioSink, val generation: Long) {
        var done = false
        var retired = false
    }
    private class Retirement(val ticket: ProductAudioRetirementTicket,
        val completion: CompletableFuture<ProductAudioRetirementResult>,
        val lease: ProductAudioAdmissionGate.Lease?, val sinks: MutableList<SinkRetirement>,
        var capture: ProductAudioCaptureRetirement, var captureFinal: Boolean) {
        var issuing = true
        var finished = false
        var failure: String? = null
    }

    fun start(): ProductAudioStatus = locked {
        if (destroyed) return@locked status()
        if (!resumed || !focused) { failure = "foreground-required"; return@locked status() }
        if (active != null) { reconcilePermission(); return@locked status() }
        // A sink detached before the first capture may still own physical resources.
        // Its ACK joins the same transaction before any capture can be created.
        if (!cleanupPending() && sinkRetirements.any { !it.done }) {
            retirement = null
            retireInternal(null)
        }
        requested = true
        if (!cleanupPending() && phase != ProductAudioPhase.PermissionPending) beginStart()
        return@locked status()
    }
    fun stop(): ProductAudioStatus = locked { retire(); status() }
    fun onResume(): Unit = locked { if (!destroyed) { resumed = true; reconcilePermission() } }
    fun onPause(): Unit = locked { resumed = false; retire(); Unit }
    fun onFocusChanged(hasFocus: Boolean): Unit = locked {
        focused = hasFocus
        if (!hasFocus) retire() else reconcilePermission()
    }
    fun onDestroy(): Unit = locked { destroyed = true; resumed = false; retire(); Unit }

    fun observeCameraSource(value: String?): Unit = locked { source = value }
    /** Explicit evidence for this live generation only; stale queued native observations are ignored. */
    fun observeNativeLut(observedGeneration: Long, value: Boolean?): Unit = locked {
        if (observedGeneration == generation && active != null && retirement == null &&
            phase == ProductAudioPhase.Capturing && resumed && focused && !destroyed) nativeLut = value
    }
    /** Mutating permission reconciliation is deliberately separate from pure status observation. */
    fun reconcilePermission(): Unit = locked {
        if (permissionState == ProductAudioPermissionState.Granted && !hasPermission()) {
            permissionState = ProductAudioPermissionState.Denied
            retireInternal("permission-revoked")
        }
    }

    fun registerSink(id: String, sink: ProductAudioSink): Unit = locked {
        check(!destroyed) { "Audio owner destroyed" }; require(id.isNotBlank())
        val old = sinks[id]
        if (old === sink) return@locked
        check(retirement?.failure == null && !admission.failed) { "Audio retirement failed" }
        // Adoption itself can transfer physical audio resources. Reserve before adopting,
        // including the sink-only/no-capture case, so recreated owners cannot overlap.
        if (lease == null) {
            val acquired = checkNotNull(admission.tryAcquire()) { "Audio owner admission busy" }
            lease = acquired; generation = acquired.generation
        }
        sinks[id] = sink
        if (retirement?.finished == true && retirement?.failure == null) retirement = null
        if (old != null && sinks.values.none { it === old }) retireSink(old)
        // Resources attached during an outstanding retirement belong to that same cohort.
        if (retirement != null && !retirement!!.finished) retireSink(sink)
    }
    fun unregisterSink(id: String): Unit = locked {
        val old = sinks.remove(id) ?: return@locked
        if (sinks.values.none { it === old }) retireSink(old)
    }
    fun updateSettings(value: StyleSettings): Boolean = locked {
        if (!value.strength.isFinite() || value.strength !in 0f..1f ||
            !value.quietStrength.isFinite() || value.quietStrength !in 0f..1f ||
            !value.clockSpeed.isFinite() || value.clockSpeed !in 0f..10f) return@locked false
        settings = value; true
    }
    /** Pure cached observation: no permission, native, capture or sink callbacks. */
    fun status(): ProductAudioStatus = synchronized(monitor) { ProductAudioStatus(
        requested, phase == ProductAudioPhase.Capturing && resumed && focused && !destroyed,
        permissionState, phase, cleanupPending(), generation, source, settings, nativeLut, failure,
    ) }
    private fun cleanupPending(): Boolean = retirement?.let { !it.finished || it.failure != null } ?: false
    private fun hasPermission(): Boolean = try { permission.isGranted() } catch (_: Exception) { false }

    private fun beginStart() {
        if (!requested || destroyed || !resumed || !focused || cleanupPending() || active != null) return
        failure = null
        val epoch = ++permissionEpoch
        if (hasPermission()) {
            permissionState = ProductAudioPermissionState.Granted
            beginCapture()
        } else {
            permissionState = ProductAudioPermissionState.Pending; phase = ProductAudioPhase.PermissionPending
            try { permission.request { granted -> permissionResult(epoch, granted) } }
            catch (_: Exception) {
                if (epoch == permissionEpoch) {
                    permissionState = ProductAudioPermissionState.Denied; requested = false
                    phase = ProductAudioPhase.Failed; failure = "permission-request-failed"
                }
            }
        }
    }
    private fun permissionResult(epoch: Long, granted: Boolean): Unit = locked {
        if (epoch != permissionEpoch || destroyed || !requested || !resumed || !focused ||
            phase != ProductAudioPhase.PermissionPending) return@locked
        permissionState = if (granted && hasPermission()) ProductAudioPermissionState.Granted else ProductAudioPermissionState.Denied
        if (permissionState == ProductAudioPermissionState.Granted) beginCapture() else phase = ProductAudioPhase.Idle
    }
    private fun beginCapture() {
        if (active != null || cleanupPending() || destroyed || !requested || !resumed || !focused) return
        // Permission completion can enter here without another explicit Start call.
        // Detached physical resources still have to finish before capture admission.
        if (sinkRetirements.any { !it.done }) {
            retirement = null
            retireInternal(null)
            requested = true
            // A current sink may synchronously ACK a batch containing the detached sink.
            // In that case there will be no later callback to resume this requested Start.
            if (!cleanupPending()) beginStart()
            return
        }
        if (!hasPermission()) { permissionState = ProductAudioPermissionState.Denied; phase = ProductAudioPhase.Idle; return }
        val acquired = lease ?: admission.tryAcquire()
        if (acquired == null) {
            phase = if (admission.failed) ProductAudioPhase.CleanupFailed else ProductAudioPhase.Idle
            failure = if (admission.failed) "cleanup-failed" else "admission-busy"
            return
        }
        lease = acquired; generation = acquired.generation; retirement = null; nativeLut = null
        analyzer.reset(); phase = ProductAudioPhase.Starting
        val token = generation
        val receiver = object : ProductAudioCaptureReceiver {
            override fun onStarted() = captureStarted(token)
            override fun onPcm(pcm: ShortArray, count: Int, capturedAtNanos: Long) = accept(token, pcm, count, capturedAtNanos)
            override fun onFailure() = captureFailed(token)
        }
        try {
            val session = runtime.create(token, receiver); active = session; session.start()
        } catch (_: Exception) { if (token == generation) retireInternal("capture-failed") }
    }
    private fun captureStarted(token: Long): Unit = locked {
        if (token != generation || active == null || !requested || !resumed || !focused || destroyed ||
            phase != ProductAudioPhase.Starting) return@locked
        if (!hasPermission()) { permissionState = ProductAudioPermissionState.Denied; retireInternal("permission-revoked") }
        else phase = ProductAudioPhase.Capturing
    }
    private fun captureFailed(token: Long): Unit = locked {
        if (token == generation && active != null) retireInternal("capture-failed")
    }
    private fun accept(token: Long, pcm: ShortArray, count: Int, at: Long): Unit = locked {
        if (token != generation || phase != ProductAudioPhase.Capturing || !requested || destroyed || !resumed || !focused) return@locked
        if (!hasPermission()) { permissionState = ProductAudioPermissionState.Denied; retireInternal("permission-revoked"); return@locked }
        val snapshots = try { analyzer.accept(pcm, count, token, at) } catch (_: Exception) {
            retireInternal("invalid-capture-input"); return@locked
        }
        for (snapshot in snapshots) for ((id, sink) in sinks.toList()) {
            if (token != generation || phase != ProductAudioPhase.Capturing) return@locked
            if (sinks[id] === sink) try { sink.onAudio(snapshot, settings) } catch (_: Exception) {
                // onAudio may reentrantly replace this registration before throwing.
                // Only remove the failing registration; its successor still owns resources.
                if (sinks[id] === sink) sinks.remove(id)
                if (sinks.values.none { it === sink }) retireSink(sink)
            }
        }
    }

    /** Synchronous publication close; one ticket joins capture plus every current/detached sink. */
    fun retire(): ProductAudioRetirementTicket = locked { retireInternal(null) }
    private fun retireInternal(reason: String?): ProductAudioRetirementTicket {
        requested = false; ++permissionEpoch
        nativeLut = null
        if (reason != null) failure = reason
        if (permissionState == ProductAudioPermissionState.Pending) permissionState = ProductAudioPermissionState.Unknown
        retirement?.let {
            if (it.finished) phase = if (it.failure != null) ProductAudioPhase.CleanupFailed
                else if (destroyed) ProductAudioPhase.Destroyed
                else if (failure != null) ProductAudioPhase.Failed else ProductAudioPhase.Idle
            return it.ticket
        }
        analyzer.reset()
        val session = active; active = null
        phase = ProductAudioPhase.Stopping
        val completion = CompletableFuture<ProductAudioRetirementResult>()
        val ticket = ProductAudioRetirementTicket(generation, completion)
        val transaction = Retirement(ticket, completion, lease, sinkRetirements.toMutableList(),
            ProductAudioCaptureRetirement(session == null, session == null, session == null), session == null)
        retirement = transaction
        for (sink in sinks.values.toList()) retireSink(sink)
        if (session != null) try { session.stop { evidence -> captureAcknowledged(transaction, evidence) } }
        catch (_: Exception) { captureAcknowledged(transaction, ProductAudioCaptureRetirement(false, false, false, "capture-stop-failed")) }
        transaction.issuing = false
        advance(transaction)
        return ticket
    }
    private fun retireSink(sink: ProductAudioSink) {
        val existing = sinkRetirements.firstOrNull { it.sink === sink && it.generation == generation }
        if (existing != null) return
        val operation = SinkRetirement(sink, generation); sinkRetirements += operation
        retirement?.let { if (!it.finished) it.sinks += operation }
        try { sink.retireAudio(generation) { retired -> sinkAcknowledged(operation, retired) } }
        catch (_: Exception) { sinkAcknowledged(operation, false) }
    }
    private fun sinkAcknowledged(operation: SinkRetirement, retired: Boolean): Unit = locked {
        if (operation.done) return@locked
        operation.done = true; operation.retired = retired
        val transaction = retirement
        if (!retired) {
            failure = "sink-retirement-failed"
            if (transaction != null && !transaction.finished && operation in transaction.sinks) transaction.failure = failure
            lease?.let { admission.complete(it, false) }
            if (transaction == null) retireInternal("sink-retirement-failed")
        }
        if (transaction != null && !transaction.finished && operation in transaction.sinks) advance(transaction)
        else if (retired) sinkRetirements.remove(operation)
    }
    private fun captureAcknowledged(transaction: Retirement, evidence: ProductAudioCaptureRetirement): Unit = locked {
        if (retirement !== transaction || transaction.finished || transaction.captureFinal) return@locked
        val old = transaction.capture
        transaction.capture = ProductAudioCaptureRetirement(old.captureReleased || evidence.captureReleased,
            old.workerTerminated || evidence.workerTerminated, old.callbacksDrained || evidence.callbacksDrained, evidence.failure)
        transaction.captureFinal = transaction.capture.successful || evidence.failure != null
        if (evidence.failure != null) {
            transaction.failure = evidence.failure; failure = "cleanup-failed"
            transaction.lease?.let { admission.complete(it, false) }
        }
        advance(transaction)
    }
    private fun advance(transaction: Retirement) {
        if (transaction.issuing || transaction.finished || !transaction.captureFinal || transaction.sinks.any { !it.done }) return
        val capture = transaction.capture
        val sinksRetired = transaction.sinks.all { it.retired }
        val cleanupFailure = transaction.failure ?: if (!sinksRetired) "sink-retirement-failed" else capture.failure
        transaction.failure = cleanupFailure
        val result = ProductAudioRetirementResult(capture.captureReleased, capture.workerTerminated,
            capture.callbacksDrained, sinksRetired, cleanupFailure)
        transaction.finished = true
        transaction.lease?.let { admission.complete(it, result.successful) }
        if (result.successful) {
            lease = null; sinkRetirements.removeAll(transaction.sinks.toSet())
            phase = if (destroyed) ProductAudioPhase.Destroyed else if (failure != null) ProductAudioPhase.Failed else ProductAudioPhase.Idle
        } else {
            requested = false; phase = ProductAudioPhase.CleanupFailed
            if (failure == null) failure = "cleanup-failed"
        }
        completions += transaction.completion to result
        if (result.successful && requested && resumed && focused && !destroyed) beginStart()
    }
}
