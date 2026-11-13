// SPDX-License-Identifier: AGPL-3.0-or-later
// Independently authored for Synedelica Spatial; no recovered application source.
package io.github.mesmerprism.rustyquest.spatial_camera_panel

import io.github.mesmerprism.synedelica.core.AudioAnalyzer
import io.github.mesmerprism.synedelica.core.AudioSnapshot
import io.github.mesmerprism.synedelica.core.StyleSettings

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
     * Immediately closes the delivery gate. Never joins a worker on the caller thread.
     * Acknowledge exactly once after release: true = released, false = terminal failure.
     * Must handle stop before start and never throw. False never authorizes replacement.
     */
    fun stop(onClosed: (Boolean) -> Unit)
}
interface ProductAudioCaptureRuntime {
    /** No capture or receiver callbacks before start(). A throwing create owns no resources. */
    fun create(generation: Long, receiver: ProductAudioCaptureReceiver): ProductAudioCaptureSession
}
interface ProductAudioSink {
    /** Same immutable snapshot goes to each registered consumer; never rebase its metadata. */
    fun onAudio(snapshot: AudioSnapshot, settings: StyleSettings)
    /** Invalidate queued work synchronously; dispatch GPU/Scene removal on its owning thread. */
    fun removeAudio(generation: Long)
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
 * One foreground capture and analyzer, shared by texture and Scene consumers.
 * Construct one per owning Activity. Logical barriers are synchronous under this monitor;
 * capture release is asynchronous. No camera control is called by this class.
 */
class ProductAudioFeatureOwner(
    private val permission: ProductAudioPermission,
    private val runtime: ProductAudioCaptureRuntime,
    private val nativeReadback: () -> Boolean? = { null },
) {
    private val analyzer = AudioAnalyzer(windowSize = 256)
    private val sinks = linkedMapOf<String, ProductAudioSink>()
    private var generation = 0L
    private var resumed = false
    private var focused = false
    private var destroyed = false
    private var requested = false
    private var permissionState = ProductAudioPermissionState.Unknown
    private var phase = ProductAudioPhase.Idle
    private var settings = StyleSettings()
    private var source: String? = null
    private var failure: String? = null
    private var active: ProductAudioCaptureSession? = null
    private var retiring: ProductAudioCaptureSession? = null
    private var terminalCleanupFailure = false

    @Synchronized fun start(): ProductAudioStatus {
        if (destroyed || requested) return status()
        if (!resumed || !focused) {
            failure = "foreground-required"
            return status()
        }
        requested = true
        if (retiring == null) beginStart()
        return status()
    }

    @Synchronized fun stop(): ProductAudioStatus {
        halt()
        return status()
    }

    @Synchronized fun onResume() { if (!destroyed) resumed = true }
    @Synchronized fun onPause() { resumed = false; halt() }
    @Synchronized fun onFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        if (!hasFocus) halt()
    }
    @Synchronized fun onDestroy() { destroyed = true; resumed = false; halt() }

    /** Camera observation is diagnostic only; it never creates or replaces capture. */
    @Synchronized fun observeCameraSource(value: String?) { source = value }

    @Synchronized fun registerSink(id: String, sink: ProductAudioSink) {
        check(!destroyed) { "Audio owner destroyed" }
        require(id.isNotBlank())
        sinks.put(id, sink)?.let { old -> if (old !== sink) removeSafely(old) }
    }
    @Synchronized fun unregisterSink(id: String) { sinks.remove(id)?.let(::removeSafely) }

    @Synchronized fun updateSettings(value: StyleSettings): Boolean {
        if (!value.strength.isFinite() || value.strength !in 0f..1f ||
            !value.quietStrength.isFinite() || value.quietStrength !in 0f..1f ||
            !value.clockSpeed.isFinite() || value.clockSpeed !in 0f..10f) return false
        settings = value
        return true
    }

    @Synchronized fun status(): ProductAudioStatus {
        // Re-read the real caller's permission; a cached grant cannot imply current access.
        if (permissionState == ProductAudioPermissionState.Granted && !hasPermission()) {
            permissionState = ProductAudioPermissionState.Denied
            halt("permission-revoked")
        }
        return ProductAudioStatus(
            requested, phase == ProductAudioPhase.Capturing && resumed && focused && !destroyed,
            permissionState, phase, retiring != null, generation, source, settings,
            try { nativeReadback() } catch (_: Exception) { null }, failure,
        )
    }

    private fun hasPermission(): Boolean = try { permission.isGranted() } catch (_: Exception) { false }

    private fun beginStart() {
        if (!requested || destroyed || !resumed || !focused || retiring != null) return
        failure = null
        generation++
        analyzer.reset()
        val token = generation
        if (hasPermission()) {
            permissionState = ProductAudioPermissionState.Granted
            beginCapture(token)
        } else {
            permissionState = ProductAudioPermissionState.Pending
            phase = ProductAudioPhase.PermissionPending
            try {
                permission.request { granted -> permissionResult(token, granted) }
            } catch (_: Exception) {
                if (token == generation) {
                    permissionState = ProductAudioPermissionState.Denied
                    requested = false
                    phase = ProductAudioPhase.Failed
                    failure = "permission-request-failed"
                }
            }
        }
    }

    @Synchronized private fun permissionResult(token: Long, granted: Boolean) {
        if (token != generation || destroyed || !requested || !resumed || !focused ||
            phase != ProductAudioPhase.PermissionPending) return
        permissionState = if (granted && hasPermission()) ProductAudioPermissionState.Granted
            else ProductAudioPermissionState.Denied
        if (permissionState == ProductAudioPermissionState.Granted) beginCapture(token)
        else phase = ProductAudioPhase.Idle
    }

    private fun beginCapture(token: Long) {
        if (token != generation || active != null || retiring != null) return
        phase = ProductAudioPhase.Starting
        val receiver = object : ProductAudioCaptureReceiver {
            override fun onStarted() = captureStarted(token)
            override fun onPcm(pcm: ShortArray, count: Int, capturedAtNanos: Long) =
                accept(token, pcm, count, capturedAtNanos)
            override fun onFailure() = captureFailed(token)
        }
        try {
            val session = runtime.create(token, receiver)
            active = session
            session.start()
        } catch (_: Exception) {
            if (token == generation) halt("capture-failed")
        }
    }

    @Synchronized private fun captureStarted(token: Long) {
        if (token == generation && active != null && requested && resumed && focused && !destroyed &&
            phase == ProductAudioPhase.Starting) phase = ProductAudioPhase.Capturing
    }
    @Synchronized private fun captureFailed(token: Long) {
        if (token == generation && active != null) halt("capture-failed")
    }
    @Synchronized private fun accept(token: Long, pcm: ShortArray, count: Int, at: Long) {
        if (token != generation || phase != ProductAudioPhase.Capturing || !requested ||
            destroyed || !resumed || !focused) return
        if (!hasPermission()) {
            permissionState = ProductAudioPermissionState.Denied
            halt("permission-revoked")
            return
        }
        val snapshots = try { analyzer.accept(pcm, count, token, at) } catch (_: Exception) {
            halt("invalid-capture-input")
            return
        }
        for (snapshot in snapshots) {
            for ((id, sink) in sinks.toList()) {
                if (token != generation || phase != ProductAudioPhase.Capturing) return
                if (sinks[id] === sink) {
                    try { sink.onAudio(snapshot, settings) } catch (_: Exception) {
                        sinks.remove(id)
                        removeSafely(sink)
                    }
                }
            }
        }
    }

    private fun halt(reason: String? = null) {
        requested = false
        generation++ // Invalidates capture and permission callbacks before any host removal.
        analyzer.reset()
        if (permissionState == ProductAudioPermissionState.Pending)
            permissionState = ProductAudioPermissionState.Unknown
        if (reason != null) failure = reason
        for (sink in sinks.values.toList()) removeSafely(sink)
        val session = active
        active = null
        if (session != null) {
            retiring = session
            phase = ProductAudioPhase.Stopping
            try { session.stop { released -> cleanupAcknowledged(session, released) } }
            catch (_: Exception) { cleanupAcknowledged(session, false) }
        } else if (retiring == null) {
            phase = if (destroyed) ProductAudioPhase.Destroyed else if (failure != null)
                ProductAudioPhase.Failed else ProductAudioPhase.Idle
        }
    }

    @Synchronized private fun cleanupAcknowledged(session: ProductAudioCaptureSession, released: Boolean) {
        if (retiring !== session || terminalCleanupFailure) return
        if (!released) {
            terminalCleanupFailure = true
            requested = false
            phase = ProductAudioPhase.CleanupFailed
            failure = "cleanup-failed"
            return // Keep retirement pending; no new recorder can be admitted.
        }
        retiring = null
        phase = if (destroyed) ProductAudioPhase.Destroyed else if (failure != null)
            ProductAudioPhase.Failed else ProductAudioPhase.Idle
        if (requested && resumed && focused && !destroyed) beginStart()
    }
    private fun removeSafely(sink: ProductAudioSink) {
        try { sink.removeAudio(generation) } catch (_: Exception) {
            // Every other sink must still receive its barrier. Host removal remains its obligation.
            if (failure == null) failure = "sink-removal-failed"
        }
    }
}
