<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!-- Independently authored integration proposal; no recovered application source. -->
# Shared-root callsite proposal: integrator applies

This is an explicit proposal for the embedding app's existing foreground Activity, manifest and action router. Their current source is not part of the cleared repository, so these snippets are not represented as a verified diff. Review the actual signatures, imports and owning lifecycles before applying. Copy only the four separately listed production payload files to their exact destinations.

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
        // A queued explicit Start resumes after release on the capture worker.
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

Retain any owner/runtime with pending asynchronous retirement until it acknowledges release. It is safe to drop the UI reference after its runtime worker retains the stopped session; it is not safe to allocate a replacement capture for a recreated Activity while the old runtime is still retiring. The embedding app must transfer a **retirement gate only** across recreation, or prevent Start in the replacement Activity until old cleanup confirms release. Do not transfer an active microphone into a background owner. A failed release blocks further capture until the underlying resource is verifiably retired; never instantiate another runtime to bypass this barrier.

## Controls and status

Bind explicit Start and Stop click handlers directly to this Activity's `productAudioControls.start()` and `.stop()`. Render status after permission completion, lifecycle changes and asynchronous capture/retirement changes by reading `.status()` on the existing UI refresh mechanism. Controls should show permission pending/denied, capture starting/running/stopping/cleanup failed, requested/enabled and native state unknown. Audio settings call `.updateSettings(StyleSettings(...))`; reject a false result visibly without changing the active settings.

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

Register the existing consumers against the same owner, e.g. `registerSink("texture", textureConsumer)` and `registerSink("spatial", spatialConsumer)` only once each. Each implements ProductAudioSink, consumes the same immutable AudioSnapshot and creates its own admitted style engine. The Scene consumer posts to main and rechecks generation and BOOTTIME freshness when it actually calls the Scene port. The texture consumer validates immediately before its real host sink call. Both install a synchronous generation barrier in removeAudio, then retire resources on their owning thread. Unregistering/reconnecting one consumer never creates a capture session.

There is currently no admitted host texture allocation/JNI/native submission endpoint in this delivery. Keep `nativeReadback` omitted (unknown), retain the hook as an integrator task, and return unknown in action/UI status. Do not fabricate handles, report successful submission or replace raw camera state to simulate audio output.
