# Audio retirement v2 independent source review

Scope: current `integration/body-swap` delta from `6b94f01`, the v2 continuation,
and the added retirement/worker/runtime tests. Read-only source review; no compiler,
test, Gradle, foreground, device, commit, or remote action was performed here.
Existing core/adapters and the actual carrier were not re-reviewed or qualified.

## Final source verdict

No unresolved P1/P2 source finding remains in the reviewed delta after the
corrections below. Final compiler, contract, public-boundary and publication
verification belongs to the repository integrator and was still pending when
this read-only review finished. This verdict does not qualify the actual carrier.

## Findings corrected during review

### Resolved P1 — Preserve an adopted replacement after a reentrant failing callback

The initial owner invoked a sink under the reentrant owner monitor and then
unconditionally removed its id when that callback threw.
A sink can synchronously replace itself with `registerSink(id, replacement)` and
then throw. Registration has already transferred replacement resource custody and
reserved the process lease; the catch drops that replacement without calling its
`retireAudio`. A subsequent owner ticket can report `sinksRetired=true` while the
adopted replacement's physical resources have never acknowledged retirement.

Final source at `ProductAudioFeatureOwner.kt:268` removes the id only if it still
references the failing sink. The replacement stays in the registered cohort;
the failing sink retires only if no remaining id references it. Added regression
`ProductAudioFeatureOwnerContractTest.kt:305` replaces the failing sink
reentrantly, then verifies the ticket waits for the replacement's exact ACK.
The integrator reported reproducing the original RED before this correction.

### Resolved P2 — Restore the stopped phase when reusing a completed ticket

The initial completed-ticket return invalidated the permission epoch and reset
pending permission to Unknown without resetting the phase. `stop()` with no resources, `start()`
while permission is absent, `stop()`, `start()` leaves `PermissionPending` with
`requested=true` and no live request: the old permission response is rejected by
epoch, while `start()` refuses to call `beginStart` in `PermissionPending`.

Final source at `ProductAudioFeatureOwner.kt:281-285` reconciles the phase when
reusing a finished ticket. Added regression
`ProductAudioFeatureOwnerContractTest.kt:296` stops and retries the permission
request, proves a second prompt is requested, and rejects the old epoch.

### Resolved P2 — Preserve restart intent through synchronous batched sink ACK

The first detached-sink permission repair called `retireInternal`, then restored
`requested=true` and returned. If a current sink synchronously acknowledged a
batch including the detached operation, retirement completed before restart
intent was restored; no later callback remained to resume capture.

Final source at `ProductAudioFeatureOwner.kt:224-226` retries `beginStart` after
restoring intent when cleanup already finished. Added regression
`ProductAudioFeatureOwnerContractTest.kt:281` models that exact batched ACK.
The integrator reported reproducing the original RED before this correction.

## Other corrections re-read

- `ProductAudioFeatureOwner.kt:145` now accepts native observations only for the
  current live capture generation. Retirement/new capture clear cached evidence.
- `ProductAudioFeatureOwner.kt:220` checks detached operations when permission
  completion enters capture without another explicit Start; regression at
  `ProductAudioFeatureOwnerContractTest.kt:271` requires their physical ACK first.
- `AndroidAudioRecordRuntime.kt:81-86` performs all backend failure callbacks
  before `finish(result)`. Worker construction/start failure invokes
  `onStartFailure` before the proven-never-created ACK in
  `ProductAudioWorkerLifetime.kt:33-41`; there is no post-ACK failure callback.
  The injected start-failure regression at `ProductAudioWorkerLifetimeTest.kt:12`
  asserts no operation begins and observes failure callback before ACK.

## Documentation corrections re-read

`shared-root-callsites.proposal.md:5` now requires all six production payloads.
Line 30 correctly permits queued restart on the joining observer or final sink
ACK thread and retains main-thread permission marshaling.

## Reviewed constraints

- The exact ticket identity and detached `toCompletableFuture()` view protect the
  owner's private completion source from caller completion/cancellation.
- Missing capture or sink evidence stays pending; explicit failures are sticky.
  Detached operations are retained in the transaction and late/replayed callbacks
  are bound to their original operation.
- Default process app-owner and microphone gates survive owner/runtime recreation;
  sink-only adoption reserves admission before custody transfer.
- Owner future completion is published outside the owner monitor. Runtime
  gate/state monitors are released before notifying the owner. The capture worker
  is joined from a separate observer, not from its own finally or the Stop caller.
- `status()` is cached and pure; permission reconciliation and generation-scoped
  native observations are explicit mutations.
- Host callbacks still execute under the owner monitor: carrier sink/permission
  implementations must not wait for a callback that needs that monitor.
- Fake Whole and fake sink ACKs demonstrate only the supplier contract. The actual
  carrier Whole must join the exact ticket and every real Scene/GPU resource ACK
  before terminal/fence clear; an Activity join after old Whole success is not
  qualified. Actual carrier/GPU/device/native adoption remains unqualified.
- No new public source path/data/history exposure was found in the reviewed delta.

Status: final corrected source re-read; no unresolved P1/P2 source findings.
Final integrator verification/publication remains pending.
