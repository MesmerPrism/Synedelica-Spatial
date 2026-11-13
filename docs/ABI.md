# Cleared source ABI

Newly authored code; no recovered palette or outline data is admitted.

Package base: `io.github.mesmerprism.synedelica`.
Core types in `.core`:

```kotlin
enum class AudioClockDomain { BootTime }
data class AudioSnapshot(val generation: Long, val sequence: Long,
    val capturedAtNanos: Long, val clockDomain: AudioClockDomain,
    val valid: Boolean, val amplitude: Float, val rms: Float, val onsetCount: Long)
enum class PaletteFamily { AuthoredRgbWave }
data class StyleSettings(val family: PaletteFamily = PaletteFamily.AuthoredRgbWave,
    val strength: Float = 1f, val quietStrength: Float = 0.15f,
    val clockSpeed: Float = 0.1f)
class LutFrame(val generation: Long, val sequence: Long,
    val capturedAtNanos: Long, val clockDomain: AudioClockDomain,
    rgbBytesBgrOrder: IntArray) { fun copySamples(): IntArray }
interface StyleEngine {
    fun advance(source: AudioSnapshot, nowNanos: Long, deltaSeconds: Float,
        settings: StyleSettings): LutFrame
    fun reset()
}
class QuietStyle : StyleEngine
```

`LutFrame` owns exactly 16*16*16*3 integer channels (0..255); blue outer,
green middle, red inner; RGB channels per point. `LutFrame.identity(source)`
constructs identity. Arrays copied on input/output. Source validation fails closed.
Fresh valid silent input permits authored quiet animation. Invalid/stale/future,
generation regression and sequence/timestamp regression produce identity and clear
transients. Generation advance resets all state. Age boundary inclusive 250ms.
Repeated current observations may advance animation while fresh; new sequence must
not regress timestamp. Style settings are finite normalized strength/quietStrength,
clockSpeed finite 0..10. AudioAnalyzer accepts borrowed PCM synchronously, stores
only scalar partial-window accumulators; no PCM retained. Its public methods are
to be documented by core owner before capture consumer binding.

Texture adapter `.texture`: immutable `TextureLutUpload` with `dimension=16`,
generation, sequence, original capturedAtNanos, clockDomain and copied RGB8 bytes.
`TextureLutAdapter.prepare(frame)` converts only; `submit(upload, generation,
nowNanos, sink)` validates immediately before synchronously calling host sink.
Stale/mismatch invokes host removal. Host owns colour space, interpolation,
eye/source selection, texture fences and retirement. No GL/native ownership here.

Spatial adapter `.spatial` applies the same owned samples through a main-thread
Scene port, with expected generation and BOOTTIME recheck at the actual call.
Stop removes the Scene LUT, including queued old-generation work.

Only AuthoredRgbWave is admitted in the initial closure. Additional public
families need an independent supplier revision/license/source review.
