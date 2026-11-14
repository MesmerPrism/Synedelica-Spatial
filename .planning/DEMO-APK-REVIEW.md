# Demo APK milestone independent review

Date: 2026-11-14. Base: `ad6a5341f0fc185a89d81b1dd1b880b3eaa7a0c7`.
Branch: `codex/demo-apk-milestone`; reviewed the live tooling/test/documentation changes.
Review is read-only except this ignored report. No Gradle, build, device, ADB,
installation, foreground UI, commit or push was performed by this reviewer.

## Assessment

Three P2 gate issues were reproduced, corrected and independently rechecked.
No open P1 or P2 finding remains. All 15 final inspector tests pass independently.
The corrected source is ready for the integrator's final retained portable recipe
and scoped source checkpoint; this review does not claim that later run occurred.

These are reusable gate defects, not evidence of forbidden data in the retained
APK. The actual inspected APK and all receipt readbacks checked here remain
consistent. Package inclusion does not establish valid runtime presentation.

## Findings

### P2: Reject every additional activity, including a non-launcher activity

Location: `tools/inspect_demo_apk.py`, `parse_manifest`, current lines 369-379;
original review lines 368-376.

The original check required one expected demo activity and rejected only other
activities carrying LAUNCHER. An additional exported activity with no intent
filter therefore passed the supposed manifest component closure. Reproduction:
append `<activity android:name="synthetic.ExtraActivity" android:exported="true"/>`
to the synthetic application's XML; `parse_manifest` returned `errors=[]`.

Required fix: require exactly the one expected activity, retaining the existing
exported/launcher/VR checks. Cover exported extras, internal extras, and duplicate
expected activities.

Status: **closed**. Fix observed at current lines 371-372; exported-extra,
internal-extra and duplicate-demo regression cases passed independently.

### P2: Preserve the existing native-outline/operator payload exclusions

Location: `tools/inspect_demo_apk.py`, `EXCLUDED_PAYLOAD`, current lines 43-48;
original review lines 43-47.

The previous `Check-DemoApk.ps1` explicitly rejected `passthrough_outline` and
`synedelica_operator`. The new exclusion expression initially omitted both.
Independent synthetic APK probes containing `assets/passthrough_outline.glsl`,
`assets/passthrough_outline.bin`, or `assets/synedelica_operator.json` each returned
`valid=True`. That is a regression in the existing excluded payload boundary.

Required fix: restore both case-insensitive exclusions for ZIP paths and DEX
string references, with regressions in both paths.

Status: **closed**. Fix observed at current line 48; all three ZIP-name cases and
their DEX string-reference counterparts passed independently.

### P2: Convert XML/ZIP decoding faults into a retained failure receipt

Location: `tools/inspect_demo_apk.py`, `parse_manifest`, current lines 318-321,
and `inspect_apk` exception handler, current line 470.

`ET.fromstring` raises `LookupError` for an unknown declared encoding, while the
XML decoder catches only `ET.ParseError` and the outer handler does not catch
`LookupError`. The CLI consequently exits with a traceback before rewriting its
output. A synthetic input beginning with
`<?xml version="1.0" encoding="nonexistent-encoding"?>` exited 1 and left a
preexisting receipt `{ "valid": true }` unchanged. This contradicts the intended
malformed-input/stale-success behavior already tested for bad DEX/deflate.

Required fix: translate the XML encoding lookup failure into `InspectionError`
and add a CLI regression proving that this input replaces an old success receipt
with `valid=false`. Preserve nonzero exit status. A second reproduction affects
ZIP names: mark a central-directory filename as UTF8 and set its first byte to
`0xff`. `zipfile.ZipFile` raises uncaught `UnicodeDecodeError`, also leaving the
old success receipt untouched. Catch `UnicodeError` at the malformed-input
boundary and cover that corrupt ZIP filename regression too.

Status: **closed**. `parse_manifest` now normalizes `(ET.ParseError, LookupError)`
at lines 320-321; the outer failure boundary catches `UnicodeError` at lines
470-471. Both CLI regressions passed independently and verify that old success
receipts become `valid=false` while the exit status remains nonzero.

## Reviewed evidence and boundaries

- All 11 original synthetic inspection tests passed independently, followed by
  all 15 final tests after the three corrections. The integrator's retained
  `local/demo-apk-20261114/inspector-final15-tests.log` matches this result.
- `local/demo-apk-20261114/final15-inspection.json` records `valid=true`, no
  errors and the same exact retained APK SHA-256 after the final corrections.
- `git diff --quiet ad6a534 -- core adapter-texture adapter-spatial demo-spatial`
  exited 0: the app, core, adapters and carrier source bytes remain unchanged.
- Fresh recipe2 receipts were read at `local/demo-recipe-20261114-002`.
  Source-inventory, runtime-pins and package-receipt hashes match their build
  receipt. The dependency evidence explicitly identifies its typed
  `android-classes-jar` view; this is code-artifact evidence, not a claim that it
  alone hashes every original AAR/native input.
- APK SHA-256 is consistently
  `c08e0a95f1010e2433e42ab16b64853f5c7468cd761e4fb2c49f9aae0a8096be`.
  Every inventoried ZIP entry SHA-256 and all 33,096 recorded method instruction
  range hashes were independently checked against the retained APK bytes, with
  zero mismatches. Observed totals: 5 DEX, 4,272 classes, 209 assets, 6 arm64 libs.
- The wrapper really invokes Android manifest decoding, `apksigner verify`, and
  `zipalign -c -P 16 4`, checking exit status. It uses a new evidence directory,
  checks APK bytes before/after, and retains the raw results. Existing actual
  signature output records v2 success and an Android Debug signer.
- Four SDK OGG exceptions use exact path/hash pairs; DEX reference admission
  requires the matching packaged asset verification. No source asset was added
  and no redistribution grant is inferred.
- DEX evidence records declared nonabstract bodies and byte ranges. It does not
  perform complete Android bytecode verification, execution, SDK startup, native
  runtime/ELF compatibility, headset effects, microphone or retirement testing.
- Public changes remain authored tooling/tests/docs; APKs, private paths and raw
  evidence remain under ignored local directories.
