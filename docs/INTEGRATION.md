# Integration boundary

The core consumes immutable normalized audio features; it never opens a recorder.
One foreground host capture feeds all selected sinks. Connecting/disconnecting a
sink does not start another microphone. Start is explicit; denied audio permission
does not block a default Solo camera path. Stop, focus loss, pause and destroy
retire publication synchronously and await acknowledged hardware release before
a replacement capture can start. Failed release blocks replacement.

LUT transfer: dimension16, 12288 RGB8 channels, red-fastest/green/blue-outermost.
The immutable Kotlin value also declares contractVersion1,
LutOrder.RedFastestBlueOutermost and ColorEncoding.EncodedStraightRgb8.
At the reviewed Rust contract boundary these map to `contract_version=1`,
`LutOrder::RedFastestBlueOutermost` and `ColorEncoding::EncodedUnorm`; alpha
is a host-owned current-pixel value and is not part of the RGB8 LUT samples.
`copyRgb8()` and core `copySamples()` return owned copies. Preserve generation,
monotonic sequence, original `capturedAtNanos`, and `AudioClockDomain.BootTime`.
Android elapsedRealtimeNanos and native CLOCK_BOOTTIME share the declared domain.
Do not relabel System.nanoTime/CLOCK_MONOTONIC observations as BOOTTIME.

Recheck generation, sequence, clock and capture age immediately before the actual
host submission. Age250ms is inclusive; future time rejects. CPU callback delivery
does not prove native/GPU effective state. A host that queues work must repeat the
same validation in its final native call. Call removal on invalid/stopped input.
The Spatial adapter includes an expiry watchdog entry point; the demo calls it
even if no more microphone blocks arrive. Texture hosts must do the same expiry
work independently of new arrivals.

The carrier texture contract uses encoded straight RGB in R8G8B8A8_UNORM. Preserve
current-pixel alpha; panel composition premultiplies later. No implicit gamma or
premultiplication is introduced. Host owns 3D texture allocation, interpolation,
descriptors, barriers/fences, retirement, eye/source geometry and the final effect
readback. This repository defines no JNI endpoint or private renderer catalog.

WP03 distinguishes admitting a new upload from drawing its already selected
texture. Its proposed `validate_lut16_upload` rejects nonincreasing new sequence;
`validate_lut16_for_submission` rechecks an opaque selected value's exact identity
and original freshness on every draw. Reusing that same current selection while
fresh is valid. No draw/upload can refresh its capture age. Kotlin `submit` only
delivers a synchronous callback; the host performs selection/sequence validation.

See `integration/body-swap/` for isolated destination payloads and a shared-callsite
proposal. Only the carrier integrator applies its Activity/manifest/action/native
edits. UI and CLI route audio.start/audio.stop/audio.status through actual typed
permission/lifecycle handlers; no generic extension dispatch or package intents.
No Synedelica APK, optical grid or compositor loader is required for direct LUTs.

The initial style is AuthoredRgbWave. Additional public palette families require
separate supplier/source/license admission. Generic optical-carrier sources remain
with their supplier; clock conversion needs an explicit measured adapter.
