# Synedelica Spatial

An audio-reactive colour library and standalone Meta Spatial SDK demonstration.
The initial implementation uses a newly authored RGB wave and quiet onset bands.
The library accepts immutable observations; the foreground app owns its microphone.

| Module | Responsibility |
| --- | --- |
| `core` | Pure Kotlin fixed-window audio features, bounded bands and LUT16 frames |
| `adapter-texture` | Immutable RGB8 upload values and synchronous freshness gate |
| `adapter-spatial` | Main-thread Meta Scene LUT binding and retirement |
| `demo-spatial` | Explicit Start/Stop, permission and foreground capture |
| `integration/body-swap` | Reviewed carrier patch payloads; integration owned by carrier |

Capture identity survives `AudioSnapshot -> LutFrame -> TextureLutUpload`:
generation, sequence, original capture timestamp and BOOTTIME clock. A frame older
than 250ms, from the future or from a retired generation removes modulation.
Hosts recheck at the final submission, including after queueing. Raw camera launch
does not depend on audio permission. PCM stays ephemeral and is never transmitted.

The source checkpoint has focused host verification; it is not an APK or headset
qualification. Native outlines and an optical compositor carrier remain experimental
and are outside this build graph. No recovered 2023 assets or original-profile
fixtures are included. See [provenance](docs/PROVENANCE.json),
[integration contract](docs/INTEGRATION.md) and [continuation](.planning/CONTINUATION.md).

Use JDK17 and Gradle9.4.1 with an installed Android API35 SDK:

```text
gradle :core:test :adapter-texture:test :adapter-spatial:testDebugUnitTest :demo-spatial:testDebugUnitTest
python tools/check_public_boundary.py --self-test
python tools/check_public_boundary.py --history
```

`tools/run_host_tests.py` provides an additional cache-only Kotlin2.1.0/JUnit4
runner for selected pure sources; it does not substitute for Android compilation.
No SDK binaries, build packages or signing keys are stored in this repository.

Synedelica and its original creator are the creative reference for this work.
Thanks also to **phreax**, whose public
[cyberdelic hackathon project](https://github.com/phreax/cyberdelic_hackathon_boilerplate)
documents related audio-reactive passthrough work. This credit grants no asset
redistribution rights. This repository implements its own cleared style and tests.

Licensed under AGPL-3.0-or-later; see [LICENSE](LICENSE) and [notices](THIRD_PARTY.md).
