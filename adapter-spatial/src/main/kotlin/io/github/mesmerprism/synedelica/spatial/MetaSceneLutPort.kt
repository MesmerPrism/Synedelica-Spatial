// SPDX-License-Identifier: AGPL-3.0-or-later
// Newly authored binding. AGPL reference: the AGPL reference application demonstrated the public
// Meta Spatial SDK Scene.setPassthroughLUT approach; no private lifecycle or data copied.
package io.github.mesmerprism.synedelica.spatial

import android.os.Handler
import android.os.Looper
import com.meta.spatial.core.Lut
import com.meta.spatial.runtime.Scene

class AndroidMainThreadPort : MainThreadPort {
    private val handler = Handler(Looper.getMainLooper())
    override fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
    override fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Scene requires the Android main thread" }
    }
}

class MetaSceneLutPort(private val scene: Scene) : SceneLutPort {
    override fun prepare(samples: IntArray): Any {
        checkMain()
        require(samples.size == 16 * 16 * 16 * 3)
        val table = Lut(16)
        for (blue in 0 until 16) for (green in 0 until 16) for (red in 0 until 16) {
            val offset = ((blue * 16 + green) * 16 + red) * 3
            table.setMapping(red, green, blue, samples[offset], samples[offset + 1], samples[offset + 2])
        }
        return table
    }
    override fun setPassthroughLut(lut: Any?) {
        checkMain()
        scene.setPassthroughLUT(lut as Lut?)
    }
    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
