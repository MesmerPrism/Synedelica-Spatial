# Standalone demo package verification — 14 November 2026

| Evidence | Observed result |
| --- | --- |
| Existing normal Android app assembly | `:demo-spatial:assembleDebug` passed, no product-source/config change |
| APK identity | `io.github.mesmerprism.synedelica.demo.spatial`, min34/target35, actual DemoSpatialActivity |
| Retained debug APK | SHA-256 `c08e0a95f1010e2433e42ab16b64853f5c7468cd761e4fb2c49f9aae0a8096be`,67,883,159 bytes |
| Signing/alignment | Actual v2 debug signature and zipalign4/native-page16 checks passed |
| Manifest | Exactly one launcher/VR activity, intended permissions, SDK0.13.2, only exact non-exported SDK broker |
| DEX inclusion | 5 DEX,4,272 classes,34,309 declared methods,33,096 executable bodies |
| Required code | Authored analyzer/clock/style/LUT, Spatial binding and demo capture/lifecycle method bodies present |
| SDK payload | 6 arm64 libraries,209 assets; all entries hashed |
| Focused JVM/Android tests | 39 test results, zero failures/errors, cached reuse labelled in Gradle log |
| New inspector tests | 15 passed, including adversarial/stale-receipt regressions |
| Public boundary | Existing4 synthetic cases/self-test passed |
| Independent review | Three P2 gate issues fixed; no open P1/P2; actual entry/instruction/receipt hashes matched |

The actual application sources and six-file carrier cohort are unchanged from
ad6a534. First assembly ran with JDK17.0.19, Gradle9.4.1, Kotlin plugin2.1.0,
AGP8.11.1, API35 and Meta Spatial SDK0.13.2 through the ordinary module. The recipe
uses existing caches and own build outputs; no cache clone or shared R.jar edits.

The first evidence-only dependency query failed on an ambiguous Android artifact
view. It was fixed by explicitly selecting `android-classes-jar`; a corrected
recipe completed and recorded14 runtime code artifacts, not a claim of every raw
AAR/native compiler input. A separate pinned-archive audit covers SDK bootstrap,
native/assets and confirms no required Meta plugin configuration change here.

The SDK APK-analyzer batch wrapper misreported JDK compatibility when `findstr`
was absent in a minimal PATH and returned zero. The recipe invokes the SDK's own
Java CLI with the selected JDK and validates the actual decoded XML. It does not
disable a Java compatibility check or modify installed SDK tools.

Review regressions tightened extra-activity rejection and restored old native
outline/operator ZIP/DEX exclusions. Unknown XML encodings, malformed UTF8 ZIP
names, corrupt deflate and invalid DEX now replace stale success with failure.
Four SDK UI sound assets are admitted only by exact path/hash from the fixed
base AAR; references require those verified entries. No SDK/source binary is
vendored and no redistribution right is inferred. Recovered assets stay absent.

Final portable recipe PASS from clean source
`ffb9ac3dbb7accc235146b8345011307d26caae5`: source_dirty=false, unchanged
before/after inventory,39 test results and all actual package checks. The source
inventory receipt SHA-256 is
`02e3773311fe6b4c806c8ec34f3ea67507ee6c62165763a638b6aba7fd36881d`.
The typed14-artifact runtime-code view hash is
`d5864b1326fb29576211acffb391d90ecd54c7a94328f1b7be8291b459a27ed0`.
The package receipt hash is
`ae866a0a3a92ee9e4deddc16db3243c34c995f51ae4610a7fdf647cae0f8bd6b`.
The cached recipe reused73 tasks and executed the dependency-pins task; it is
not a cold rebuild or deterministic-build claim. Original assembly executed41
tasks and produced the same retained APK. Raw failed/successful attempts, all
tool/source/dependency hashes and APKs stay ignored. The long-build lease was
released after success. Publication is source/packaging evidence only.

That reviewed source was pushed to selected main and independently observed at
the verified PUBLIC remote through Git refs and GitHub's commit API. The containing
ref adds this outcome/continuation, with exact final readback stored locally.

No installation, recorder, foreground UI, ADB, headset or wearer work occurred.
DEX inclusion does not prove runtime execution or effect equivalence. ZIP page
alignment does not certify ELF/native runtime compatibility. Actual startup,
passthrough/audio behavior, visibility, native outlines, carrier Whole/GPU adoption
and physical sink/resource ACK remain separate unqualified gates.
