// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored foreground host. Public SDK registration signatures referenced from
// the AGPL reference application; no recovered palette/outline/data or lifecycle implementation.
package io.github.mesmerprism.synedelica.demo.spatial

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.SessionState
import com.meta.spatial.toolkit.*
import com.meta.spatial.vr.VRFeature
import io.github.mesmerprism.synedelica.spatial.*

class DemoSpatialActivity : AppSystemActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var lifecycle: CaptureLifecycle? = null
    private var adapter: SpatialLutAdapter? = null
    private var permissionResult: ((Boolean) -> Unit)? = null
    private var controls: View? = null
    private var foreground = false
    private var focused = false
    private var destroyed = false
    private val watchdog = object : Runnable {
        override fun run() {
            if (!destroyed) {
                adapter?.invalidateIfStale()
                lifecycle?.retryPendingStart()
                main.postDelayed(this, 50)
            }
        }
    }

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))
    override fun registerPanels(): List<PanelRegistration> = listOf(
        LayoutXMLPanelRegistration(R.id.demo_controls_panel,
            layoutIdCreator = { R.layout.demo_controls },
            settingsCreator = { UIPanelSettings(shape = QuadShapeOptions(width = .8f, height = .65f),
                style = PanelStyleOptions(themeResourceId = R.style.DemoTheme)) },
            panelSetupWithRootView = { root, _, _ -> bind(root) })
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        main.post(watchdog)
    }
    override fun onSceneReady() {
        super.onSceneReady()
        if (destroyed) return
        scene.enablePassthrough(true)
        val output = SpatialLutAdapter(AndroidMainThreadPort(), SystemClock::elapsedRealtimeNanos, MetaSceneLutPort(scene))
        adapter = output
        lifecycle = CaptureLifecycle(object : CapturePermissionPort {
            override fun granted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            override fun request(result: (Boolean) -> Unit) {
                permissionResult = result
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            }
        }, ForegroundAudioCapture(output), object : CaptureEffectPort {
            override fun start(generation: Long) = output.start(generation)
            override fun stop() = output.stop()
        }, { updateStatus(it) }).also { it.foreground(foreground); it.focus(focused) }
        Entity.create(listOf(Panel(R.id.demo_controls_panel), Transform(Pose(Vector3(0f, 1.3f, -1.5f))), Visible(true)))
        controls?.let(::bind)
    }
    private fun bind(root: View) {
        controls = root
        root.findViewById<Button>(R.id.start_capture).setOnClickListener { lifecycle?.start() }
        root.findViewById<Button>(R.id.stop_capture).setOnClickListener { lifecycle?.stop() }
        updateStatus(lifecycle?.state ?: CaptureState.Idle)
    }
    private fun updateStatus(state: CaptureState) {
        controls?.findViewById<TextView>(R.id.capture_status)?.text = when (state) {
            CaptureState.Idle -> "Stopped — press Start when focused"
            CaptureState.AwaitingPermission -> "Waiting for microphone permission"
            CaptureState.Running -> "Microphone active — audio stays in memory"
            CaptureState.Retiring -> "Stopping microphone; restart waits for release"
            CaptureState.Failed -> "Microphone release failed — close the app before trying again"
            CaptureState.Destroyed -> "Closed"
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_REQUEST) {
            val result = permissionResult
            permissionResult = null
            result?.invoke(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
        }
    }
    override fun onResume() { super.onResume(); foreground = true; lifecycle?.foreground(true) }
    override fun onPause() { foreground = false; lifecycle?.foreground(false); super.onPause() }
    override fun onSessionStateChanged(state: SessionState) {
        super.onSessionStateChanged(state)
        main.post {
            if (!destroyed) { focused = state == SessionState.FOCUSED; lifecycle?.focus(focused) }
        }
    }
    override fun onDestroy() {
        destroyed = true
        lifecycle?.destroy()
        adapter?.stop()
        main.removeCallbacks(watchdog)
        permissionResult = null
        controls = null
        super.onDestroy()
    }
    private companion object { const val MICROPHONE_REQUEST = 41 }
}
