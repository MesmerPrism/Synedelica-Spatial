# Standalone demo packaging/config review

Date: 2026-11-14. Reviewed source HEAD: `ad6a5341f0fc185a89d81b1dd1b880b3eaa7a0c7`.
Scope: read-only source/archive/bytecode audit. The only repository write is this ignored report. No Gradle, compiler, ADB, headset, private reference, recovered data, source edits, commits, or pushes were used by this audit. Existing six-file v2 carrier cohort remains untouched.

## Result

No required Meta Spatial plugin/bootstrap/manifest correction was identified for the existing standalone demo's built-in Toolkit/VR path. Plain Android application/library plugins plus the 0.13.2 AAR dependency graph supply the classes, generated built-in components/resources, native libraries, shaders, and runtime initialization used here. Applying `com.meta.spatial.plugin` is not necessary merely to package these existing components or to invoke the `AppSystemActivity` startup path.

This is an archive/source/config conclusion, not an observed launch. The integrator's real assemble and resulting DEX/merged manifest/native/assets checks are still necessary. Successful build/package checks do not qualify headset startup, microphone behavior, panel visibility, passthrough/LUT effect, timing, or retirement on hardware.

## Existing consumer configuration

- `demo-spatial/build.gradle.kts`: Android application plus Kotlin Android; namespace/application ID `io.github.mesmerprism.synedelica.demo.spatial`; compile/target 35, min 34, Java/Kotlin JVM17; arm64-v8a; dependencies on `:core`, `:adapter-spatial`, base/toolkit/vr 0.13.2. Preserve this distinct ID.
- `adapter-spatial/build.gradle.kts`: Android library plus Kotlin Android; `api(:core)` and base SDK implementation. Its binding calls public `Scene.setPassthroughLUT(Lut?)` through its Android main-thread port.
- `DemoSpatialActivity` extends `AppSystemActivity`, supplies `VRFeature(this)`, registers the XML panel, enables passthrough in `onSceneReady`, and creates built-in `Panel`, `Transform`, `Visible` components. It does not declare a custom component schema, custom shader, Spatial Editor scene, or app native library.
- Main manifest already declares RECORD_AUDIO, OpenXR permissions, VR headtracking/passthrough/GLES features, supported Quest devices, focus awareness, required device-provided `libossdk.oculus.so`, and an exported MAIN/LAUNCHER/VR activity. No INTERNET permission is admitted. No `NativeActivity`/`android.app.lib_name` declaration is required for the inspected Java `VrActivity` subclass path.
- `demo_controls_panel` is an authored Android resource ID, defined in `res/values/styles.xml`; it is not a missing generated component/schema ID.

## Cached 0.13.2 runtime evidence

Primary artifacts are under `%USERPROFILE%/.gradle/caches/modules-2/files-2.1/com.meta.spatial/<module>/0.13.2/<cache-key>/<module>-0.13.2.{aar,jar}`. SHA-256 pins:

| Module | Gradle artifact cache key | SHA-256 |
|---|---|---|
| `meta-spatial-sdk` | `4021973675a22b422fbfefac4f6e49151d3ca418` | `4902d21f7f02c874a7ee2d5e2ea88ee3a355caf6388e87b5771551f7a89fbe92` |
| `meta-spatial-sdk-toolkit` | `bf64f0abe772cf1031cbd6ee11e298a57b8ea14` | `021e16a741c751d1e52fff6660b8ea0ca2ae871005c2952a629937c134a79b35` |
| `meta-spatial-sdk-vr` | `c9711162e2044094ea57f93066442b4f2e6786b9` | `959877166d0f4339df6977daceebdcfde8856b35763c7fb8077dae9e80131d25` |
| `meta-spatial-sdk-isdk` | `8349b9dc6905080adb127ad9a299b0a1cb100511` | `cb188e3bdb061b4f9e41392160fbd4153dfd88574ed80edff6b7c5ac181a1456` |
| `spatial-gradle-plugin-impl` | `365ba14912adde8e47384806b38f80ef506afccf` | `7a48f293b7d632f57efe6b92803804ef4e4720b14f10439dc61b9fedf78d4423` |

The cached descriptor records are under `%USERPROFILE%/.gradle/caches/modules-2/metadata-2.107/descriptors/com.meta.spatial/<module>/0.13.2/26c913274550a0b2221f47a0fe2d2358/descriptor.bin`. Read-only printable-string inspection records base -> okhttp4.9.1/serialization-json1.6.3; toolkit -> base/coroutines-core+android1.10.1; VR -> base/toolkit/ISDK0.13.2; ISDK -> base/toolkit. The integrator's resolved runtime graph should confirm exact selected transitive versions.

### Startup and built-in registration

JDK17 `javap -c -p` on extracted AAR `classes.jar` establishes:

1. `AppSystemActivity` extends `com.meta.spatial.runtime.VrActivity`. `AppSystemActivity.registerSystemFeatures()` constructs `ToolkitFeature(GLXFManager, spatial, systemManager, context)`.
2. `ToolkitFeature.componentsToRegister()` calls the already-compiled `ToolkitComponentRegistrations.all()`. Toolkit's `Panel`, `Transform`, and `Visible` static initializers load component/attribute IDs from `com.meta.spatial.toolkit.R$id`. They require ordinary Android resource merging; they do not require regenerating SDK component classes in the consumer.
3. `VrActivity.onCreate()` combines system and app features, attaches the library loader, loads `MetaSpatialSDK`, calls feature `preRuntimeOnCreate`, creates the SpatialInterface, and initializes the runtime with the Activity. The app does not need a separate custom Application initializer for this path.
4. `VRFeature`'s default constructor selects `VrInputSystemType.INTERACTION_SDK`; `preRuntimeOnCreate` loads `openxr_loader` and `MetaSpatialSDKIsdk`. Thus the **transitive ISDK runtime must be included**. It is recorded in cached VR metadata, and the ISDK AAR contains `libMetaSpatialSDKIsdk.so`; the app should not exclude it.
5. `VrActivity.onCreate()` first calls `SpatialIdsBootstrap.installIfPresent(context)`. That reads `assets/spatial_ids.json`; `FileNotFoundException` returns false without throwing. A class-byte scan found `SpatialIdRegistry` references only in `SpatialIdRegistry` and `SpatialIdsBootstrap` among base/toolkit/VR/ISDK jars. Built-in Toolkit classes in this exact release instead use Android R IDs. Absence of this asset is therefore not a demonstrated bootstrap defect for this existing demo. This does not generalize to future generated/custom components.

### Manifests and native/assets closure

- Base AAR manifest contributes `com.meta.spatial.SDK_VERSION=0.13.2` application metadata. Toolkit and VR contribute empty applications. ISDK contributes the non-exported `com.meta.spatial.channels.ChannelBrokerService`; it must survive normal merging.
- Base AAR carries arm64 `libAetherGlobals.so`, `libMetaSpatialSDK.so`, `libMetaSpatialSDK_1.so`, `libopenxr_loader.so`, and `libthird-party_zlib_1_3_1_z.so`; ISDK adds `libMetaSpatialSDKIsdk.so` and an identical zlib copy. The duplicate zlib bytes have SHA-256 `1bc681cfd0c201d4366d381485b9c7ec249d743e92e49d4980292915f9ac7138` in both AARs. Let the actual Android merge result determine whether any narrow packaging declaration is needed; no speculative exclusion is recommended.
- Read-only ELF64 DT_NEEDED parsing found no app-provided C++ library missing from the base/ISDK archive closure. Non-Android-system dependencies are the listed SDK libraries. In particular, none of these libraries declares `libc++_shared.so` as DT_NEEDED. `libossdk.oculus.so` is separately device-provided and already declared in the app manifest.
- Base AAR already carries `assets/shader_manifest.bin`, precompiled SPIR-V shader assets, renderer shader, and SDK common resources. Existing demo uses these shaders rather than app-authored shader sources. SDK vendor resources in the dependency are distinct from the frozen original app's assets.

## What the optional plugin does

Cached `spatial-gradle-plugin-impl-0.13.2.jar` contains component XML resolution/generation/export, Spatial Editor CLI scene export, custom shader extraction/compilation, and hot reload tooling. `SpatialPlugin.setupApp` wires generated component Java/resources and exported component XML assets; its custom shader task branch checks that shader sources are present and nonempty. The jar does not add an app native bootstrap class or Android manifest transform in its inspected setup path. Existing demo has no input requiring those generators/exporters.

`SpatialExtension.allowUsageDataCollection` has a false convention. `TelemetryBuildService` and the jar's Windows `native/logeventjni.dll` are host build telemetry machinery. They are distinct from the AAR's Android runtime `.so` closure. Do not add plugin/telemetry/build-tool downloads merely to obtain existing AAR native classes or shader assets.

## Integrator APK verification targets

After the real standalone assemble, inspect the resulting artifact rather than upgrading this audit into a runtime claim:

- Unique package ID and DemoSpatialActivity launcher; intended SDK levels, RECORD_AUDIO, focus/VR/passthrough declarations, libossdk requirement, SDK_VERSION, and merged ISDK ChannelBrokerService; INTERNET remains absent.
- DEX includes authored demo/capture/core/adapter classes plus Meta VrActivity/AppSystemActivity/ToolkitFeature/VRFeature/ISDK support; XML layout/theme/resource IDs are present.
- APK arm64 native closure above and precompiled SDK shader assets are present. Confirm no unintended ABI or custom/private assets.
- Record assemble result, artifact hash, signing/alignment/package evidence as their own milestone. This audit proposes no source or feature change.

## Official-source corroboration and limits

The current official [StarterSample manifest](https://raw.githubusercontent.com/meta-quest/Meta-Spatial-SDK-Samples/main/StarterSample/app/src/main/AndroidManifest.xml) uses the same Java activity/VR intent, supported-devices/focus-aware metadata and libossdk declaration; it does not use `android.app.lib_name` or OpenXR broker queries. The current [StarterSample build configuration](https://raw.githubusercontent.com/meta-quest/Meta-Spatial-SDK-Samples/main/StarterSample/app/build.gradle.kts) also applies the plugin and exports a Spatial Editor scene, which is an extra input absent from this demo. These mutable main-branch samples are corroboration only, not an exact0.13.2 requirement. The primary conclusion above comes from the locally pinned0.13.2 archives/bytecode.

## Integrator packaging readback

The integrator subsequently reports the unchanged sourcead6 real standalone assemble PASS, v2 signature and16KiB zip alignment PASS; decoded actual APK manifest contains `com.meta.spatial.SDK_VERSION=0.13.2` and `com.meta.spatial.channels.ChannelBrokerService` with exported=false. This matches the pinned cached ISDK AAR contribution above; it is SDK infrastructure, not an authored background microphone service. The actual APK remains uninstalled. Artifact/hash and full assembler/DEX/manifest evidence are owned by the integrator's milestone receipt; this audit does not duplicate or expand those claims into runtime qualification.
