# Source and host verification — 13 November 2026

This is a source/host checkpoint. It does not qualify an APK or headset outcome.

| Check | Actual result |
| --- | --- |
| `:core:test` | 20 tests, zero failures/errors |
| `:adapter-texture:test` | 4 tests, zero failures/errors |
| `:adapter-spatial:testDebugUnitTest` | 6 tests, zero failures/errors |
| `:demo-spatial:testDebugUnitTest` | 9 tests, zero failures/errors |
| Carrier pure owner/controls/time suite | 9 cases passed with real core |
| Carrier Android runtime compilation | All four payloads compiled against real API35 android.jar |
| Carrier Python test registration | Passed against copied handoff source staging; nine inner cases passed |
| Public boundary synthetic unit tests | 4 passed; self-test rejected forbidden dependency before clean fixture |
| Demo XML and APK-helper PowerShell syntax | Parsed successfully |
| Private reference preservation | Selected reference hashes and Git status unchanged |

The Gradle command in README completed successfully using JDK17, Gradle9.4.1,
Kotlin2.1.0, AGP8.11.1, Android API35 and Meta Spatial SDK0.13.2. This command
compiled actual Android demo/adapter code before unit tests. It did not run
assembly, native build, signing, installation, device or ADB commands. Standard
Gradle/Kotlin deprecation warnings remain; they did not fail this pinned build.

Tests use authored synthetic inputs: fragmented PCM windows, Short.MIN_VALUE,
bounded onset waves and retirement, zero strength identity/all cube points,
array ownership, stale/future/capture-regression rejection, inclusive250ms,
delayed final submission, queued Stop/restart, in-flight Scene Stop concurrency,
permission denial, process admission/recreation and failed-release barriers.

Independent source review found three P2 issues, repaired before checkpoint:
future hardware-anchor validation now lives in one pure helper used by both
capture hosts; the texture module exports its public core dependency through
Java library API metadata; final Scene submission serializes with logical Stop.
Failing timestamp and Scene regression tests preceded their fixes. The final
Gradle run includes those tests and accepted the dependency metadata correction.

The explicit provenance ledger covers every source/document/test/tool file.
The boundary gate checks current admitted hashes plus each reachable Git commit's
own ledger/tree/hash closure and excluded payload/dependency content. Git review
and independent remote SHA readback complete the source handoff. Raw logs,
reference selections and remote receipts remain ignored local evidence.

Remaining: actual carrier callsites/JNI/GPU selection and effective readback,
APK package/signature/alignment, installed bytes, microphone clock/overrun
calibration and hardware response, Scene visibility and wearer qualification.
The optional APK checker performs partial identity/foreground checks only.
