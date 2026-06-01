# Voxy/Sodium Production Hardening Contract

## Summary

The production target is not a Voxy debug overlay that happens to draw in the same frame as Sodium. The target is a Sodium-compatible far terrain provider where Sodium owns near chunks, Voxy owns only provider-approved far terrain, and every mesh in a draw list has passed ownership, material, lifecycle, and render-pass checks.

This document records the code-side rules that should exist before the mod is treated as production ready.

## Things That Must Not Exist In The Final Build

- A separate Voxy overlay renderer that competes with Sodium for terrain ownership.
- Late provider masks that hide bad traversal output after command generation. Provider render lists must be the source of draw commands for provider-owned terrain.
- Any player-facing mode where Voxy disables or hides Sodium near chunks during normal gameplay.
- Any blank ownership interval between vanilla/Sodium chunks and Voxy LoDs.
- Any parent LoD rendered at the same ownership cell as a finer child LoD.
- Any invalid palette, unresolved block state, invalid biome/light data, or corrupt tile rendered as black/default/random geometry.
- Mixed render-pass terrain drawn as opaque fallback. SOLID, CUTOUT, FLUID, TRANSLUCENT, and DEBUG must be classified and routed independently.
- CUTOUT, FLUID, or TRANSLUCENT terrain drawn through the SOLID provider path.
- Shader/Iris paths that mutate or hide Sodium near terrain when required state is incomplete.
- Async mesh uploads accepted after the source section, epoch, world, or renderer lifecycle was invalidated.
- Static string-only tests as the main correctness proof. They can guard architecture names, but data, hierarchy, ownership, and mesh proof must run on real objects.
- Screenshot-only correctness gates. Screenshots are final confirmation, not the primary proof.
- Debug harness controls, untyped system properties, or `voxy_only` behavior leaking into normal player runtime.
- Unclear storage authority. Runtime must report which source supplied a tile, whether it was trusted, and why it was rejected.

## Code-Side Guard Added In This Pass

Provider mesh approval now has an explicit terrain pass mask. Built sections derive provider pass eligibility from their generated quad ranges:

- unsupported translucent/fluid-like data produces an empty provider pass mask and is rejected;
- CUTOUT-only data is classified as CUTOUT and excluded from production drawing until the CUTOUT path is proven;
- SOLID data is eligible for the SOLID provider list;
- CUTOUT remains non-drawing until independent CUTOUT command generation is proven.

This prevents a common production failure: visually incorrect leaf, alpha, water, or mixed-pass geometry entering the opaque far-terrain path and showing up as wrong colors, black blocks, or sheet artifacts.

## Gemini Issue Closure Pass

The `implementation_plan.md` and `fifty_issues_report.md` review identified additional production gaps. The following deterministic code-side items are now closed:

- Surface preview top-face-only mode defaults to enabled, preventing preview sections from drawing full volumetric shell sides by default.
- Imported sparse or corrupt nonzero block-state mapping gaps fall back to solid stone instead of air, preventing missing mappings from becoming mountain holes.
- Depth framebuffer resize validates framebuffer completeness with `glCheckNamedFramebufferStatus` and fails closed on incomplete driver state.
- Material resolution uses a per-thread cache keyed by block state, biome, packed light, and terrain pass.
- Sodium lifecycle integration exposes and invokes a world-change clear hook that resets provider state and chunk-bound state before renderer shutdown.
- Service manager shutdown is synchronized and rejects new services after shutdown starts.

These are code-side fixes only. They do not prove final visual parity with Sodium, Iris, biome tint blending, fog, or shader color-space behavior.

## 100-Issue Hardening Follow-Up

The first follow-up slice closes additional source-level blockers from the 100-issue review:

- `voxy_only` is harness-only and requires `voxy.allowHarnessVoxyOnlyMode=true`; normal gameplay can no longer disable Sodium near chunks through visual attribution mode alone.
- Provider draw decisions no longer call the mutating Sodium pass gate while only computing a draw decision.
- Boundary coverage now lets current exact/fallback coverage clear stale misses, while current rejected/missing coverage still fails closed.
- MDIC provider render-list CPU state is capped to the provider SSBO capacity so diagnostics and draw dispatch do not claim more ids than were uploaded.
- Serialized section loading now rejects too-short buffers, invalid LUT payload bounds, and per-voxel LUT indices outside the decoded LUT before unsafe reads.
- Server LoD tile stores expose deterministic close, and client cache swaps close the old mounted store.

These changes reduce production risk around near-field ownership, stale diagnostics, corrupt storage data, and cache lifecycle. They still do not complete shader parity, full pass separation, Sodium material equivalence, or live visual correctness.

## Production Architecture Rules

The provider is the runtime authority for far terrain:

- traversal may discover candidates, but provider render lists decide what can draw;
- render lists must contain only approved mesh ids;
- stale uploads and invalidated sections must be removed before the next snapshot;
- parent fallback is allowed only when exact child coverage is missing or rejected;
- parent meshes are suppressed anywhere child/finer ownership exists;
- diagnostics fail closed on ownership conflicts or invalid rendered geometry.

Sodium remains the near-field authority:

- Minecraft render distance controls the total visual terrain distance when Voxy is enabled;
- real chunk radius is capped for performance;
- Voxy begins in an overlap ring before the real chunk edge;
- normal gameplay keeps Sodium chunk rendering enabled.

## Verification Gates

Code-side gates:

- `.\gradlew.bat testClasses --no-daemon --console=plain`
- `java -cp 'build\classes\java\test;build\classes\java\main' me.cortex.voxy.client.core.rendering.VoxyHandoffPolicyTest`
- `.\gradlew.bat voxy_lod_correctness_proof --no-daemon --console=plain`
- `.\gradlew.bat build --no-daemon --console=plain`

Live visual gates remain last. They must confirm no blank handoff band, no random blocks in the gap, no black invalid LoDs, no parent/child sheet conflicts, and no shader-depth/fog corruption.

## Remaining Deep Work

- Replace every remaining traversal-first provider path with provider-owned command generation.
- Split CUTOUT into an independently proven provider pass before enabling it at runtime.
- Add real Sodium-equivalent biome tint blending, fog parity, and depth parity validation.
- Add corruption tests for sparse palettes, missing biome/light data, stale cache tiles, and partial child sets.
- Add GPU-side counters for provider command count, skipped pass count, stale skip count, and ownership conflict count.
- Build a live in-Minecraft LoD probe that samples ownership/material/pass state, with sidecar image analysis only as confirmation.
