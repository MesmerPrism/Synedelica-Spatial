# Audio retirement version2 host verification

| Check | Actual outcome |
| --- | --- |
| Standalone owner contract suite | 22 named cases passed |
| Real-thread lifetime and default runtime admission | 5 JUnit cases passed |
| Copied carrier fixture/Python registration | 1 test passed,22 inner cases passed |
| Android compilation | All six production payloads compiled with real API35 android.jar |
| Boundary tests and self-test | 4 cases passed; forbidden/clean synthetic fixtures discriminated |
| Independent source review | No unresolved P1/P2 after recorded corrections |
| Source/history closure | 62 explicit admitted files; every reachable commit ledger/tree/hash passed |
| Source remote readback | f57fa9d5093f3ae9cba5fbccc0f43bc64138ae07 matched Git refs and GitHub API |

The baseline6b94 runtime was compiled separately with the new default-runtime
admission regression; it failed as expected because two instances admitted at
once. That probe never started a worker or microphone. The final runtime test
only admits sessions then cancels before Start, proving process gate/actual
no-recorder completion without invoking AudioRecord. The worker tests exercise
real independent capture/join threads with scalar dummy operations, including
callback blocking, release failure, worker exception and a synthetic start failure
whose notification must drain before the actual never-created acknowledgment.

Owner regressions reproduced effectful status, sink-only recreated-owner overlap,
pending-permission detached-resource admission, cached lifecycle/permission wedge,
synchronous batched-ACK lost intent, and reentrant replacement loss. Final tests
also cover all capture and sink physical facts, partial evidence, sticky failure,
old/foreign/replayed callbacks, read-only completion views, late native observations,
no-audio completion and fake Whole join-before-terminal ordering.

Final raw logs are ignored local evidence. Core, texture/Spatial adapters and demo
source were unchanged; their initial39 Gradle tests are reused, not claimed rerun.
No Gradle/APK/native build, ADB, foreground UI, recorder, device or wearer action
occurred in this repair. Compiler/type/host tests do not qualify real microphone
clocking, actual Whole adoption, Scene/GPU/fence retirement or native readback.

The carrier alone binds the exact supplier ticket/custody into its existing Whole,
including retained/no-media and zero-attachment finish, before global terminal or
fence clear. No final Whole JSON or permission/device proof was invented.
