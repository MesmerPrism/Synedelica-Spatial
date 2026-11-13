<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!-- Independently authored integration proposal; no recovered application source. -->
# Shared-root callsite proposal: integrator applies

This is an explicit proposal for the embedding app's existing foreground Activity, manifest and action router. Their current source is not part of the cleared repository, so these snippets are not represented as a verified diff. Review actual signatures and lifecycles before applying. Copy all six listed production payloads and the test registration/fixture to their exact destinations.

## Foreground Activity

Register the permission launcher with the actual Activity's existing Activity Result lifecycle before it reaches STARTED. Create exactly one owner and one runtime for that Activity. The following members illustrate the binding inside that existing Activity; register the launcher only once, including configuration recreation:

```kotlin
private val audioPermissionWaiters = mutableListOf<(Boolean) -> Unit>()
private val audioPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    val waiters = audioPermissionWaiters.toList()
    audioPermissionWaiters.clear()
    waiters.forEach { it(granted) }
}
private lateinit var productAudioOwner: ProductAudioFeatureOwner
private lateinit var productAudioControls: ProductAudioControls

// In the Activity's existing onCreate, before binding audio UI/action callers:
val audioPermission = object : ProductAudioPermission {
    override fun isGranted(): Boolean = ContextCompat.checkSelfPermission(
        this@ActualOwningActivity, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun request(result: (Boolean) -> Unit) {
        // A queued explicit Start can resume on the capture-join observer or a sink ACK thread.
        // Marshal the actual Activity launcher to main and recheck cancellation there.
        runOnUiThread {
            val current = if (::productAudioOwner.isInitialized) productAudioOwner.status() else null
            if (current == null || !current.requested ||
                current.capture != ProductAudioPhase.PermissionPending ||
                isFinishing || isDestroyed) {
                result(false)
                return@runOnUiThread
            }
            // Reuse any already visible OS prompt; do not launch a second request.
            val launch = audioPermissionWaiters.isEmpty()
            audioPermissionWaiters += result
            if (launch) {
                try { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                catch (_: Exception) {
                    val failed = audioPermissionWaiters.toList()
                    audioPermissionWaiters.clear()
                    failed.forEach { it(false) }
                }
            }
        }
    }
}
productAudioOwner = ProductAudioFeatureOwner(audioPermission, AndroidAudioRecordRuntime())
productAudioControls = ProductAudioControls(productAudioOwner)
```

`ActualOwningActivity` is a placeholder for the integrator's real class name, not a new class or extension dispatch endpoint. Preserve existing Activity bodies and add these calls at their matching lifecycle points:

```kotlin
// existing onResume, after super:
productAudioOwner.onResume()
// seed the current focus observation; onWindowFocusChanged also updates it:
productAudioOwner.onFocusChanged(window.decorView.hasWindowFocus())

// existing onWindowFocusChanged(hasFocus), after super:
if (::productAudioOwner.isInitialized) productAudioOwner.onFocusChanged(hasFocus)

// existing onPause, before handing control away:
productAudioOwner.onPause()

// existing onDestroy, before dropping resource-owning references:
productAudioOwner.onDestroy()
audioPermissionWaiters.clear()
```

Retain the exact version2 ticket and original owner/session custody on the existing app lifecycle owner across recreation. Default process app-owner and microphone gates prevent bypass of pending physical retirement; never substitute new production gates or transfer active capture to a background owner. Failed retirement is sticky.

## Required Whole/Original join before terminal cleanup

Register audio INSIDE the SAME existing Whole/Original transaction, including
retained-cleanup/no-media and zero-attachment Activity finish. Before its global
terminal/fence-clear decision, call `productAudioOwner.retire()` to synchronously
close publication and join the returned version2 ticket. Completion must prove
microphone release, actual capture-worker termination/join, callback drain and
every admitted/detached physical sink ACK (or actual never-created resources).
Missing ACK stays pending; failure cannot authorize restart. Retain actual
ticket/owner/session plus genuine app/run/request/Whole-subject custody; generation
alone is not an identity. Keep that custody on the existing lifecycle owner.

An Activity allOf after old Whole success is false cleanup. Do not append ignored
audio fields to closed Body/no-media proof families. The carrier owns any new
app-level envelope and its actual producer/readers/journal; this supplier defines
no final Whole JSON, camera result, or native retirement proof.
The existing whole-app lifecycle also remains the Start/resource-adoption authority;
audio completion alone cannot authorize restart while any other Whole participant
is pending. Do not forward/queue owner Start or adopt sinks through a closed app-level gate.

## Controls and status

Bind typed Start/Stop directly to the actual Activity owner. Render cached pure `status()` on existing observers; it never checks permission or starts cleanup. Call mutating `reconcilePermission()` explicitly on lifecycle/control/permission paths, separate from observation guards. Native evidence enters via `observeNativeLut(generation,Boolean?)`, only for the live admitted generation; retirement resets unknown and rejects old callbacks. Never query a driver from status. Controls show truthful requested/enabled, pending physical cleanup and unknown native state.

Add explicit cases to the existing Activity-owned action router:

| Existing router action ID | Invoke against that Activity's instance |
| --- | --- |
| `audio.start` | `productAudioControls.start()` |
| `audio.stop` | `productAudioControls.stop()` |
| `audio.status` | `productAudioControls.status()` |
| `audio.settings` | parse typed, finite, bounded fields; call `updateSettings`; return accepted flag plus `status()` |

Execute UI/CLI Start and settings on the owning Activity's main thread. CLI must target the actual current, resumed, focused Activity and use its permission launcher and lifecycle owner. If no such owner is available, return `foreground-required`; do not create a background/global audio owner or generic extension dispatcher. Preserve the existing router's own result serialization and authentication. Return the actual `ProductAudioStatus` fields, including `pendingCleanup`, `generation`, `sourceObservation`, `requested`, `enabled`, and nullable `effectiveNativeLut`; do not map `enabled` to successful native submission.

The existing camera source observer may call `productAudioOwner.observeCameraSource(existingSourceDescription)` when its source changes. This observation must not call Start or create a runtime. Existing raw Solo camera actions continue through their current caller independently of audio permission and requested/enabled state.

## Manifest proposal

In the existing app manifest, add only if absent:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature android:name="android.hardware.microphone" android:required="false" />
```

Do not add a service, background microphone permission/workflow or automatic startup. Preserve the app's current camera, Quest and Activity declarations. Confirm existing AndroidX Activity Result/ContextCompat dependencies and cleared-core module admission through the app's existing dependency process.

## Adapter/facade hooks

Register consumers against the same owner. Version2 ProductAudioSink consumes the same immutable snapshot and implements `retireAudio(generation,onRetired)`: synchronous publication barrier followed by ACTUAL owning-thread physical Scene/GPU/texture/fence retirement ACK, or actual never-created proof. Queueing removal alone cannot ACK. Detached sink resources remain joined. Allocation/fences/current-pixel alpha/eye semantics and final original BOOTTIME freshness stay host-owned. Busy process admission rejects new resource adoption; the caller retains custody of a rejected sink.

There is no admitted host texture allocation/JNI/submission endpoint in this delivery. Keep native state unknown until actual `observeNativeLut` evidence. The legacy constructor readback callback is unused. Do not fabricate handles, submission success or raw camera state to simulate audio output.
