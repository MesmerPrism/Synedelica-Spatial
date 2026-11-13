# Audio retirement contract repair

The carrier's integration audit identified three source-contract gaps in the
initial handoff: runtime-local admission does not persist host recreation;
void sink removal proves a publication barrier but no physical-resource ACK;
and status observation invokes permission cleanup and can mutate the graph.

This repair is scoped to supplier payloads and headless host tests. The carrier
integrator alone applies its actual Whole/Original, Activity, fence and native
callsites. No foreground, APK, headset, private app or shared carrier source work.

1. Reproduce pure status mutation, missing physical ACK and recreation admission.
2. Supply a version2 app-owned retirement ticket with immutable completion result.
   Join microphone release and all attached/detached sink retirement acknowledgments.
   No acknowledgment stays pending; failure is sticky and blocks replacement.
3. Retain app-owner and physical microphone admission across host/runtime instances.
4. Keep status pure; permission reconciliation and native observations are explicit.
5. Review fake Whole join ordering, repeat/old/failure callbacks, restart and no-audio
   path. The SAME carrier Whole transaction must join audio before terminal/fence
   clear; an Activity allOf after a successful old Whole is insufficient.
6. Update exact payload/schema/provenance and source continuation, scoped commit,
   non-force remote checkpoint and independent SHA readback. Initial6b94f01 remains
   the source baseline; no actual carrier or device adoption is inferred.

Source outcome: reviewed repair f57fa9d5093f3ae9cba5fbccc0f43bc64138ae07 was
fast-forwarded to main, non-force pushed and independently read back from Git
and GitHub.22 owner cases,5 worker/runtime tests,4 boundary cases and the copied
consumer registration passed. The62-file closure passes current/history gates.
Actual carrier Whole/physical GPU/Android microphone/device adoption remains open.
