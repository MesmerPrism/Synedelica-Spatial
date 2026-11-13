<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!-- Independently authored for Synedelica Spatial; no recovered application source. -->
# Reviewed carrier patch delivery

This directory is an integration payload, not an installed application change. The integrator copies each isolated file below into the embedding app only after review. No private application, shared Activity, facade, manifest or build file was edited. The cleared library is the only audio-analysis dependency. No recovered source, profiles, palettes, identifiers, PCM or fixtures are included.

| Payload relative to `payload/` | Exact embedding destination relative to the app repository |
| --- | --- |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/ProductAudioFeatureOwner.kt` | same path |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/ProductAudioControls.kt` | same path |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/AndroidAudioRecordRuntime.kt` | same path |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/ProductAudioCaptureTime.kt` | same path |
| `tests/test_product_audio_owner.py` | same path |

The authored suite `tests/ProductAudioFeatureOwnerContractTest.kt` in this handoff
is copied to the carrier destination `tests/fixtures/product_audio/AudioOwnerChecks.kt`.
The Python registration runs the actual copied consumer sources against the
selected supplier core. The integrator must independently verify the supplier
revision and clean source before setting `SYNEDELICA_SPATIAL_ROOT` to its checkout.
The wrapper requires that path and all named inputs; it does not itself verify a
Git revision. Run `python -m unittest discover -s tests -p test_product_audio_owner.py`
after applying the fixture and payloads. Missing configuration fails rather than
skipping the test.

These are independently authored AGPL-3.0-or-later files, each with its own SPDX and authorship notice. Existing files at those destinations require integrator review before replacement. Android runtime uses API 24+ AudioRecord BOOTTIME timestamps, Builder/nonblocking short reads. Build configuration, library dependency admission and Android API qualification belong to the embedding app.

`shared-root-callsites.proposal.md` contains the separate Activity, permission, UI, CLI and manifest proposal. It is deliberately an integrator-applied proposal: no exact shared-source diff can be verified from this cleared repository. There is no generic extension dispatch and no global permission/capture singleton.

## Public signatures

```kotlin
ProductAudioFeatureOwner(
    permission: ProductAudioPermission,
    runtime: ProductAudioCaptureRuntime,
    nativeReadback: () -> Boolean? = { null },
)
fun start(): ProductAudioStatus
fun stop(): ProductAudioStatus
fun status(): ProductAudioStatus
fun onResume()
fun onPause()
fun onFocusChanged(hasFocus: Boolean)
fun onDestroy()
fun observeCameraSource(value: String?)
fun registerSink(id: String, sink: ProductAudioSink)
fun unregisterSink(id: String)
fun updateSettings(value: StyleSettings): Boolean

ProductAudioControls(owner: ProductAudioFeatureOwner)
fun start(): ProductAudioStatus
fun stop(): ProductAudioStatus
fun status(): ProductAudioStatus
fun updateSettings(settings: StyleSettings): Boolean
```

Action IDs are `audio.start`, `audio.stop`, `audio.status`, `audio.settings`. The actual foreground caller's router invokes these typed methods. UI and CLI must share the same Activity-owned instance. Settings accept only finite `strength` and `quietStrength` in 0..1 and finite `clockSpeed` in 0..10; the initial core supports `AuthoredRgbWave` only.

The host permission port implements `isGranted(): Boolean` and `request(result: (Boolean) -> Unit)`. Permission requests occur only after explicit Start in a resumed, focused owner. Duplicate Start does not prompt or open another session. Denial preserves the user's `requested` observation while `enabled=false`. Stop clears that intent. Resuming, gaining focus, source changes, reconnecting sinks and camera changes do not automatically start recording or ask permission.

Capture injection uses `create(generation, receiver): ProductAudioCaptureSession`, followed by `start()`. Create must perform no capture or receiver callbacks and must leave no resources if it throws. The runtime owns its worker and AudioRecord. `stop(onClosed: (Boolean) -> Unit)` synchronously closes the logical delivery gate and must not join its worker. It calls completion once after retirement: true confirms release; false reports a terminal release failure. A false acknowledgement keeps `pendingCleanup=true`, sets `failure=cleanup-failed` and permanently blocks a replacement session in that owner/runtime. Replacing the owner/runtime to bypass unconfirmed release is forbidden.

The concrete Android runtime enforces one outstanding session independently of the owner. It clears its admission gate only after AudioRecord.release returns successfully, and reports failed release without pretending cleanup succeeded. Cancellation before allocation and thread-start failure acknowledge cleanup without creating a recorder. Stop failure still attempts release. The host must retain the stopped runtime/owner until its cleanup acknowledgement; lifecycle destruction is logical cancellation, not a claim of completed release.

`onPcm(pcm, count, capturedAtNanos)` borrows PCM only during the callback. The Android worker wipes its ephemeral scratch buffer after delivery. `AudioAnalyzer(windowSize=256).accept(pcm, count, generation, capturedAtNanos)` is synchronous and returns immutable snapshots. A completed window starts at sequence 1 after each generation reset. Every registered sink receives the same snapshot object, with original sequence, generation, BOOTTIME domain and timestamp. Disconnecting a sink removes its modulation without replacing capture. Raw Solo/camera operation has no dependency on audio grant or Start.

Android timestamps use `AudioRecord.getTimestamp(AudioTimestamp.TIMEBASE_BOOTTIME)` and map the original hardware frame-position anchor to the first consumed PCM frame using the recorder sample rate. Missing, inconsistent, stale or future anchors drop the block. `SystemClock.elapsedRealtimeNanos()` measures age only; it is never a replacement capture timestamp. The pure `ProductAudioCaptureTime.firstSample` helper rejects age >250ms, and a one-second no-fresh-progress watchdog retires capture. These timestamps preserve the declared original frame estimate at every handoff; exact ADC/overrun behavior still requires Android/device qualification. Consumers must enforce age <=250ms again at the actual Scene or texture submission; the owner does not turn a delayed observation into a fresh one.

`ProductAudioSink.onAudio(snapshot, settings)` can run on the capture worker. Register the texture and Scene consumers with this same owner. Each consumer uses its own style engine to avoid advancing one mutable engine twice. `removeAudio(generation)` must install a synchronous generation barrier before returning and dispatch resource removal on the resource-owning thread. Scene calls must use the main thread. Host callbacks must not block waiting for another callback that requires the owner's monitor. Consumers must preserve identity/removal for invalid, stale, future or regressed observations and reject queued work from stopped generations.

`ProductAudioStatus` reports requested/enabled, observed permission, capture phase, pending cleanup, generation, camera source observation, settings, a stable failure code and nullable `effectiveNativeLut`. Enabled means live foreground microphone capture, not installed native LUT state. Null native state is unknown. Default readback remains unknown: this delivery supplies no host texture allocation/JNI/native submission endpoint. Supply readback only when the host can actually observe effective compositor state. No endpoint or successful native application is invented.

## Focused host verification

`tests/ProductAudioFeatureOwnerContractTest.kt` is an executable JVM suite with deterministic permission/capture boundary doubles and the real core analyzer/owner/controls. It requires only Kotlin/JDK plus the cleared core sources and the three pure Kotlin owner/controls/timestamp payload files. Its main class is `io.github.mesmerprism.rustyquest.spatial_camera_panel.ProductAudioFeatureOwnerContractTest`. The repository integrator runs it using the focused host compiler harness, separately from Android runtime compilation. Do not compile AndroidAudioRecordRuntime in that host-only invocation.

It checks explicit permission/denial, duplicate Start, one-capture fanout, source/disconnect stability, synchronous stop/lifecycle barriers, late callback rejection, restart after successful cleanup, terminal failed cleanup and truthful controls/settings. The suite does not certify the Android runtime, microphone privacy indicator, APK, permissions UI, hardware clock behavior, Scene, compositor, native texture ownership, headset or device. No APK/device/Gradle build forms part of this delivery.
