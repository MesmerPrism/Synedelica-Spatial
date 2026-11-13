<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!-- Independently authored review; no private source or assets examined. -->
# Initial public source closure review

**Verdict: no open P1/P2 source findings.** The three P2 findings raised during
review were corrected and the final host/compiler checks are green. This verdict
qualifies the reviewed source slice, not an APK, headset or native carrier.
Final manifest generation, scoped root commit, reachable-history gate and remote
publication/readback remain the repository integrator's steps.

Review scope: repository AGENTS.md, docs/ABI.md, .planning/PLAN.md, current pure
core, texture and Scene adapters, standalone demo, carrier payloads/proposal,
boundary checker, build declarations and synthetic tests. The integrator owns
compilation and publication. This reviewer inspected final source and existing
test evidence without running a competing Gradle build, accessing a device,
changing implementation files, committing or performing remote operations.

## Findings resolved

1. **Future/inconsistent hardware anchors could derive an apparently fresh
   timestamp.** The shared
   `core/src/main/kotlin/io/github/mesmerprism/synedelica/core/AudioCaptureTime.kt:21`
   now rejects invalid/future anchors and uses checked arithmetic. Both the demo
   and carrier delegate to it. `AudioCaptureTimeTest` includes the prior failing
   counterexample `(firstFrame=0, anchorFrame=48000, anchorNanos=2000000000,
   sampleRate=48000, nowNanos=1100000000)`, plus age boundaries, invalid inputs and
   overflow/nonpositive-result cases. The timestamp is never rebased to arrival.

2. **The texture module did not export public core types.**
   `adapter-texture/build.gradle.kts:2` now applies `java-library` and declares
   `api(project(":core"))`, matching the `LutFrame`/`AudioClockDomain` types in its
   public API. The actual Gradle build accepted the corrected dependency model.

3. **Stop could return while an old Scene submission was in flight.**
   `adapter-spatial/src/main/kotlin/io/github/mesmerprism/synedelica/spatial/SpatialLutAdapter.kt:62`
   now serializes final run/freshness validation and Scene application with
   retirement through the same monitor. The deterministic
   `stopWaitsForInFlightSceneCallBeforeReturning` case failed before the fix
   (`local/red-scene-race.log`) and passed in the final run. Queued Stop/restart
   rejection and post-preparation freshness checks remain covered.

Two integration/publication discrepancies were also corrected during review:
the texture contract now has immutable `contractVersion`, `order` and `encoding`
fields with assertions; history inspection now visits each reachable commit
tree, checks its own admission ledger, regular paths/classifications and hashes,
and scans forbidden content. The final generated ledger and post-commit history
check are publication evidence, not inferred from this pre-commit review.

## Reviewed behavior

No additional concrete core/texture functional defect was found. PCM16
normalization handles Short.MIN_VALUE; partial windows retain scalars only;
generation reset, bounded onset bands, disabled-strength consumption, stale
clearing, defensive sample ownership, RGB order, positive capture time and
inclusive 250ms final callback checks are coherent with the admitted ABI.

The carrier owner uses one capture/analyzer per owner, preserves immutable
snapshot fanout, keeps camera observation independent, rejects retired callbacks,
and blocks replacement until successful release acknowledgement. Failed release
retains a terminal cleanup barrier. Permission denial preserving requested=true
is explicitly documented/tested and does not imply enabled capture. The carrier
proposal honestly leaves cross-Activity retirement-gate transfer and actual
app/native callsites with its embedding integrator. It does not claim that the
payloads are installed or a native LUT is effective.

The demo has process-shared admission and monotonic generations across recreated
hosts. Failed release permanently blocks process admission. Its lifecycle and
permission completions are serialized; stopped work is retired before restart.

Only authored source and synthetic fixtures were visible in the reviewed
closure. No private/recovered asset dependency or imported historical application
commits were found. Git history was unborn during source inspection; verifying
the final first root commit and exact public inventory remains necessary.

## Final evidence and limits

`local/gradle-final.log` ends with **BUILD SUCCESSFUL**. The corresponding final
JUnit XML reports **39 tests, zero failures/errors**: core 20 (15 core and five
timestamp cases), texture four, Scene six, demo nine (seven lifecycle and two
process-admission cases). Real Android API35/Meta SDK0.13.2 compilation is included
for the Scene adapter/demo. `local/carrier-final.log` reports nine pure carrier
contract cases passed; the integrator reports its cached compiler invocation
included all four carrier payload files against the real Android API35 jar.
The integrator also reports the four boundary unit cases/self-test passed.
Earlier files named `*-green.log` contain superseded failures and are not the
final evidence cited here.

These checks do not establish APK packaging/signatures/alignment, installed
bytes, Android permission UI, hardware timestamp calibration/overruns, microphone
stop latency, Scene startup/teardown, headset controls/appearance, colour-space
behavior, GPU/compositor/native-outline output or effective native readback.
Those remain explicitly unqualified. Publication is a source checkpoint only.

## Final carrier registration addendum

Reviewed `integration/body-swap/payload/tests/test_product_audio_owner.py` and
the exact fixture mapping in its README. Once copied to the carrier's `tests/`
directory, `parents[1]` resolves the intended carrier root. The fixture target
`tests/fixtures/product_audio/AudioOwnerChecks.kt` matches the actual standalone
main class, independent of its file name. The wrapper compiles the three copied
pure owner/controls/time sources against the selected supplier core; it excludes
the Android recorder intentionally. The cache-only harness supports `--main`,
propagates compiler/assertion failures and uses fresh class output per invocation.
It does not silently skip absent source inputs or a missing checkout setting.

The supplier checkout path is supplied by the integrator through
`SYNEDELICA_SPATIAL_ROOT`. Revision pinning/verification is an integrator
responsibility; the wrapper checks path presence, not Git revision or cleanliness.
The corrected README states that boundary directly, and its timestamp payload
row now sits inside the destination table. These documentation corrections were
re-read and accepted.

Observed `local/carrier-registration.log`: one Python unittest passed, running
the nine carrier contract cases successfully against copied payloads in the
integrator's ignored staging tree. This reviewer did not rerun compilation or
edit the actual carrier. No additional P1/P2 source finding was identified in
this mechanical registration change.
