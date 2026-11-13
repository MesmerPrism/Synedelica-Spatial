<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!-- Authored integration contract; no recovered application source. -->
# Carrier audio payload — retirement version2

This is a source handoff, not an installed carrier change. The carrier integrator
alone applies shared Activity, Whole/Original, journal, manifest, action and native
paths. This version repairs initial6b94f01's runtime-local admission, effectful
status and capture-only cleanup. All payload source is authored AGPL-3.0-or-later.

Copy the following to the SAME carrier-relative destinations under `payload/`:

| Path suffix | Purpose |
| --- | --- |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/ProductAudioFeatureOwner.kt` | Foreground capture and physical retirement join |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/ProductAudioRetirement.kt` | Version2 ticket/result and process admission |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/ProductAudioWorkerLifetime.kt` | Actual worker-termination observer |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/AndroidAudioRecordRuntime.kt` | Process-gated microphone backend |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/ProductAudioControls.kt` | Typed Start/Stop/status/settings actions |
| `product/android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/ProductAudioCaptureTime.kt` | Shared original BOOTTIME mapping |
| `tests/test_product_audio_owner.py` | Focused consumer test registration |

Copy this handoff's `tests/ProductAudioFeatureOwnerContractTest.kt` to carrier
`tests/fixtures/product_audio/AudioOwnerChecks.kt`. Its independent real-thread
suite is `tests/ProductAudioWorkerLifetimeTest.kt`. The Python registration runs
copied pure consumer sources with the supplier's real core and pinned compiler.
Independently verify the supplier revision/clean bytes before setting
`SYNEDELICA_SPATIAL_ROOT`; the wrapper verifies inputs, not Git revision/cleanliness.

## Physical retirement

`owner.retire(): ProductAudioRetirementTicket` synchronously closes publication
before returning an immutable version2 completion view. Stop and lifecycle paths
use that same transaction. Completion joins recorder release, actual capture-worker
termination and callback drain, plus every admitted or detached sink's physical
resource ACK. Missing ACK stays pending. An explicit failure is sticky and can
never authorize replacement. Incomplete capture facts with no explicit failure
stay pending for later complete evidence. Capture-only ACK cannot retire sinks.

`ProductAudioCaptureSession.stop(onClosed: (ProductAudioCaptureRetirement) -> Unit)`
requests cancellation without blocking the caller. The backend's joining observer
waits for the ACTUAL capture worker to terminate before acknowledging release,
workerTerminated and callbacksDrained. A worker-finally callback alone is not EOF.
An operation that never began is an actual no-resource case. Failed release/join
or observer creation cannot open process admission. PCM callbacks are synchronous
within the capture worker, so successful join also proves their return.

The version2 sink protocol replaces void removal:

```kotlin
fun retireAudio(generation: Long, onRetired: (Boolean) -> Unit)
```

It installs a synchronous generation/publication barrier before returning, then
acknowledges true only after owning Scene/GPU/texture/fence resources are actually
retired or proves they were never created. Scheduling removal, zero current
attachments, timeout or a logical barrier alone is not ACK. False is terminal
failure. Detached/replaced sink operations remain joined. No compatibility shim
converts old void removal to successful physical retirement.

The process app-owner gate remains occupied through combined retirement, including
host recreation; the backend has a separate process microphone gate. Production
must retain those defaults or an equivalent SINGLE app-owned authority. Injected
fresh gates are for isolated host tests. Failed gates are sticky and ignore replay.

The carrier retains the exact ticket and original owner/session custody on its
existing lifecycle owner across Activity recreation and binds the genuine app,
request/run/Whole subject. Generation alone is not ticket identity. Join the
participant INSIDE the SAME Whole/Original transaction BEFORE global terminal,
fence clear, retained-cleanup/no-media and zero-attachment finish paths. An Activity
allOf AFTER a successful old Whole is false cleanup. Existing closed Body/no-media
proofs retain their scopes. The carrier owns any new app-level envelope, producer
and readers; this supplier creates no final Whole JSON or native proof.
Carrier Start and new resource adoption remain guarded by its existing whole-app lifecycle:
an audio ticket alone never grants app restart or proves other resources retired.
Do not forward or queue a new owner Start or adopt a new sink while that Whole forbids it.

## Foreground observation

Explicit Start owns one foreground recorder/analyzer and fans the SAME immutable
snapshot to selected sinks. Audio off/permission denial leaves raw Solo/camera
actions independent. UI/CLI bind audio.start/stop/status/settings to their actual
foreground Activity's typed owner. Pause/focus loss/destroy retire publication;
restart waits for physical completion. Permission requests occur only from Start.

`status()` is a PURE cached snapshot: no permission callback, cleanup or native
query. `reconcilePermission()` is an explicit mutating lifecycle/control operation;
PCM/start admission also checks access. `observeNativeLut(generation, Boolean?)`
records actual host evidence only for the live admitted generation. Retirement
or a new generation resets it to unknown; retired/old callbacks are ignored.
The legacy constructor readback callback is unused. Enabled means
foreground microphone capture, never native effect success. Pending cleanup spans
capture-worker and physical sink retirement, not microphone release alone.

PCM is borrowed synchronously and wiped; never retained, recorded or transmitted.
The common pure AudioCaptureTime validates the original BOOTTIME hardware frame
anchor; elapsedRealtimeNanos measures age and never replaces capture time. Invalid,
missing, future or stale anchors drop the block. Last-call250ms validation, alpha,
eye geometry and actual GPU effective readback remain host-owned. No demo APK,
optical grid or native outline is required for direct audio/LUT integration.

Host checks and real API35 compilation are source evidence. Actual Whole adoption,
Android capture/clock calibration, APK/device, Scene/GPU/fence retirement and native
readback remain unqualified until the carrier completes separate gates.
