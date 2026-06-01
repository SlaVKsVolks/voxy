# Sodium-Compatible Voxy Far Terrain Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework Voxy from a separate distant overlay renderer into a Sodium-compatible far-terrain LoD provider that shares render distance policy, terrain ownership, lifecycle data, material validity, render pass ordering, upload scheduling, and diagnostics with Sodium.

**Architecture:** Sodium remains the near-field terrain renderer and the owner of exact chunk rendering. Voxy becomes the far-terrain provider behind the same player-facing render distance control, using a deterministic ownership map to fill terrain from the overlap ring outward while suppressing invalid, duplicate, or stale parent/child geometry. The first production target is correctness and no gaps; shader polish and maximum performance tuning happen only after the correctness contract is stable.

**Tech Stack:** Java 21, NeoForge 1.21.1, Sodium 0.8.x backport, Iris integration where enabled, Voxy RocksDB/cache-overlay storage, JVM headless proof tests, ModpackTestHarness runtime diagnostics.

---

## Scope And Direction

This plan implements option **B: Sodium-Compatible Voxy Far Terrain Provider**.

The current failure mode is not just one bad threshold. Voxy is still behaving like a separate renderer that attempts to draw distant terrain after Sodium has rendered normal chunks. That makes the vanilla-to-LoD handoff fragile: wrong pass ordering, depth mismatch, parent/child overlap, stale palette IDs, boundary load starvation, and shader/fog state drift can all produce gaps, black blocks, random floating sections, or wrong-colored LoDs.

The target architecture is:

- Minecraft/Sodium render distance slider controls total visual terrain distance.
- Sodium exact chunk rendering owns the near field up to the capped real chunk radius.
- Voxy owns far LoD coverage from an overlap ring to the visual terrain distance.
- Sodium section lifecycle data is the canonical near-field source for Voxy ingestion and invalidation.
- Voxy mesh/material output is validated before upload.
- Voxy draws in Sodium-compatible terrain phases with deterministic ownership.
- Parent fallback is allowed only where finer ownership is missing or invalid.
- Invalid cache/storage tiles are rejected or replaced by valid fallback; they never render black/random geometry.
- Existing RocksDB writable storage plus server LoD cache overlay remains the storage path for this rewrite.

## Non-Goals

- Do not force Minecraft/Sodium to load real chunks to the full visual distance.
- Do not switch to CompactVLCP as the primary storage path in this rewrite.
- Do not solve every shaderpack-specific Iris issue before the no-gap correctness gate.
- Do not optimize 129x129, 257x257, 501x501, or 2001x2001 generation runs before the boundary correctness gate passes.
- Do not keep `voxy_only` as a player-facing render mode. It remains diagnostics/harness-only.
- Do not attempt a full Sodium fork unless explicit Sodium extension points prove insufficient.

## File Structure

Create these focused units rather than continuing to grow the current renderer files:

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java`
  - Public integration boundary used by Sodium mixins and Voxy render code.
  - Owns provider lifecycle, policy snapshot, queues, diagnostics snapshot, and pass dispatch.

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainOwnershipMap.java`
  - CPU ownership model for exact chunks, LoD sections, parent fallback, invalid sections, and overlap.

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainOwnershipCell.java`
  - Small immutable record for one ownership decision.

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainPass.java`
  - Enum for Sodium-compatible terrain passes: `SOLID`, `CUTOUT`, `TRANSLUCENT`, `FLUID`, and `DEBUG`.

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainMaterialResolver.java`
  - Converts Voxy palette/block/biome/light data into validated Sodium-compatible material descriptors.

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyResolvedTerrainMaterial.java`
  - Immutable material descriptor with block state ID, model key, tint, light, pass, and validity state.

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainTileValidator.java`
  - Rejects invalid palette IDs, unresolved block states, missing biome mappings, corrupt light, and impossible bounds before meshing.

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyBoundaryPriorityQueue.java`
  - Prioritizes boundary-ring exact/fallback requests before far-distance refinement.

- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainProviderDiagnostics.java`
  - Typed diagnostics snapshot for policy, ownership, material rejection, pass rendering, and queue pressure.

- Modify: `src/main/java/me/cortex/voxy/client/sodium/VoxySodiumSectionLifecycleBridge.java`
  - Make Sodium lifecycle events the canonical ingestion/invalidation source.

- Modify: `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java`
  - Replace ad hoc Voxy draw injection with provider pass dispatch.

- Modify: `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager.java`
  - Keep exact section lifecycle and upload-pressure hooks, but route provider decisions through the new provider API.

- Modify: `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`
  - Stop owning Sodium handoff decisions directly; delegate terrain ownership, pass dispatch, and boundary priority to the provider.

- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java`
  - Use explicit ownership decisions to suppress parent meshes when child/finer coverage owns the cell.

- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java`
  - Consume visual distance and ownership bounds from the provider policy snapshot.

- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java`
  - Fail closed on invalid material/tile validation rather than emitting black/random fallback geometry.

- Modify: `src/main/java/me/cortex/voxy/client/core/model/ModelBakerySubsystem.java`
  - Expose a resolver-friendly material lookup result instead of forcing render code to infer missing model behavior.

- Modify: `src/main/java/me/cortex/voxy/common/debug/RenderCorrectnessDiagnostics.java`
  - Add provider ownership/material/pass/queue diagnostics.

- Modify: `src/test/java/me/cortex/voxy/client/core/rendering/VoxyLodCorrectnessProof.java`
  - Extend the existing synthetic proof to exercise provider ownership, material resolver, and per-pass mesh contracts.

## Acceptance Contract

The rewrite is not production-ready until all of these are true:

- The Minecraft/Sodium render distance slider is the only player-facing terrain-distance control.
- Sodium exact chunks render visibly in the near field in normal gameplay.
- Voxy starts at `realChunkRadiusChunks - handoffOverlapChunks`.
- Every boundary ownership cell has exact LoD or valid parent fallback.
- Parent meshes are suppressed anywhere child/finer ownership exists.
- Invalid palette/model/biome/light tiles are rejected before meshing.
- Rejected tiles never emit black blocks, random material IDs, or random geometry.
- Voxy terrain draws in Sodium-compatible terrain pass ordering.
- Boundary-ring requests outrank far LoD refinement.
- Diagnostics can explain every visible boundary state without screenshots.
- Headless proof passes before any live visual gate is run.

## Phase 0: Baseline Guardrails

### Task 0.1: Freeze Current Contract In Tests

**Files:**
- Modify: `src/test/java/me/cortex/voxy/client/core/rendering/VoxyHandoffPolicyTest.java`
- Modify: `src/test/java/me/cortex/voxy/client/core/rendering/VoxyLodCorrectnessProof.java`

- [ ] **Step 1: Add static checks that prevent fallback to legacy overlay behavior**

Add assertions that fail if render/traversal code reads `sectionRenderDistance` for active rendering, if `voxy_only` becomes default, or if diagnostics no longer expose merged policy fields.

- [ ] **Step 2: Run the static gate**

Run:

```powershell
java -cp 'build\classes\java\test;build\classes\java\main' me.cortex.voxy.client.core.rendering.VoxyHandoffPolicyTest
```

Expected:

```text
exit code 0
```

- [ ] **Step 3: Run the headless proof**

Run:

```powershell
.\gradlew.bat voxy_lod_correctness_proof --no-daemon --console=plain
```

Expected JSON:

```json
{"status":"PASS_LOD_CORRECTNESS","coverage_verdict":"PASS","palette_verdict":"PASS","mesh_verdict":"PASS","parent_child_verdict":"PASS","invalid_tile_verdict":"PASS"}
```

### Task 0.2: Add Provider Failure Vocabulary

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainFailureReason.java`
- Modify: `src/test/java/me/cortex/voxy/client/core/rendering/VoxyLodCorrectnessProof.java`

- [ ] **Step 1: Create the failure enum**

```java
package me.cortex.voxy.client.sodium.provider;

public enum VoxyTerrainFailureReason {
    NONE,
    MISSING_EXACT_CHILD,
    VALID_PARENT_FALLBACK,
    INVALID_PALETTE_ID,
    UNRESOLVED_BLOCK_STATE,
    MISSING_MODEL_FALLBACK,
    MISSING_BIOME_TINT,
    INVALID_LIGHT,
    SECTION_OUT_OF_BOUNDS,
    PARENT_CHILD_OWNERSHIP_CONFLICT,
    RENDER_PASS_UNSUPPORTED
}
```

- [ ] **Step 2: Use these exact names in proof diagnostics**

The proof JSON must use these names in `failures[]` and per-scenario invalid tile verdicts.

- [ ] **Step 3: Verify**

Run:

```powershell
.\gradlew.bat testClasses --no-daemon --console=plain
.\gradlew.bat voxy_lod_correctness_proof --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
```

## Phase 1: Central Provider API

### Task 1.1: Define Provider Snapshot Records

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProviderSnapshot.java`
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainProviderDiagnostics.java`

- [ ] **Step 1: Add immutable policy snapshot**

```java
package me.cortex.voxy.client.sodium.provider;

public record VoxyFarTerrainProviderSnapshot(
        String renderDistanceSliderMode,
        int visualTerrainDistanceChunks,
        int realChunkRadiusChunks,
        int handoffOverlapChunks,
        int voxyLodStartChunks,
        int voxyLodEndChunks,
        boolean sodiumChunkRenderingEnabled,
        boolean irisShaderPackEnabled
) {
    public boolean hasMergedDistanceOwnership() {
        return this.visualTerrainDistanceChunks >= this.realChunkRadiusChunks
                && this.voxyLodStartChunks <= this.realChunkRadiusChunks
                && this.voxyLodEndChunks == this.visualTerrainDistanceChunks;
    }
}
```

- [ ] **Step 2: Add diagnostics record**

```java
package me.cortex.voxy.client.sodium.provider;

public record VoxyTerrainProviderDiagnostics(
        VoxyFarTerrainProviderSnapshot policy,
        long exactOwnedCells,
        long lodOwnedCells,
        long parentFallbackCells,
        long invalidRejectedCells,
        long parentChildConflictCells,
        long boundaryExactSections,
        long boundaryParentFallbackSections,
        long boundaryRejectedSections,
        long solidPassMeshes,
        long cutoutPassMeshes,
        long translucentPassMeshes,
        long fluidPassMeshes,
        long boundaryQueueDepth,
        long farQueueDepth,
        String terrainCoverageVerdict
) {
}
```

- [ ] **Step 3: Add proof assertions**

Extend `VoxyLodCorrectnessProof` to construct a snapshot with visual `64`, real `8`, overlap `2`, start `6`, end `64`, and assert `hasMergedDistanceOwnership()`.

- [ ] **Step 4: Verify**

Run:

```powershell
.\gradlew.bat testClasses --no-daemon --console=plain
.\gradlew.bat voxy_lod_correctness_proof --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
```

### Task 1.2: Create Provider Entry Point

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`

- [ ] **Step 1: Add provider shell**

```java
package me.cortex.voxy.client.sodium.provider;

public final class VoxyFarTerrainProvider {
    private volatile VoxyFarTerrainProviderSnapshot snapshot;
    private volatile VoxyTerrainProviderDiagnostics diagnostics;

    public VoxyFarTerrainProvider(VoxyFarTerrainProviderSnapshot initialSnapshot) {
        this.snapshot = initialSnapshot;
        this.diagnostics = new VoxyTerrainProviderDiagnostics(
                initialSnapshot, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                "UNKNOWN_NO_PROVIDER_OWNERSHIP"
        );
    }

    public VoxyFarTerrainProviderSnapshot snapshot() {
        return this.snapshot;
    }

    public void updateSnapshot(VoxyFarTerrainProviderSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public VoxyTerrainProviderDiagnostics diagnostics() {
        return this.diagnostics;
    }
}
```

- [ ] **Step 2: Store provider in `VoxyRenderSystem`**

Add a private final provider field and a getter:

```java
private final VoxyFarTerrainProvider farTerrainProvider;

public VoxyFarTerrainProvider getFarTerrainProvider() {
    return this.farTerrainProvider;
}
```

Initialize it from the current `VoxyHandoffPolicy` state during renderer construction.

- [ ] **Step 3: Verify compile**

Run:

```powershell
.\gradlew.bat testClasses --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
```

## Phase 2: Terrain Ownership Map

### Task 2.1: Define Ownership Cell

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainOwnershipCell.java`
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainOwnership.java`

- [ ] **Step 1: Add ownership enum**

```java
package me.cortex.voxy.client.sodium.provider;

public enum VoxyTerrainOwnership {
    VANILLA_EXACT,
    VOXY_EXACT_LOD,
    VOXY_PARENT_FALLBACK,
    REJECTED_INVALID,
    EMPTY_OUTSIDE_DISTANCE
}
```

- [ ] **Step 2: Add ownership cell**

```java
package me.cortex.voxy.client.sodium.provider;

public record VoxyTerrainOwnershipCell(
        int chunkX,
        int chunkZ,
        int lodLevel,
        VoxyTerrainOwnership ownership,
        VoxyTerrainFailureReason reason
) {
    public boolean rendersVoxyGeometry() {
        return this.ownership == VoxyTerrainOwnership.VOXY_EXACT_LOD
                || this.ownership == VoxyTerrainOwnership.VOXY_PARENT_FALLBACK;
    }
}
```

- [ ] **Step 3: Add proof checks**

Use the new records in `VoxyLodCorrectnessProof` sparse missing-child scenario. Assert exact cells and parent fallback cells never both render the same ownership cell.

### Task 2.2: Implement Ownership Map

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainOwnershipMap.java`
- Modify: `src/test/java/me/cortex/voxy/client/core/rendering/VoxyLodCorrectnessProof.java`

- [ ] **Step 1: Write failing proof assertions**

Add test/proof cases for:

```text
visual=2, real=2, start=0, end=2
visual=8, real=8, start=6, end=8
visual=32, real=8, start=6, end=32
visual=64, real=8, start=6, end=64
```

Expected ownership:

```text
0..real = VANILLA_EXACT
start..end = Voxy eligible
start..real = overlap, no gap
end+1 = EMPTY_OUTSIDE_DISTANCE
```

- [ ] **Step 2: Implement map**

```java
package me.cortex.voxy.client.sodium.provider;

public final class VoxyTerrainOwnershipMap {
    private final VoxyFarTerrainProviderSnapshot snapshot;

    public VoxyTerrainOwnershipMap(VoxyFarTerrainProviderSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public VoxyTerrainOwnershipCell classifyChunkRadius(
            int chunkX,
            int chunkZ,
            int chunkRadius,
            boolean exactLodAvailable,
            boolean parentFallbackAvailable,
            boolean tileRejected
    ) {
        if (chunkRadius > this.snapshot.voxyLodEndChunks()) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.EMPTY_OUTSIDE_DISTANCE, VoxyTerrainFailureReason.NONE);
        }
        if (tileRejected) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.REJECTED_INVALID, VoxyTerrainFailureReason.INVALID_PALETTE_ID);
        }
        if (chunkRadius < this.snapshot.voxyLodStartChunks()) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.VANILLA_EXACT, VoxyTerrainFailureReason.NONE);
        }
        if (exactLodAvailable) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.VOXY_EXACT_LOD, VoxyTerrainFailureReason.NONE);
        }
        if (parentFallbackAvailable) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 1,
                    VoxyTerrainOwnership.VOXY_PARENT_FALLBACK, VoxyTerrainFailureReason.VALID_PARENT_FALLBACK);
        }
        if (chunkRadius <= this.snapshot.realChunkRadiusChunks()) {
            return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                    VoxyTerrainOwnership.VANILLA_EXACT, VoxyTerrainFailureReason.MISSING_EXACT_CHILD);
        }
        return new VoxyTerrainOwnershipCell(chunkX, chunkZ, 0,
                VoxyTerrainOwnership.REJECTED_INVALID, VoxyTerrainFailureReason.MISSING_EXACT_CHILD);
    }
}
```

- [ ] **Step 3: Verify**

Run:

```powershell
.\gradlew.bat voxy_lod_correctness_proof --no-daemon --console=plain
```

Expected:

```text
"coverage_verdict":"PASS"
"parent_child_verdict":"PASS"
```

## Phase 3: Sodium Lifecycle As Canonical Ingestion

### Task 3.1: Harden Section Lifecycle Bridge

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/sodium/VoxySodiumSectionLifecycleBridge.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`

- [ ] **Step 1: Ensure lifecycle events route through provider**

`onSectionInfoUpdated` must call provider ingestion with:

```text
SectionPos
RenderSection
BuiltSectionInfo
changed flag
current provider snapshot
```

`onSectionRemoved` must invalidate both:

```text
exact section ingestion cache
boundary ownership cells affected by that chunk section
```

- [ ] **Step 2: Add stale-event guard**

Reject lifecycle events when:

```text
Minecraft level is null
provider is null
section dimension does not match current world
renderer instance was recreated after event capture
```

- [ ] **Step 3: Verify no old redirect path is active**

Static check must assert `RenderSection.setInfo` is not redirected by Voxy and lifecycle hook registration remains active.

Run:

```powershell
java -cp 'build\classes\java\test;build\classes\java\main' me.cortex.voxy.client.core.rendering.VoxyHandoffPolicyTest
```

Expected:

```text
exit code 0
```

### Task 3.2: Invalidation Contract

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxySectionInvalidationTracker.java`
- Modify: `src/main/java/me/cortex/voxy/client/sodium/VoxySodiumSectionLifecycleBridge.java`

- [ ] **Step 1: Track invalidation by section key**

```java
package me.cortex.voxy.client.sodium.provider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class VoxySectionInvalidationTracker {
    private final ConcurrentMap<Long, Long> invalidationVersions = new ConcurrentHashMap<>();

    public long invalidate(long sectionKey) {
        return this.invalidationVersions.merge(sectionKey, 1L, Long::sum);
    }

    public long version(long sectionKey) {
        return this.invalidationVersions.getOrDefault(sectionKey, 0L);
    }
}
```

- [ ] **Step 2: Tie invalidation to upload rejection**

If a mesh build finishes with an older invalidation version than the current section version, discard it before upload.

- [ ] **Step 3: Verify with synthetic stale build proof**

Add a proof case:

```text
build starts at version 1
section invalidates to version 2
build returns version 1
expected: discarded, no mesh ownership
```

## Phase 4: Material And Palette Validity

### Task 4.1: Resolve Materials Before Meshing

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyResolvedTerrainMaterial.java`
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainMaterialResolver.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/model/ModelBakerySubsystem.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java`

- [ ] **Step 1: Add resolved material record**

```java
package me.cortex.voxy.client.sodium.provider;

public record VoxyResolvedTerrainMaterial(
        int sourceBlockStateId,
        int sourceBiomeId,
        int colorArgb,
        int packedLight,
        VoxyTerrainPass pass,
        String modelKey,
        boolean valid,
        VoxyTerrainFailureReason failureReason
) {
    public boolean canMesh() {
        return this.valid && this.failureReason == VoxyTerrainFailureReason.NONE;
    }
}
```

- [ ] **Step 2: Resolver behavior**

Resolver must return invalid results for:

```text
block state ID not present in mapper
block state maps to no model and no explicit fallback
biome tint ID not present and no default tint configured
light outside expected packed range
```

Resolver must never return black unless the source block is actually black or configured black.

- [ ] **Step 3: Fail closed in mesh factory**

`RenderDataFactory` must skip invalid materials and increment rejected diagnostics. It must not emit geometry using material ID `0`, missing model ID, or default black fallback for unresolved blocks.

- [ ] **Step 4: Extend proof**

Add corrupted cases:

```text
missing palette entry
sparse palette ID
invalid block state ID
missing biome ID
missing light
```

Expected:

```text
invalid_tile_verdict = PASS_REJECTED_INVALID_TILE
mesh_hash does not include invalid material
palette_verdict = PASS
```

## Phase 5: Sodium-Compatible Terrain Passes

### Task 5.1: Define Voxy Terrain Pass Contract

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainPass.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java`

- [ ] **Step 1: Add pass enum**

```java
package me.cortex.voxy.client.sodium.provider;

public enum VoxyTerrainPass {
    SOLID,
    CUTOUT,
    TRANSLUCENT,
    FLUID,
    DEBUG
}
```

- [ ] **Step 2: Map materials to passes**

Expected mapping:

```text
full opaque cube -> SOLID
cutout foliage/fence-like model -> CUTOUT
water/lava -> FLUID
translucent glass-like model -> TRANSLUCENT
diagnostic overlay -> DEBUG
```

- [ ] **Step 3: Mesh buffers split by pass**

`RenderDataFactory` must produce separate buffers/counts per `VoxyTerrainPass`.

- [ ] **Step 4: Proof assertions**

Water scenario must emit `FLUID`.
Checkerboard opaque blocks must emit `SOLID`.
Cutout synthetic model must emit `CUTOUT`.

### Task 5.2: Replace Ad Hoc Sodium Draw Injection

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`
- Modify: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java`

- [ ] **Step 1: Provider exposes pass dispatch**

```java
public boolean shouldRenderDuringSodiumPass(VoxyTerrainPass pass) {
    return this.snapshot().sodiumChunkRenderingEnabled()
            && this.snapshot().hasMergedDistanceOwnership();
}
```

- [ ] **Step 2: Mixin routes Sodium pass to provider**

`MixinDefaultChunkRenderer` should convert Sodium `TerrainRenderPass` to `VoxyTerrainPass` and call:

```java
provider.renderPass(pass, matrices, camera);
```

- [ ] **Step 3: Preserve `voxy_only` only as diagnostic**

`voxy_only` can still cancel Sodium chunk rendering for harness captures, but normal mode must not cancel near-field rendering.

- [ ] **Step 4: Static verification**

`VoxyHandoffPolicyTest` must fail if:

```text
disableSodiumChunkRender() is used outside diagnostic/harness paths
MixinDefaultChunkRenderer cancels Sodium render in normal mode
Voxy pass dispatch is missing from Sodium pass integration
```

## Phase 6: Boundary Priority And Parent Fallback

### Task 6.1: Boundary Priority Queue

**Files:**
- Create: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyBoundaryPriorityQueue.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/RenderDistanceTracker.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java`

- [ ] **Step 1: Add two-lane queue**

```java
package me.cortex.voxy.client.sodium.provider;

import java.util.Comparator;
import java.util.PriorityQueue;

public final class VoxyBoundaryPriorityQueue<T extends VoxyBoundaryPriorityQueue.Entry> {
    private final PriorityQueue<T> queue = new PriorityQueue<>(Comparator
            .comparingInt(T::lane)
            .thenComparingInt(T::distanceFromHandoff));

    public void add(T entry) {
        this.queue.add(entry);
    }

    public T poll() {
        return this.queue.poll();
    }

    public int size() {
        return this.queue.size();
    }

    public interface Entry {
        int lane();
        int distanceFromHandoff();
    }
}
```

Lane rules:

```text
lane 0 = boundary exact tile or parent fallback
lane 1 = near refinement outside boundary
lane 2 = far-distance refinement
```

- [ ] **Step 2: Route handoff-ring requests to lane 0**

Any section intersecting:

```text
[voxyLodStartChunks, realChunkRadiusChunks + boundaryExtraChunks]
```

must enter lane 0.

- [ ] **Step 3: Diagnostics**

Emit:

```text
boundary_queue_depth
far_queue_depth
boundary_exact_sections
boundary_parent_fallback_sections
boundary_rejected_sections
```

### Task 6.2: Parent Suppression Contract

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java`
- Modify: `src/test/java/me/cortex/voxy/client/core/rendering/VoxyLodCorrectnessProof.java`

- [ ] **Step 1: Fine ownership suppresses parent**

If all renderable children for a parent cell are present and valid, parent must not render that ownership cell.

- [ ] **Step 2: Missing child allows parent fallback**

If a child is missing, invalid, or stale, the nearest valid parent may render exactly that uncovered region.

- [ ] **Step 3: Invalid parent does not fallback**

If the parent tile fails validation, it cannot fallback. The next valid ancestor may fallback, or the cell is rejected.

- [ ] **Step 4: Proof cases**

Add scenarios:

```text
all children present -> parent face count for owned child cells is 0
one child missing -> parent fallback face count > 0
child invalid parent valid -> parent fallback face count > 0
child invalid parent invalid -> no invalid geometry emitted
```

Expected:

```text
parent_child_verdict = PASS
invalid_tile_verdict = PASS_REJECTED_INVALID_TILE
```

## Phase 7: Render State, Depth, Fog, And Iris

### Task 7.1: Provider Owns Voxy Terrain Render State Snapshot

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/rendering/RenderStateSnapshot.java`
- Modify: `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`
- Modify: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java`

- [ ] **Step 1: Add provider snapshot fields**

Render snapshot must include:

```text
visual terrain distance blocks
real chunk radius
handoff start
handoff end
terrain pass
Sodium chunk rendering enabled
Iris shaderpack enabled
depth target identity
fog start/end
```

- [ ] **Step 2: Assert snapshot consistency**

At render time:

```text
provider snapshot visual distance == VoxyHandoffPolicy visual distance
provider pass matches current Sodium pass
depth target is valid
fog end >= visual terrain distance blocks
```

Invalid snapshot means skip Voxy pass and emit diagnostics, not render garbage.

### Task 7.2: Iris Compatibility Boundary

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/core/IrisVoxyRenderPipeline.java`
- Modify: `src/main/java/me/cortex/voxy/client/iris/IrisVoxyRenderPipelineData.java`
- Modify: `src/main/java/me/cortex/voxy/client/iris/VoxyUniforms.java`

- [ ] **Step 1: Keep Iris path behind compatibility check**

Voxy should render in Iris only when:

```text
pipeline data exists
target set is valid
required uniforms exist or compatibility fallback provides them
depth state is valid
```

- [ ] **Step 2: Fail closed**

If Iris state is incomplete:

```text
skip Voxy Iris pass
keep Sodium near field enabled
emit iris_voxy_provider_verdict=SKIPPED_INCOMPLETE_IRIS_STATE
```

- [ ] **Step 3: Preserve no-gap priority**

Shader integration problems must not disable Sodium near-field terrain.

## Phase 8: Diagnostics And Harness Proof

### Task 8.1: Provider Diagnostics JSON

**Files:**
- Modify: `src/main/java/me/cortex/voxy/common/debug/RenderCorrectnessDiagnostics.java`
- Modify: `src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodDiagnostics.java`
- Modify: `src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainProviderDiagnostics.java`

- [ ] **Step 1: Add fields**

Emit:

```text
provider_mode
provider_snapshot_valid
render_distance_slider_mode
visual_terrain_distance_chunks
vanilla_real_render_distance_chunks
voxy_lod_start_chunks
voxy_lod_end_chunks
handoff_overlap_chunks
sodium_chunk_rendering_enabled
exact_owned_cells
lod_owned_cells
parent_fallback_cells
invalid_rejected_cells
parent_child_conflict_cells
boundary_queue_depth
far_queue_depth
boundary_exact_sections
boundary_parent_fallback_sections
boundary_rejected_sections
solid_pass_meshes
cutout_pass_meshes
fluid_pass_meshes
translucent_pass_meshes
terrain_coverage_verdict
```

- [ ] **Step 2: Verdict rules**

```text
PASS_SODIUM_COMPATIBLE_PROVIDER_NO_GAP:
  sodium chunks enabled
  visual >= real
  lod start <= real
  boundary exact + parent fallback > 0
  parent child conflicts = 0
  invalid rejected cells may be > 0

FAIL_PROVIDER_GAP:
  boundary missing > 0
  exact + parent fallback = 0

FAIL_PROVIDER_INVALID_RENDERED:
  invalid rejected cells rendered as geometry
  unresolved material reached mesh

UNKNOWN_PROVIDER_NO_BOUNDARY_REQUESTS:
  no boundary data has been requested yet
```

### Task 8.2: Harness Gates

**Files:**
- Modify: `../../ModpackTestHarness/src/main/java/com/slavks/mctestharness/RenderStackCliController.java`

- [ ] **Step 1: Add provider proof command fields**

Harness proof must include:

```text
PASS_SODIUM_COMPATIBLE_PROVIDER_NO_GAP
FAIL_PROVIDER_GAP
FAIL_PROVIDER_INVALID_RENDERED
provider diagnostics JSON
screenshot paths only when live visual gate is explicitly requested
```

- [ ] **Step 2: Do not require live screenshots for code-side gate**

The code-side gate is:

```powershell
.\gradlew.bat voxy_lod_correctness_proof --no-daemon --console=plain
java -cp 'build\classes\java\test;build\classes\java\main' me.cortex.voxy.client.core.rendering.VoxyHandoffPolicyTest
```

The live gate is separate and must only run when requested.

## Phase 9: Runtime Safety And Migration

### Task 9.1: Keep Normal Gameplay Safe

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/VoxyClient.java`
- Modify: `src/main/java/me/cortex/voxy/client/config/VoxyConfig.java`
- Modify: `src/main/java/me/cortex/voxy/client/config/VoxyConfigMenu.java`

- [ ] **Step 1: Normal mode cannot disable Sodium chunks**

`disableSodiumChunkRender()` may return true only when:

```text
visual attribution mode == voxy_only
and diagnostics/harness session explicitly requested it
```

- [ ] **Step 2: Reset diagnostics mode**

On world load, disconnect, reconnect, and renderer recreation:

```text
visual attribution mode = normal
Sodium chunk rendering enabled = true
```

- [ ] **Step 3: Static guard**

`VoxyHandoffPolicyTest` must reject code paths that make `voxy_only` persistent or default.

### Task 9.2: Config Migration

**Files:**
- Modify: `src/main/java/me/cortex/voxy/client/config/VoxyConfig.java`
- Modify: `src/main/java/me/cortex/voxy/client/VoxyMergedRenderDistance.java`

- [ ] **Step 1: Keep legacy `sectionRenderDistance` migration-only**

Rendering must not read it. Migration may translate it once:

```text
visualTerrainDistanceChunks = max(2, round(sectionRenderDistance * 32))
```

- [ ] **Step 2: Clamp policy**

```text
visualTerrainDistanceChunks: 2..512
maxRealChunkRadiusChunks: 2..32
handoffOverlapChunks: 0..32
realChunkRadiusChunks = min(visual, maxReal)
lodStart = max(0, real - overlap)
lodEnd = visual
```

## Phase 10: Verification Gates

### Task 10.1: Required Code-Side Gates

Run from:

```text
C:\Users\SlaVKs\Documents\Github\Minecraft\Projects\VoxyNeoForge\m3-voxy
```

- [ ] **Step 1: Compile test classes**

```powershell
.\gradlew.bat testClasses --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Run static policy gate**

```powershell
java -cp 'build\classes\java\test;build\classes\java\main' me.cortex.voxy.client.core.rendering.VoxyHandoffPolicyTest
```

Expected:

```text
exit code 0
```

- [ ] **Step 3: Run deterministic LoD proof**

```powershell
.\gradlew.bat voxy_lod_correctness_proof --no-daemon --console=plain
```

Expected:

```json
{"status":"PASS_LOD_CORRECTNESS","coverage_verdict":"PASS","palette_verdict":"PASS","mesh_verdict":"PASS","parent_child_verdict":"PASS","invalid_tile_verdict":"PASS"}
```

- [ ] **Step 4: Run full build**

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
```

### Task 10.2: Optional Harness Build Gate

Run only if `ModpackTestHarness` changes:

```powershell
cd C:\Users\SlaVKs\Documents\Github\Minecraft\Projects\ModpackTestHarness
gradle build --no-daemon --console=plain
```

Expected:

```text
BUILD SUCCESSFUL
```

### Task 10.3: Live Visual Gate

Run only after all code-side gates pass and the user explicitly approves live testing.

Required live cases:

```text
SodiumFamilyNeoForge1211Control
normal mode
render distance 2
render distance 8
render distance 32
render distance 64
known failing camera
```

Pass only if:

```text
vanilla foreground visible
no blank band between vanilla and LoD
no random blocks in blank area
no black unresolved LoD blocks
parent/child sheets are not both visible
diagnostics verdict = PASS_SODIUM_COMPATIBLE_PROVIDER_NO_GAP
```

## Rollout Order

1. Add provider records, ownership map, and proof-only tests.
2. Route diagnostics through provider without changing draw behavior.
3. Move Sodium lifecycle ingestion/invalidation into provider.
4. Add material resolver and invalid tile rejection before meshing.
5. Add parent/child ownership suppression and fallback.
6. Route Voxy draw through Sodium-compatible terrain passes.
7. Add boundary priority queue and diagnostics.
8. Harden Iris fail-closed behavior.
9. Run code-side gates.
10. Run one short live visual gate.

## Production Readiness Checklist

- [ ] Provider snapshot is initialized before first render tick.
- [ ] Sodium chunk rendering stays enabled in normal mode.
- [ ] Merged render distance policy is the only player-facing terrain-distance control.
- [ ] Ownership map proves no empty interval from player to visual distance.
- [ ] Boundary queue requests exact/fallback sections before far refinement.
- [ ] Parent fallback renders only where child/finer ownership is absent.
- [ ] Fine child ownership suppresses parent meshes.
- [ ] Material resolver rejects unresolved palette/model/biome/light data.
- [ ] Mesh factory never emits invalid material IDs.
- [ ] Mesh buffers are split by Sodium-compatible terrain pass.
- [ ] Iris path skips Voxy far terrain rather than corrupting near-field rendering when incomplete.
- [ ] Diagnostics explain coverage, rejection, pass counts, queue pressure, and ownership conflicts.
- [ ] `voxy_lod_correctness_proof` passes.
- [ ] Voxy Gradle build passes.
- [ ] Harness build passes if harness changed.
- [ ] Live screenshot gate passes after code-side gates.

## Main Risks

- Sodium 0.8.x backport may not expose enough stable pass/lifecycle APIs. If so, add focused hooks to the owned Sodium backport instead of widening Voxy mixin fragility.
- Iris shaderpacks may require per-pack integration. The safe default is to skip Voxy far terrain under incomplete Iris state while preserving Sodium near-field terrain.
- Parent fallback can hide missing child data. Diagnostics must distinguish exact coverage from fallback coverage.
- Boundary priority can starve far refinement if queue budgets are too strict. Start with correctness-first budgets, then tune after no-gap proof passes.
- Material rejection can reduce visible far terrain if cache data is corrupt. That is acceptable during correctness work; visible black/random blocks are not acceptable.

## Commit Strategy

Use small commits after each passing phase:

```powershell
git add src/main/java/me/cortex/voxy/client/sodium/provider src/test/java/me/cortex/voxy/client/core/rendering/VoxyLodCorrectnessProof.java
git commit -m "test: add sodium provider ownership proof"

git add src/main/java/me/cortex/voxy/client/sodium src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java
git commit -m "feat: route voxy far terrain through provider boundary"

git add src/main/java/me/cortex/voxy/client/core/model src/main/java/me/cortex/voxy/client/core/rendering/building
git commit -m "fix: reject invalid voxy terrain materials before meshing"

git add src/main/java/me/cortex/voxy/client/mixin/sodium src/main/java/me/cortex/voxy/client/sodium/provider
git commit -m "feat: dispatch voxy lods through sodium terrain passes"
```

Do not run `git add .` from the monorepo root.

## Final Definition Of Done

The rewrite is done when a clean run produces:

```text
.\gradlew.bat voxy_lod_correctness_proof --no-daemon --console=plain
PASS_LOD_CORRECTNESS

java -cp 'build\classes\java\test;build\classes\java\main' me.cortex.voxy.client.core.rendering.VoxyHandoffPolicyTest
exit code 0

.\gradlew.bat build --no-daemon --console=plain
BUILD SUCCESSFUL
```

Then, after explicit approval for live testing, the clean Sodium/NeoForge instance must produce:

```text
PASS_SODIUM_COMPATIBLE_PROVIDER_NO_GAP
vanilla foreground visible
LoDs start at the overlap ring
no blank band
no random boundary blocks
no unresolved black LoD blocks
no visible parent/child duplicate sheets
```
