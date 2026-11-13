// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored reusable capture ownership state machine. No Android or PCM state.
package io.github.mesmerprism.synedelica.demo.spatial

interface CapturePermissionPort {
    fun granted(): Boolean
    fun request(result: (Boolean) -> Unit)
}
interface CaptureSession { fun stop() }
enum class CaptureEnd { Released, ReleaseFailed }
interface CapturePort { fun start(generation: Long, onEnded: (CaptureEnd) -> Unit): CaptureSession }
interface CaptureEffectPort { fun start(generation: Long); fun stop() }
enum class CaptureState { Idle, AwaitingPermission, Running, Retiring, Failed, Destroyed }

/** All methods and port completions must run on the host's serialized event thread.
 * stop() requests retirement; only onEnded acknowledges recorder release. A second
 * Start is remembered while retiring, and cannot create overlapping captures.
 */
class CaptureLifecycle(
    private val permission: CapturePermissionPort,
    private val capture: CapturePort,
    private val effect: CaptureEffectPort,
    private val changed: (CaptureState) -> Unit,
) {
    var state = CaptureState.Idle
        private set
    private var foreground = false
    private var focused = false
    private var destroyed = false
    private var requested = false
    private var generation = 0L
    private var requestToken = 0L
    private var session: CaptureSession? = null
    private var retiringGeneration: Long? = null
    private var waitingForProcess = false

    fun foreground(value: Boolean) { foreground = value; if (!value) stop() }
    fun focus(value: Boolean) { focused = value; if (!value) stop() }
    fun start() {
        if (destroyed || !foreground || !focused) return
        requested = true
        if (state == CaptureState.Idle) considerStart()
    }
    /** Host event-loop tick; a busy process owner retains no callback to this host. */
    fun retryPendingStart() {
        if (waitingForProcess && requested && !destroyed && foreground && focused) {
            waitingForProcess = false
            transition(CaptureState.Idle)
            considerStart()
        }
    }
    fun stop() {
        requested = false
        requestToken++
        effect.stop()
        if (waitingForProcess) {
            waitingForProcess = false
            transition(CaptureState.Idle)
        }
        when (state) {
            CaptureState.Running -> {
                retiringGeneration = generation
                transition(CaptureState.Retiring)
                session?.stop()
            }
            CaptureState.AwaitingPermission -> transition(CaptureState.Idle)
            else -> Unit
        }
    }
    fun destroy() {
        if (destroyed) return
        destroyed = true
        stop()
        if (state != CaptureState.Retiring && state != CaptureState.Failed) transition(CaptureState.Destroyed)
    }
    private fun considerStart() {
        if (!requested || destroyed || !foreground || !focused) return
        if (!permission.granted()) {
            val token = ++requestToken
            transition(CaptureState.AwaitingPermission)
            permission.request { granted ->
                if (token == requestToken && state == CaptureState.AwaitingPermission) {
                    transition(CaptureState.Idle)
                    if (granted && requested && !destroyed && foreground && focused) begin()
                    else requested = false
                }
            }
        } else begin()
    }
    private fun begin() {
        val current = CaptureGenerations.next()
        generation = current
        transition(CaptureState.Running)
        effect.start(current)
        try {
            session = capture.start(current) { result -> ended(current, result) }
        } catch (_: CaptureBusyException) {
            effect.stop()
            session = null
            waitingForProcess = true
            transition(CaptureState.Retiring)
        } catch (_: CaptureReleaseFailedException) {
            effect.stop()
            session = null
            requested = false
            transition(CaptureState.Failed)
        } catch (failure: Exception) {
            effect.stop()
            session = null
            requested = false
            transition(if (destroyed) CaptureState.Destroyed else CaptureState.Idle)
        }
    }
    private fun ended(current: Long, result: CaptureEnd) {
        if (current != generation || (state != CaptureState.Running && retiringGeneration != current)) return
        if (result == CaptureEnd.ReleaseFailed) {
            requested = false
            effect.stop()
            transition(CaptureState.Failed)
            return
        }
        val restart = state == CaptureState.Retiring && requested
        effect.stop()
        session = null
        retiringGeneration = null
        transition(if (destroyed) CaptureState.Destroyed else CaptureState.Idle)
        if (restart) considerStart() else requested = false
    }
    private fun transition(value: CaptureState) { state = value; changed(value) }
}
