package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.common.VoxyHandoffPolicy;
import me.cortex.voxy.client.sodium.provider.VoxyFarTerrainProvider;
import me.cortex.voxy.client.sodium.provider.VoxyFarTerrainProviderSnapshot;
import me.cortex.voxy.client.sodium.provider.VoxyProviderDrawDecision;
import me.cortex.voxy.client.sodium.provider.VoxyProviderRenderCell;
import me.cortex.voxy.client.sodium.provider.VoxyProviderRenderList;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainFailureReason;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainOwnership;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainOwnershipCell;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainPass;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.serverlod.ServerLodNeoForgeContract;

import java.nio.file.Files;
import java.nio.file.Path;

public final class VoxyHandoffPolicyTest {
    private VoxyHandoffPolicyTest() {
    }

    public static void main(String[] args) {
        assertStart(2, 2, 0);
        assertStart(3, 2, 1);
        assertStart(8, 2, 6);
        assertStart(0, 2, 0);
        assertStart(1, 8, 0);
        assertStart(12, -4, 12);
        assertMergedDistance(2, 8, 2, 2, 0, 2);
        assertMergedDistance(8, 8, 2, 8, 6, 8);
        assertMergedDistance(32, 8, 2, 8, 6, 32);
        assertMergedDistance(512, 8, 2, 8, 6, 512);
        assertTopLevelRadius(2, 2);
        assertTopLevelRadius(32, 2);
        assertTopLevelRadius(64, 3);
        assertTopLevelRadius(512, 17);
        assertDistanceStateCanBeInitializedBeforeFirstCameraTick();
        assertRequiredVoxyCoverageOnlyExistsBeyondVanilla();
        assertParentFallbackShaderDoesNotUseFarCornerRenderDistanceGate();
        assertHandoffRootsIncludeExactLevelZeroSections();
        assertRenderPathsUseMergedDistancePolicy();
        assertMergedSliderAdaptersAreRegistered();
        assertDiagnosticsExposeMergedCoverageFields();
        assertHarnessProofUsesMergedDistanceVerdict();
        assertParentMeshArtifactGuardsDefaultOn();
        assertSparseServerLodMappingsAreImported();
        assertServerAuthoredNeoForgeContractIsExposed();
        assertServerLodCacheSynthesizesUsefulParentsByDefault();
        assertServerLodSyncAutoBuildsAndRefreshesManifests();
        assertServerLodSyncAutoBuildsWhenCoverageRadiusIsTooSmall();
        assertServerLodSyncUsesExactTileCacheIdentity();
        assertServerLodSyncRemountsWorldStore();
        assertProductionHardeningIssuesAreClosed();
        assertMillionDollarProductionBlockersAreClosed();
        assertSodiumCompatibleProviderBoundaryExists();
        assertSodiumCompatibleProviderIsRuntimeAuthority();
        assertProviderIsProductionRenderAuthority();
        assertProviderCoverageRequiresRenderOwnedBoundaryMesh();
        assertProviderCurrentRefreshDoesNotRetainStaleGaps();
        assertProviderCurrentRefreshDoesNotInventSolidPassOwnership();
        assertProviderRejectedMeshCommitIsDiagnosticFailure();
        assertProviderCurrentRefreshPrunesStaleRenderListEntries();
        assertProviderCurrentRefreshPreservesRejectedBoundaryFailure();
        assertProviderCurrentRefreshIgnoresMissingTraversalPlaceholders();
        assertProviderCurrentRefreshDoesNotChurnRenderListEpoch();
        assertProviderRejectsCommittedMeshAfterInvalidation();
        assertProviderRejectsCurrentRefreshMeshAfterInvalidation();
        assertProviderMaterialUnsupportedPassIsDiagnosed();
        assertProviderSkipDecisionPreservesExplicitSectionCount();
        assertProviderCurrentRefreshRetainsChildWhenParentIsSuppressed();
        assertProviderStaleUploadRejectionClearsBoundaryCoverage();
        assertProviderParentSuppressionClearsBoundaryCoverage();
        assertProviderDrawAuthorityContract();
        VoxyLodCorrectnessProof.runAssertions();
    }

    private static void assertStart(int vanillaRadiusChunks, int overlapChunks, int expected) {
        int actual = VoxyHandoffPolicy.handoffStartChunks(vanillaRadiusChunks, overlapChunks);
        if (actual != expected) {
            throw new AssertionError("handoffStartChunks(" + vanillaRadiusChunks + ", " + overlapChunks
                    + ") expected " + expected + " but got " + actual);
        }
    }

    private static void assertMergedDistance(
            int visualChunks,
            int maxRealChunks,
            int overlapChunks,
            int expectedRealChunks,
            int expectedStartChunks,
            int expectedEndChunks
    ) {
        VoxyHandoffPolicy.MergedRenderDistance distance =
                VoxyHandoffPolicy.mergedRenderDistance(visualChunks, maxRealChunks, overlapChunks);
        if (distance.realRenderDistanceChunks() != expectedRealChunks) {
            throw new AssertionError("realRenderDistanceChunks(" + visualChunks + ") expected "
                    + expectedRealChunks + " but got " + distance.realRenderDistanceChunks());
        }
        if (distance.voxyLodStartChunks() != expectedStartChunks) {
            throw new AssertionError("voxyLodStartChunks(" + visualChunks + ") expected "
                    + expectedStartChunks + " but got " + distance.voxyLodStartChunks());
        }
        if (distance.voxyLodEndChunks() != expectedEndChunks) {
            throw new AssertionError("voxyLodEndChunks(" + visualChunks + ") expected "
                    + expectedEndChunks + " but got " + distance.voxyLodEndChunks());
        }
    }

    private static void assertTopLevelRadius(int visualChunks, int expectedSections) {
        int actual = VoxyHandoffPolicy.topLevelRenderDistanceSections(visualChunks);
        if (actual != expectedSections) {
            throw new AssertionError("topLevelRenderDistanceSections(" + visualChunks + ") expected "
                    + expectedSections + " but got " + actual);
        }
    }

    private static void assertDistanceStateCanBeInitializedBeforeFirstCameraTick() {
        VoxyHandoffPolicy.updateDistance(64, 8, 2, "voxy_merged");
        if (VoxyHandoffPolicy.visualTerrainDistanceChunks() != 64) {
            throw new AssertionError("Merged policy visual distance must initialize before the first camera tick");
        }
        if (VoxyHandoffPolicy.visualTerrainDistanceBlocks() != 1024) {
            throw new AssertionError("Visual distance blocks must be available before the first camera tick");
        }
        if (VoxyHandoffPolicy.realRenderDistanceChunks() != 8) {
            throw new AssertionError("Real render distance must be capped before the first camera tick");
        }
        if (VoxyHandoffPolicy.handoffStartChunks() != 6) {
            throw new AssertionError("LoD handoff start must be initialized before the first camera tick");
        }
        if (VoxyHandoffPolicy.voxyLodEndChunks() != 64) {
            throw new AssertionError("LoD end must be initialized before the first camera tick");
        }
    }

    private static void assertRequiredVoxyCoverageOnlyExistsBeyondVanilla() {
        VoxyHandoffPolicy.updateCamera(0.0D, 0.0D, 8, 8, 2, "voxy_merged");
        if (VoxyHandoffPolicy.hasRequiredVoxyCoverageBeyondVanilla()) {
            throw new AssertionError("Visual distance equal to vanilla real distance must not require Voxy coverage beyond vanilla");
        }
        if (VoxyHandoffPolicy.isRequiredVoxyCoverageSection(0, 4, 0)) {
            throw new AssertionError("Overlap-only Voxy sections must not be counted as required gap coverage");
        }

        VoxyHandoffPolicy.updateCamera(0.0D, 0.0D, 64, 8, 2, "voxy_merged");
        if (!VoxyHandoffPolicy.hasRequiredVoxyCoverageBeyondVanilla()) {
            throw new AssertionError("Visual distance beyond real distance must require Voxy coverage");
        }
        if (!VoxyHandoffPolicy.isRequiredVoxyCoverageSection(0, 4, 0)) {
            throw new AssertionError("Sections past the real render distance must be required when visual distance extends beyond vanilla");
        }
        if (VoxyHandoffPolicy.isRequiredVoxyCoverageSection(0, 40, 0)) {
            throw new AssertionError("Sections beyond the visual terrain distance must not be required");
        }
    }

    private static void assertParentFallbackShaderDoesNotUseFarCornerRenderDistanceGate() {
        Path shader = Path.of("src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp");
        String source;
        try {
            source = Files.readString(shader);
        } catch (Exception exception) {
            throw new AssertionError("Unable to read traversal shader " + shader.toAbsolutePath(), exception);
        }
        if (source.contains("far.x*far.x+far.z*far.z")) {
            throw new AssertionError("Traversal shader must render parent fallback when child refinement is missing");
        }
        if (!source.contains("Child refinement is missing")) {
            throw new AssertionError("Traversal shader parent-fallback handoff comment is missing");
        }
    }

    private static void assertHandoffRootsIncludeExactLevelZeroSections() {
        String source = readSource(Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"));
        if (!source.contains("Integer.getInteger(\"voxy.handoffRootMinLevel\", 0)")) {
            throw new AssertionError("Handoff root trackers must default to level 0 so exact cached tiles can fill the boundary");
        }
        if (!source.contains("Integer.getInteger(\"voxy.handoffRootMaxLevel\", 2)")) {
            throw new AssertionError("Handoff root trackers must avoid default level-3 coarse roots");
        }
    }

    private static void assertRenderPathsUseMergedDistancePolicy() {
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/VoxyClient.java"),
                "applyMergedRenderDistancePolicy",
                "Client ticks must apply the merged visual/real render-distance policy");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/VoxyMergedRenderDistance.java"),
                "VoxyHandoffPolicy.updateDistance",
                "Merged distance helper must initialize policy state before render code depends on camera ticks");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "VoxyHandoffPolicy.topLevelRenderDistanceSections",
                "Top-level tracker radius must derive from the merged visual terrain distance");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "VoxyMergedRenderDistance.currentDistance()",
                "Render-frame camera updates must preserve the merged visual terrain distance");
        String renderSystem = readSource(Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"))
                .replace("\r\n", "\n");
        if (renderSystem.contains("VoxyHandoffPolicy.updateCamera(\n"
                + "                        viewport.cameraX,\n"
                + "                        viewport.cameraZ,\n"
                + "                        Minecraft.getInstance().options.getEffectiveRenderDistance()")) {
            throw new AssertionError("Render-frame camera updates must not overwrite merged distance policy with vanilla render distance");
        }
        rejectSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java"),
                "sectionRenderDistance",
                "Traversal must not use the deprecated Voxy sectionRenderDistance field");
        rejectSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinGameRenderer.java"),
                "sectionRenderDistance",
                "Far plane must use the merged visual terrain distance");
        rejectSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinFogRenderer.java"),
                "sectionRenderDistance",
                "Terrain fog capture must use the merged visual terrain distance");
        rejectSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/mixin/sodium/MixinCloudRenderer.java"),
                "sectionRenderDistance",
                "Cloud distance must use the merged visual terrain distance");
    }

    private static void assertMergedSliderAdaptersAreRegistered() {
        requireSourceContains(
                Path.of("src/main/resources/client.voxy.mixins.json"),
                "sodium.MixinSodiumConfigBuilder",
                "Sodium render-distance slider mixin must be registered");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/mixin/sodium/MixinSodiumConfigBuilder.java"),
                "setRange(III)",
                "Sodium render-distance slider range must be widened in Voxy merged mode");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/mixin/sodium/MixinSodiumConfigBuilder.java"),
                "VoxyMergedRenderDistance::setVisualFromSlider",
                "Sodium render-distance slider must write Voxy visual distance in merged mode");
        rejectSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/config/VoxyConfigMenu.java"),
                "voxy:render_distance",
                "Voxy config screen must not expose a second render-distance slider");
    }

    private static void assertDiagnosticsExposeMergedCoverageFields() {
        String diagnostics = readSource(Path.of("src/main/java/me/cortex/voxy/common/debug/RenderCorrectnessDiagnostics.java"));
        for (String field : new String[] {
                "render_distance_slider_mode",
                "visual_terrain_distance_chunks",
                "vanilla_real_render_distance_chunks",
                "voxy_lod_start_chunks",
                "voxy_lod_end_chunks",
                "boundary_ring_rejected_sections",
                "terrain_coverage_verdict"
        }) {
            if (!diagnostics.contains(field)) {
                throw new AssertionError("Render diagnostics must expose merged distance field: " + field);
            }
        }
        String serverLod = readSource(Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodDiagnostics.java"));
        if (!serverLod.contains("boundaryRingRejectedSections")) {
            throw new AssertionError("Server LoD diagnostics must count rejected boundary sections");
        }
        if (!serverLod.contains("terrain_coverage_verdict")) {
            throw new AssertionError("Server LoD diagnostics must emit merged terrain coverage verdict");
        }
    }

    private static void assertHarnessProofUsesMergedDistanceVerdict() {
        String harness = readSource(Path.of("../../ModpackTestHarness/src/main/java/com/slavks/mctestharness/RenderStackCliController.java"));
        if (!harness.contains("PASS_MERGED_RENDER_DISTANCE_NO_GAP")) {
            throw new AssertionError("Harness proof must use merged render-distance pass verdict");
        }
        if (!harness.contains("applyVoxyMergedRenderDistance")) {
            throw new AssertionError("Harness render-distance command must route through Voxy's merged policy when available");
        }
        if (!harness.contains("visual_terrain_distance_chunks")) {
            throw new AssertionError("Harness proof must report visual terrain distance");
        }
        if (!harness.contains("vanilla_real_render_distance_chunks")) {
            throw new AssertionError("Harness proof must report capped real vanilla distance");
        }
        for (String providerField : new String[] {
                "provider_mode",
                "provider_snapshot_valid",
                "exact_owned_cells",
                "lod_owned_cells",
                "parent_fallback_cells",
                "invalid_rejected_cells",
                "parent_child_conflict_cells",
                "boundary_queue_depth",
                "far_queue_depth",
                "boundary_exact_sections",
                "boundary_parent_fallback_sections",
                "boundary_rejected_sections",
                "boundary_missing_sections",
                "boundary_missing_required_cells",
                "invalid_rendered_sections",
                "boundary_source_real_chunk_sections",
                "boundary_source_surface_preview_sections",
                "boundary_source_synthetic_preview_sections",
                "boundary_source_unknown_sections",
                "boundary_degraded_preview_sections",
                "ore_leak_surface_representatives",
                "parent_child_visual_conflicts",
                "unsupported_pass_rendered_sections",
                "sodium_material_parity_verdict",
                "fog_depth_parity_verdict",
                "provider_visual_artifact_verdict",
                "unsupported_pass_skips",
                "iris_fail_closed_skips",
                "solid_pass_meshes",
                "cutout_pass_meshes",
                "fluid_pass_meshes",
                "translucent_pass_meshes",
                "provider_terrain_coverage_verdict",
                "provider_visual_source_verdict",
                "provider_render_list_length",
                "provider_render_list_epoch",
                "provider_render_list_stale_skips",
                "provider_command_generation_count",
                "provider_traversal_bypass_count",
                "provider_render_authority_verdict"
        }) {
            if (!harness.contains(providerField)) {
                throw new AssertionError("Harness proof must report provider diagnostic field: " + providerField);
            }
        }
        if (!harness.contains("voxy_visual_artifact_probe")) {
            throw new AssertionError("Harness must expose voxy_visual_artifact_probe for targeted no-artifact live gates");
        }
        if (!harness.contains("voxy_visual_correctness_probe")) {
            throw new AssertionError("Harness must expose voxy_visual_correctness_probe as the Minecraft-side LoD visual authority");
        }
        if (!harness.contains("voxy.minecraft_lod_visual_authority.v1")) {
            throw new AssertionError("Harness visual correctness probe must emit the stable authority schema");
        }
        if (!harness.contains("voxy.minecraft_screen_space_visual_metrics.v1")) {
            throw new AssertionError("Harness visual correctness authority must include Minecraft-side framebuffer metrics");
        }
        if (!harness.contains("screen_space_blank_dominated")) {
            throw new AssertionError("Harness visual correctness authority must fail closed on blank-dominated frames");
        }
        if (!harness.contains("screen_space_central_blank_band_pixels")) {
            throw new AssertionError("Harness visual correctness authority must reject central blank bands before sidecar confirmation");
        }
        requireSourceContains(
                Path.of("../../../tools/render-stack/Send-HarnessCliCommand.ps1"),
                "voxy_visual_correctness_probe",
                "Harness CLI sender must expose the Minecraft-side LoD visual authority probe");
        requireSourceContains(
                Path.of("../../../tools/render-stack/Analyze-VoxyVisualCorrectnessSidecar.ps1"),
                "voxy.sidecar_visual_confirmation.v1",
                "Sidecar analyzer must emit a stable visual confirmation schema");
    }

    private static void requireSourceContains(Path sourcePath, String expected, String message) {
        String source = readSource(sourcePath);
        if (!source.contains(expected)) {
            throw new AssertionError(message);
        }
    }

    private static void rejectSourceContains(Path sourcePath, String rejected, String message) {
        String source = readSource(sourcePath);
        if (source.contains(rejected)) {
            throw new AssertionError(message);
        }
    }

    private static void requireEnabledMixin(String mixinClass, String message) {
        String source = readSource(Path.of("src/main/resources/client.voxy.mixins.json"));
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("//") && trimmed.contains("\"" + mixinClass + "\"")) {
                return;
            }
        }
        throw new AssertionError(message);
    }

    private static void assertParentMeshArtifactGuardsDefaultOn() {
        String source = readSource(Path.of("src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java"));
        if (!source.contains("System.getProperty(\"voxy.dropLeafParentMeshOnRefinement\", \"true\")")) {
            throw new AssertionError("Refinable parent meshes must be dropped by default to avoid parent/child double rendering");
        }
        if (!source.contains("System.getProperty(\"voxy.suppressUnrefinableParentMesh\", \"true\")")) {
            throw new AssertionError("Unrefinable parent meshes must be suppressed by default to avoid random coarse blocks");
        }
    }

    private static void assertSparseServerLodMappingsAreImported() {
        String source = readSource(Path.of("src/main/java/me/cortex/voxy/common/world/other/Mapper.java"));
        if (!source.contains("fillImportedStateMappingGap")) {
            throw new AssertionError("Sparse server LoD block mappings must be gap-filled instead of skipped");
        }
        if (!source.contains("fillImportedBiomeMappingGap")) {
            throw new AssertionError("Sparse server LoD biome mappings must be gap-filled instead of skipped");
        }
        if (!source.contains("ensureImportedMappingCoverage")) {
            throw new AssertionError("Server LoD section payload IDs must extend mapper coverage after decode");
        }
        String bakery = readSource(Path.of("src/main/java/me/cortex/voxy/client/core/model/ModelBakerySubsystem.java"));
        if (!bakery.contains("this.mapper.ensureImportedMappingCoverage(blockId, 0)")) {
            throw new AssertionError("Model bake requests must repair missing imported block IDs before rejecting them");
        }
        String modelFactory = readSource(Path.of("src/main/java/me/cortex/voxy/client/core/model/ModelFactory.java"));
        if (!modelFactory.contains("resolveBiomeForTint")) {
            throw new AssertionError("Biome tint baking must default sparse/null biome slots instead of crashing or writing invalid colors");
        }
        String nodeManager = readSource(Path.of("src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java"));
        if (!nodeManager.contains("VoxyHandoffPolicy.isBoundaryRingSection")) {
            throw new AssertionError("Refinable parent meshes must be suppressed in the handoff ring");
        }
        if (nodeManager.contains("parent_retained_until_child_mesh_commit")) {
            throw new AssertionError("Boundary parents must not be retained once child/finer coverage exists");
        }
    }

    private static void assertServerAuthoredNeoForgeContractIsExposed() {
        String contract = readSource(Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodNeoForgeContract.java"));
        ServerLodNeoForgeContract.ContractSnapshot snapshot = ServerLodNeoForgeContract.snapshot();
        if (!snapshot.pass()) {
            throw new AssertionError("Default NeoForge LoD contract snapshot must pass: " + snapshot.toJson());
        }
        for (String expected : new String[] {
                "SERVER_THREAD_SNAPSHOT",
                "WORKER_COMPUTE_ONLY",
                "SERVER_THREAD_PUBLISH",
                "terrain_lod",
                "forbidWorkerMinecraftMutation"
        }) {
            if (!contract.contains(expected)) {
                throw new AssertionError("NeoForge LoD contract must expose " + expected);
            }
        }
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodDiagnostics.java"),
                "serverLodContractJson",
                "Server LoD diagnostics must expose the NeoForge contract JSON");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodCommands.java"),
                "contract",
                "Server LoD commands must expose /voxy serverlod contract");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerAuthoredLodBuilder.java"),
                "ServerLodNeoForgeContract",
                "Minecraft-authored LoD builder must declare the server-thread NeoForge contract it follows");
    }

    private static void assertServerLodCacheSynthesizesUsefulParentsByDefault() {
        Path cacheStorage = Path.of("src/main/java/me/cortex/voxy/common/config/section/ServerLodCacheSectionStorage.java");
        requireSourceContains(
                cacheStorage,
                "System.getProperty(\"voxy.serverLodCacheSynthesizeMissingParents\", \"true\")",
                "Server LoD cache must synthesize missing parents by default so boundary fallback coverage can fill gaps");
        requireSourceContains(
                cacheStorage,
                "containsNonAir",
                "Server LoD cache parent synthesis must detect exact leaf children with voxel data but no child metadata");
        requireSourceContains(
                cacheStorage,
                "child.getNonEmptyChildren() != 0 || containsNonAir(child)",
                "Server LoD cache parent synthesis must mark children useful when they contain non-air voxel data");
    }

    private static void assertServerLodSyncAutoBuildsAndRefreshesManifests() {
        Path syncManager = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodSyncManager.java");
        Path builder = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerAuthoredLodBuilder.java");
        Path store = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodTileStore.java");
        requireSourceContains(
                syncManager,
                "ServerAuthoredLodBuilder.ensureCoverage",
                "Server LoD sync must automatically start authored generation when visible coverage is insufficient");
        requireSourceContains(
                syncManager,
                "payload.requestedRadius()",
                "Automatic authored LoD generation must derive its bounded radius from the client visual request");
        requireSourceContains(
                syncManager,
                "broadcastManifests",
                "Server LoD sync must be able to push fresh manifests after authored generation publishes tiles");
        requireSourceContains(
                builder,
                "broadcastManifests(\"authored build finished\")",
                "Authored LoD builder must refresh connected clients after publishing server tiles");
        requireSourceContains(
                builder,
                "voxy.serverLodAutoBuildRadius",
                "Automatic authored LoD generation must use a bounded radius instead of the full client request radius");
        requireSourceContains(
                store,
                "public List<ServerLodTileMetadata> priorityManifest",
                "Server LoD visible manifests must be generated from the priority manifest path");
        requireSourceContains(
                store,
                "this.refreshIndex();\n        var out = new ArrayList<ServerLodTileMetadata>",
                "Server LoD priority manifests must refresh persisted index files before deciding visible coverage");
    }

    private static void assertServerLodSyncAutoBuildsWhenCoverageRadiusIsTooSmall() {
        Path syncManager = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodSyncManager.java");
        Path builder = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerAuthoredLodBuilder.java");
        Path store = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodTileStore.java");
        requireSourceContains(
                store,
                "maxAdvertisedChunkReach",
                "Server LoD stores must expose advertised chunk reach for radius coverage gating");
        requireSourceContains(
                builder,
                "automaticCoverageRadius",
                "Server LoD auto-build radius must be reusable by sync coverage checks");
        requireSourceContains(
                builder,
                "shape=square",
                "Automatic LoD coverage must match Minecraft's square render-distance ownership grid");
        requireSourceContains(
                syncManager,
                "advertisedReach < requiredCoverageRadius",
                "Server LoD sync must rebuild dense-but-too-small stores instead of relying on manifest count");
        requireSourceContains(
                syncManager,
                "advertised_reach=",
                "Server LoD auto-build diagnostics must report advertised reach and required radius");
    }

    private static void assertServerLodSyncUsesExactTileCacheIdentity() {
        Path constants = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodConstants.java");
        Path syncManager = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodSyncManager.java");
        Path clientSync = Path.of("src/main/java/me/cortex/voxy/client/serverlod/ClientServerLodSync.java");
        requireSourceContains(
                constants,
                "tileCacheIdentity(ServerLodTileMetadata metadata)",
                "Server LoD cache identity must be a first-class exact tile identity, not only a payload hash");
        requireSourceContains(
                constants,
                "metadata.key().stableId() + \"|\" + metadata.contentHash()",
                "Server LoD cache identity must include both tile key and content hash");
        requireSourceContains(
                syncManager,
                "!cached.contains(ServerLodConstants.tileCacheIdentity(metadata))",
                "Server LoD manifest filtering must not suppress distinct tiles that share a content hash");
        requireSourceContains(
                clientSync,
                ".map(ServerLodConstants::tileCacheIdentity)",
                "Client LoD cache hello must advertise exact tile cache identities");
        rejectSourceContains(
                syncManager,
                "!cached.contains(metadata.contentHash())",
                "Server LoD manifest filtering must not use non-unique content hashes as tile ownership proof");
    }

    private static void assertServerLodSyncRemountsWorldStore() {
        Path syncManager = Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodSyncManager.java");
        requireSourceContains(
                syncManager,
                "getStoreFor(ServerPlayer player)",
                "Server LoD sync must resolve the active store from the player world, not a stale early static path");
        requireSourceContains(
                syncManager,
                "storeRootFor(ServerPlayer player)",
                "Server LoD sync must compute the store root from the active server world");
        requireSourceContains(
                syncManager,
                "Mounted Voxy server LoD store for player world",
                "Server LoD sync must make world-store remounts visible in logs");
        requireSourceContains(
                syncManager,
                "getStoreFor(player).priorityManifest",
                "Server LoD visible manifests must use the player-world store");
        requireSourceContains(
                syncManager,
                "var activeStore = getStoreFor(player);",
                "Server LoD tile requests must read from the same player-world store used for manifests");
    }

    private static void assertProductionHardeningIssuesAreClosed() {
        Path renderDataFactory = Path.of("src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java");
        requireSourceContains(
                renderDataFactory,
                "System.getProperty(\"voxy.surfacePreviewTopFacesOnly\", \"true\")",
                "Surface preview top-face-only mode must be default-on to prevent volumetric shell artifacts");

        Path mapper = Path.of("src/main/java/me/cortex/voxy/common/world/other/Mapper.java");
        requireSourceContains(
                mapper,
                "fallbackImportedBlockState",
                "Mapper must centralize imported missing/corrupt block-state fallback policy");
        requireSourceContains(
                mapper,
                "Blocks.STONE.defaultBlockState()",
                "Imported missing/corrupt nonzero block-state mappings must fallback to solid stone, not air holes");

        Path depthFramebuffer = Path.of("src/main/java/me/cortex/voxy/client/core/rendering/util/DepthFramebuffer.java");
        requireSourceContains(
                depthFramebuffer,
                "glCheckNamedFramebufferStatus",
                "Depth framebuffer resize must verify driver framebuffer completeness");
        requireSourceContains(
                depthFramebuffer,
                "GL_FRAMEBUFFER_COMPLETE",
                "Depth framebuffer resize must fail closed when depth attachment creation is incomplete");

        Path materialResolver = Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainMaterialResolver.java");
        requireSourceContains(
                materialResolver,
                "ThreadLocal<Map<CacheKey, VoxyResolvedTerrainMaterial>>",
                "Material resolver must cache per-thread material lookups for parallel meshing");
        requireSourceContains(
                materialResolver,
                "record CacheKey",
                "Material resolver cache must key block, biome, light, and pass together");

        Path lifecycleBridge = Path.of("src/main/java/me/cortex/voxy/client/sodium/VoxySodiumSectionLifecycleBridge.java");
        requireSourceContains(
                lifecycleBridge,
                "clearForWorldChange",
                "Sodium lifecycle bridge must expose an explicit world-change clear hook");
        requireSourceContains(
                lifecycleBridge,
                "provider.resetRuntimeState(provider.snapshot())",
                "Sodium lifecycle bridge world-change clear must reset provider-owned state");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinLevelRenderer.java"),
                "VoxySodiumSectionLifecycleBridge.clearForWorldChange(\"level change\")",
                "Level changes must invoke the Sodium lifecycle bridge clear hook before renderer shutdown");

        Path serviceManager = Path.of("src/main/java/me/cortex/voxy/common/thread/ServiceManager.java");
        requireSourceContains(
                serviceManager,
                "public synchronized void shutdown()",
                "ServiceManager shutdown must be synchronized so concurrent shutdown cannot race");
    }

    private static void assertMillionDollarProductionBlockersAreClosed() {
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/VoxyClient.java"),
                "voxy.allowHarnessVoxyOnlyMode",
                "voxy_only must be an explicit harness-only opt-in and must not disable Sodium in normal gameplay");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/VoxyClient.java"),
                "return isHarnessVoxyOnlyModeAllowed() && \"voxy_only\".equals(visualAttributionMode)",
                "Sodium chunk rendering must not be disabled by visual attribution mode alone");
        rejectSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java"),
                "boolean sodiumCompatible = this.shouldRenderDuringSodiumPass(safePass);",
                "Provider draw decisions must not mutate unsupported-pass diagnostics while only deciding");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java"),
                "incoming == BoundaryCoverageState.REJECTED || incoming == BoundaryCoverageState.MISSING",
                "Boundary coverage must fail closed when current coverage reports a rejected or missing section");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java"),
                "providerRenderListCapacity()",
                "Provider render-list state must be capped to the actual upload buffer capacity");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java"),
                "Arrays.copyOf(safeMeshIds, maxListCount)",
                "Provider render-list truncation must not leave CPU state larger than the GPU list");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/common/world/SaveLoadSystem3.java"),
                "serialized section too short",
                "Section deserialization must reject buffers shorter than the fixed section header and index data");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/common/world/SaveLoadSystem3.java"),
                "lutEntryCount",
                "Section deserialization must bounds-check LUT entries before unsafe reads");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/common/world/SaveLoadSystem3.java"),
                "lutIndex >= lutEntryCount",
                "Section deserialization must reject per-voxel LUT indices outside the decoded LUT");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/commonImpl/serverlod/ServerLodTileStore.java"),
                "implements AutoCloseable",
                "Server LoD tile stores with mounted region/compact sources must expose deterministic close");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/serverlod/ClientServerLodSync.java"),
                "oldCache.close()",
                "Client server-LoD cache swaps must close the previous mounted store");
    }

    private static void assertSodiumCompatibleProviderBoundaryExists() {
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java"),
                "shouldRenderDuringSodiumPass",
                "Voxy must expose a Sodium-compatible far-terrain provider pass gate");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainOwnershipMap.java"),
                "classifyChunkRadius",
                "Voxy provider must classify vanilla/LoD/parent-fallback ownership on CPU");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyTerrainMaterialResolver.java"),
                "UNRESOLVED_BLOCK_STATE",
                "Voxy provider must fail closed on unresolved block states before meshing");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java"),
                "renderSodiumProviderPass",
                "Sodium terrain pass injection must route through the Voxy far-terrain provider boundary");
        requireEnabledMixin(
                "sodium.MixinDefaultChunkRenderer",
                "The Sodium provider dispatch mixin must be enabled in the production client mixin config");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/VoxySodiumSectionLifecycleBridge.java"),
                "getFarTerrainProvider().invalidateSection",
                "Sodium lifecycle bridge must invalidate provider-owned section state");
    }

    private static void assertSodiumCompatibleProviderIsRuntimeAuthority() {
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java"),
                "PASS_SODIUM_COMPATIBLE_PROVIDER_NO_GAP",
                "Provider diagnostics must expose the production no-gap verdict");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java"),
                "recordOwnershipDecision",
                "Provider must record authoritative ownership decisions");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java"),
                "RENDER_PASS_UNSUPPORTED",
                "Provider must fail closed for unsupported render passes");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java"),
                "VoxyTerrainOwnershipMap",
                "Node manager parent/child decisions must route through provider ownership policy");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java"),
                "VoxyBoundaryPriorityQueue.LANE_BOUNDARY",
                "Mesh build scheduling must prioritize boundary-ring work before far shells");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java"),
                "recordMaterialRejected",
                "Mesh build failures must be reported as provider material rejections");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java"),
                "validatePreparedSectionForProvider",
                "Production mesh generation must validate provider material data before emission");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "getRendererProviderVisualArtifactVerdict",
                "Provider diagnostics must expose the full visual artifact production verdict");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/common/debug/RenderCorrectnessDiagnostics.java"),
                "ore_leak_surface_representatives",
                "Runtime diagnostics must count ore-like buried representatives leaking into surface LoDs");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "SKIPPED_INCOMPLETE_IRIS_STATE",
                "Iris provider path must fail closed instead of corrupting Sodium terrain state");
    }

    private static void assertProviderIsProductionRenderAuthority() {
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyProviderRenderIndex.java"),
                "ConcurrentHashMap",
                "Provider must maintain an authoritative runtime render index");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyProviderRenderCell.java"),
                "renderOwned()",
                "Provider render cells must decide whether a section is render-owned");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyProviderMeshApproval.java"),
                "approved",
                "Provider mesh approval must exist before geometry is render-owned");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java"),
                "recordCommittedMesh",
                "Committed Voxy meshes must be recorded into the provider render index");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java"),
                "recordStaleUploadRejection",
                "Stale mesh uploads must be rejected by provider authority");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "drawDecision(pass)",
                "Sodium dispatch must ask the provider draw-decision contract whether a pass may issue Voxy draw work");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "getRendererProviderRenderIndexSize",
                "Provider render index size must be exposed for diagnostics");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "getRendererProviderRenderAuthorityVerdict",
                "Provider render authority verdict must be exposed for harness diagnostics");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "getRendererProviderRenderListLength",
                "Provider render-list length must be exposed for diagnostics");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "getRendererProviderTraversalBypassCount",
                "Provider traversal-bypass count must be exposed for diagnostics");
    }

    private static void assertProviderDrawAuthorityContract() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                8,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );

        provider.recordCommittedMesh(42L, exactCell, 7, 1L);
        VoxyProviderDrawDecision solidDecision = provider.drawDecision(VoxyTerrainPass.SOLID);
        if (!solidDecision.draw()) {
            throw new AssertionError("Provider must authorize SOLID drawing when it owns valid render-index geometry");
        }
        if (solidDecision.sectionCount() != 1) {
            throw new AssertionError("Provider draw decision must report the exact render-owned section count");
        }
        if (solidDecision.meshIds().length != 1 || solidDecision.meshIds()[0] != 7) {
            throw new AssertionError("Provider draw decision must expose the exact provider-owned mesh id set");
        }
        VoxyProviderRenderList solidList = solidDecision.renderList();
        if (solidList.meshIds().length != 1 || solidList.meshIds()[0] != 7) {
            throw new AssertionError("Provider draw decision must expose an immutable provider-owned render list");
        }
        if (solidList.epoch() <= 0) {
            throw new AssertionError("Provider render list must carry a monotonic epoch for stale-list rejection");
        }
        if (solidList.sectionCount() != 1) {
            throw new AssertionError("Provider render list must report the compact provider-owned section count");
        }
        if (provider.providerDrawnCutoutSections() != 0) {
            throw new AssertionError("SOLID-only provider geometry must not report CUTOUT sections as draw-owned");
        }
        if (!VoxyFarTerrainProvider.PASS_PROVIDER_RENDER_AUTHORITY.equals(provider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Valid provider-owned geometry must pass the render-authority verdict");
        }

        VoxyFarTerrainProvider cutoutProvider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        cutoutProvider.recordCommittedMesh(43L, exactCell, 8, 2L, VoxyProviderRenderCell.PASS_CUTOUT);
        if (cutoutProvider.shouldRenderDuringSodiumPass(VoxyTerrainPass.CUTOUT)) {
            throw new AssertionError("CUTOUT provider geometry must stay disabled until independent CUTOUT dispatch is proven");
        }
        if (VoxyFarTerrainProvider.PASS_PROVIDER_RENDER_AUTHORITY.equals(cutoutProvider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("CUTOUT-only provider geometry must not pass production render authority while CUTOUT is unsupported");
        }
        if (VoxyFarTerrainProvider.PASS_NO_GAP.equals(cutoutProvider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("CUTOUT-only provider geometry must not produce no-gap while CUTOUT is unsupported");
        }
        if (cutoutProvider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("CUTOUT provider geometry must not enter the SOLID production render list");
        }
        VoxyProviderDrawDecision cutoutDecision = cutoutProvider.drawDecision(VoxyTerrainPass.CUTOUT);
        if (cutoutDecision.draw()) {
            throw new AssertionError("CUTOUT provider geometry must not draw before the independent CUTOUT render list is supported");
        }
        cutoutProvider.recordRenderedPass(VoxyTerrainPass.CUTOUT);
        if (!VoxyFarTerrainProvider.FAIL_UNSUPPORTED_PASS_RENDERED.equals(cutoutProvider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Rendering CUTOUT before independent support must fail provider authority");
        }

        VoxyFarTerrainProvider mixedProvider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        mixedProvider.recordCommittedMesh(
                44L,
                exactCell,
                9,
                3L,
                VoxyProviderRenderCell.PASS_SOLID | VoxyProviderRenderCell.PASS_CUTOUT
        );
        VoxyProviderDrawDecision mixedSolidDecision = mixedProvider.drawDecision(VoxyTerrainPass.SOLID);
        VoxyProviderDrawDecision mixedCutoutDecision = mixedProvider.drawDecision(VoxyTerrainPass.CUTOUT);
        if (mixedSolidDecision.draw()) {
            throw new AssertionError("Mixed SOLID/CUTOUT provider geometry must not draw as opaque fallback");
        }
        if (mixedCutoutDecision.draw()) {
            throw new AssertionError("Mixed SOLID/CUTOUT provider geometry must not draw until mesh pass splitting is proven");
        }

        VoxyFarTerrainProvider parentThenChildProvider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long parentSection = WorldEngine.getWorldSectionId(1, 0, 0, 0);
        long childSection = WorldEngine.getWorldSectionId(0, 1, 0, 0);
        VoxyTerrainOwnershipCell parentFallbackCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                1,
                VoxyTerrainOwnership.VOXY_PARENT_FALLBACK,
                VoxyTerrainFailureReason.VALID_PARENT_FALLBACK
        );
        parentThenChildProvider.recordCommittedMesh(parentSection, parentFallbackCell, 21, 4L);
        parentThenChildProvider.recordCommittedMesh(childSection, exactCell, 22, 5L);
        if (parentThenChildProvider.parentSuppressedSections() <= 0) {
            throw new AssertionError("Provider render index must suppress an existing parent fallback when child ownership arrives");
        }
        if (!java.util.Arrays.equals(
                parentThenChildProvider.drawDecision(VoxyTerrainPass.SOLID).meshIds(),
                new int[] {22})) {
            throw new AssertionError("Provider render list must not contain both parent and child mesh ids after child ownership arrives");
        }
        if (parentThenChildProvider.diagnostics().boundaryParentFallbackSections() != 0) {
            throw new AssertionError("Provider diagnostics must remove parent fallback boundary coverage when child ownership suppresses the parent");
        }
        if (parentThenChildProvider.diagnostics().boundaryExactSections() != 1) {
            throw new AssertionError("Provider diagnostics must keep child exact boundary coverage after parent suppression");
        }

        VoxyFarTerrainProvider childThenParentProvider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        childThenParentProvider.recordCommittedMesh(childSection, exactCell, 32, 6L);
        childThenParentProvider.recordCommittedMesh(parentSection, parentFallbackCell, 31, 7L);
        if (!java.util.Arrays.equals(
                childThenParentProvider.drawDecision(VoxyTerrainPass.SOLID).meshIds(),
                new int[] {32})) {
            throw new AssertionError("Provider render index must reject an incoming parent fallback when child ownership already exists");
        }
        if (childThenParentProvider.diagnostics().boundaryParentFallbackSections() != 0) {
            throw new AssertionError("Provider diagnostics must not retain boundary fallback coverage for a rejected incoming parent");
        }
        if (childThenParentProvider.diagnostics().boundaryExactSections() != 1) {
            throw new AssertionError("Provider diagnostics must keep the existing child exact coverage when rejecting an incoming parent");
        }

        VoxyProviderDrawDecision translucentDecision = provider.drawDecision(VoxyTerrainPass.TRANSLUCENT);
        if (translucentDecision.draw()) {
            throw new AssertionError("Provider must fail closed for unsupported translucent terrain");
        }
        provider.recordRenderedPass(VoxyTerrainPass.TRANSLUCENT);
        if (!VoxyFarTerrainProvider.FAIL_UNSUPPORTED_PASS_RENDERED.equals(provider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Rendering an unsupported pass must fail the provider authority verdict");
        }
        if (!VoxyFarTerrainProvider.FAIL_UNSUPPORTED_PASS_RENDERED.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("Rendering an unsupported pass must fail the terrain coverage verdict");
        }
        VoxyProviderDrawDecision unsupportedRenderedSolidDecision = provider.drawDecision(VoxyTerrainPass.SOLID);
        if (unsupportedRenderedSolidDecision.draw()) {
            throw new AssertionError("Unsupported rendered pass must deny SOLID provider drawing");
        }
        if (!java.util.Arrays.equals(unsupportedRenderedSolidDecision.meshIds(), new int[] {7})) {
            throw new AssertionError("Denied supported pass draw decision must keep provider mesh ids for diagnostics");
        }
        if (unsupportedRenderedSolidDecision.renderList().epoch() != solidList.epoch()) {
            throw new AssertionError("Denied supported pass draw decision must keep provider render-list epoch");
        }

        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/AbstractRenderPipeline.java"),
                "setProviderRenderList",
                "Render pipeline must expose a provider-owned render list boundary");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "renderProviderOpaque(viewport, drawDecision)",
                "Sodium provider pass must render through provider-owned draw submission");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "prepareProviderTraversal(viewport)",
                "Sodium provider pass must populate traversal and provider ownership before asking for provider-owned render lists");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "tickProviderRuntimeState(viewport)",
                "Sodium provider pass must tick render-distance trackers before provider-only traversal");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/AbstractRenderPipeline.java"),
                "prepareProviderTraversal",
                "Render pipeline must expose a non-legacy-draw provider preparation path");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/AbstractRenderPipeline.java"),
                "TimingStatistics.main.start()",
                "Provider traversal preparation must mirror the normal render timing lifecycle before innerPrimaryWork");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "Skipping Voxy provider traversal preparation after runtime failure",
                "Provider traversal preparation must fail closed instead of crashing the client");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java"),
                "PROVIDER_RENDER_LIST_BUFFER_BINDING",
                "MDIC command generation must bind a compact provider render list");
        requireSourceContains(
                Path.of("src/main/resources/assets/voxy/shaders/lod/gl46/cmdgen.comp"),
                "providerRenderList",
                "MDIC command generation shader must source mesh ids from the provider render list");
        requireSourceContains(
                Path.of("src/main/resources/assets/voxy/shaders/lod/gl46/cmdgen.comp"),
                "sectionId = providerRenderList",
                "Provider command generation must bypass traversal lookup when a provider list is active");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java"),
                "providerRenderListPass",
                "Provider render-list uploads must include the active terrain pass so mixed SOLID/CUTOUT meshes cannot reuse stale commands");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java"),
                "providerRenderListStaleSkipCallback.run()",
                "Stale provider render-list skips must be reported to diagnostics instead of silently dropping terrain");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java"),
                "setProviderRenderListStaleSkipCallback(this.farTerrainProvider::recordProviderRenderListStaleSkip)",
                "The render system must wire stale provider render-list skips into provider diagnostics");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyProviderRenderIndex.java"),
                "hasRenderOwnedDescendant",
                "Provider render lists must reject parent fallback meshes when child ownership already exists");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyProviderRenderIndex.java"),
                "removeRenderOwnedAncestors",
                "Provider render lists must remove parent fallback meshes when child ownership arrives");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyProviderRenderIndex.java"),
                "isAncestorOf",
                "Provider render-list parent/child suppression must be hierarchy-aware");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/sodium/provider/VoxyFarTerrainProvider.java"),
                "suppressedIncomingParent",
                "Provider diagnostics must count parent suppression caused by child-owned render cells");
        requireSourceContains(
                Path.of("src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java"),
                "boolean exactLodAvailable = level == 0 && parentFallbackAvailable;",
                "Provider ownership classification must not treat coarse parent child-existence metadata as exact rendered LoD coverage");
        requireSourceContains(
                Path.of("src/main/resources/assets/voxy/shaders/lod/gl46/cmdgen.comp"),
                "providerRenderListPassMask",
                "Provider command generation must receive a pass mask for mixed SOLID/CUTOUT mesh ids");
        requireSourceContains(
                Path.of("src/main/resources/assets/voxy/shaders/lod/gl46/cmdgen.comp"),
                "providerDrawSolidBuffers",
                "Provider command generation must be able to suppress SOLID buffers during CUTOUT passes");
        requireSourceContains(
                Path.of("src/main/resources/assets/voxy/shaders/lod/gl46/cmdgen.comp"),
                "providerDrawCutoutBuffers",
                "Provider command generation must be able to suppress CUTOUT buffers during SOLID passes");
    }

    private static void assertProviderCurrentRefreshDoesNotRetainStaleGaps() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long staleMissingSection = WorldEngine.getWorldSectionId(0, 20, 0, 20);
        long currentExactSection = WorldEngine.getWorldSectionId(0, 4, 0, 4);
        VoxyTerrainOwnershipCell currentExactCell = new VoxyTerrainOwnershipCell(
                8,
                8,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        provider.recordCommittedMesh(currentExactSection, currentExactCell, 91, 1L);
        provider.recordBoundaryMissingSection(staleMissingSection);
        if (!VoxyFarTerrainProvider.FAIL_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("A recorded boundary miss must fail terrain coverage before the current refresh");
        }

        provider.beginCurrentOwnershipRefresh();
        provider.recordCurrentRenderCell(
                currentExactSection,
                currentExactCell,
                91,
                1L
        );
        provider.finishCurrentOwnershipRefresh();

        if (provider.diagnostics().boundaryMissingRequiredCells() != 0) {
            throw new AssertionError("Current provider coverage refresh must not retain stale boundary misses");
        }
        if (!VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("Current render-owned coverage must replace stale missing coverage in diagnostics");
        }
        if (!provider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("Current render-owned coverage must produce a provider render-list draw decision");
        }
    }

    private static void assertProviderCurrentRefreshDoesNotInventSolidPassOwnership() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long unknownPassSection = WorldEngine.getWorldSectionId(0, 5, 0, 5);
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                8,
                8,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );

        provider.beginCurrentOwnershipRefresh();
        provider.recordCurrentRenderCell(unknownPassSection, exactCell, 92, 1L);
        provider.finishCurrentOwnershipRefresh();

        if (provider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("Current refresh must not invent SOLID provider ownership for unknown pass geometry");
        }
        if (VoxyFarTerrainProvider.PASS_PROVIDER_RENDER_AUTHORITY.equals(provider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Unknown pass geometry must not pass provider render authority");
        }
    }

    private static void assertProviderRejectedMeshCommitIsDiagnosticFailure() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        provider.recordCommittedMesh(
                WorldEngine.getWorldSectionId(0, 6, 0, 0),
                exactCell,
                93,
                1L,
                VoxyProviderRenderCell.PASS_SOLID | VoxyProviderRenderCell.PASS_CUTOUT
        );

        if (provider.diagnostics().boundaryRejectedSections() == 0) {
            throw new AssertionError("Rejected mixed-pass provider mesh commits must count as boundary rejections");
        }
        if (provider.diagnostics().unsupportedPassSkips() == 0) {
            throw new AssertionError("Rejected mixed-pass provider mesh commits must count unsupported pass skips");
        }
        if (VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("Rejected mixed-pass provider mesh commits must not produce no-gap");
        }
        if (VoxyFarTerrainProvider.PASS_PROVIDER_RENDER_AUTHORITY.equals(provider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Rejected mixed-pass provider mesh commits must not pass render authority");
        }
    }

    private static void assertProviderCurrentRefreshPrunesStaleRenderListEntries() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        long currentSection = WorldEngine.getWorldSectionId(0, 6, 0, 0);
        long staleSection = WorldEngine.getWorldSectionId(0, 7, 0, 0);
        provider.recordCommittedMesh(currentSection, exactCell, 601, 1L);
        provider.recordCommittedMesh(staleSection, exactCell, 602, 1L);
        if (!java.util.Arrays.equals(provider.drawDecision(VoxyTerrainPass.SOLID).meshIds(), new int[] {601, 602})) {
            throw new AssertionError("Test setup expected two provider-owned render-list entries");
        }

        provider.beginCurrentOwnershipRefresh();
        provider.recordCurrentRenderCell(currentSection, exactCell, 601, 1L);
        provider.finishCurrentOwnershipRefresh();

        int[] meshIds = provider.drawDecision(VoxyTerrainPass.SOLID).meshIds();
        if (!java.util.Arrays.equals(meshIds, new int[] {601})) {
            throw new AssertionError("Current refresh must prune stale provider render-list mesh ids, got "
                    + java.util.Arrays.toString(meshIds));
        }
    }

    private static void assertProviderCurrentRefreshPreservesRejectedBoundaryFailure() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long rejectedSection = WorldEngine.getWorldSectionId(0, 6, 0, 0);
        VoxyTerrainOwnershipCell rejectedCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.REJECTED_INVALID,
                VoxyTerrainFailureReason.INVALID_PALETTE_ID
        );

        provider.beginCurrentOwnershipRefresh();
        provider.recordCurrentOwnershipDecision(rejectedSection, rejectedCell);
        provider.finishCurrentOwnershipRefresh();

        if (provider.diagnostics().boundaryRejectedSections() == 0) {
            throw new AssertionError("Current refresh must preserve rejected boundary ownership");
        }
        if (!VoxyFarTerrainProvider.FAIL_UNTRUSTED_SOURCE.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("Current rejected boundary ownership must fail terrain coverage");
        }
        if (!VoxyFarTerrainProvider.FAIL_UNTRUSTED_SOURCE.equals(provider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Current rejected boundary ownership must fail provider authority");
        }
    }

    private static void assertProviderCurrentRefreshIgnoresMissingTraversalPlaceholders() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long coveredSection = WorldEngine.getWorldSectionId(0, 6, 0, 0);
        long placeholderSection = WorldEngine.getWorldSectionId(0, 7, 0, 0);
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        VoxyTerrainOwnershipCell missingPlaceholder = new VoxyTerrainOwnershipCell(
                7,
                0,
                0,
                VoxyTerrainOwnership.REJECTED_INVALID,
                VoxyTerrainFailureReason.MISSING_EXACT_CHILD
        );
        provider.recordCommittedMesh(coveredSection, exactCell, 701, 1L);

        provider.beginCurrentOwnershipRefresh();
        provider.recordCurrentRenderCell(coveredSection, exactCell, 701, 1L);
        provider.recordCurrentOwnershipDecision(placeholderSection, missingPlaceholder);
        provider.finishCurrentOwnershipRefresh();

        if (provider.diagnostics().boundaryMissingRequiredCells() != 0) {
            throw new AssertionError("Current refresh must not turn missing traversal placeholders into hard gaps");
        }
        if (!provider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("Current missing placeholders must not disable valid provider-owned SOLID coverage");
        }
    }

    private static void assertProviderCurrentRefreshDoesNotChurnRenderListEpoch() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long section = WorldEngine.getWorldSectionId(0, 6, 0, 0);
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        provider.recordCommittedMesh(section, exactCell, 701, 1L);
        long initialEpoch = provider.drawDecision(VoxyTerrainPass.SOLID).renderList().epoch();

        provider.beginCurrentOwnershipRefresh();
        provider.recordCurrentRenderCell(section, exactCell, 701, 1L);
        provider.finishCurrentOwnershipRefresh();

        long refreshedEpoch = provider.drawDecision(VoxyTerrainPass.SOLID).renderList().epoch();
        if (refreshedEpoch != initialEpoch) {
            throw new AssertionError("No-op current refresh must not churn provider render-list epoch");
        }
    }

    private static void assertProviderRejectsCommittedMeshAfterInvalidation() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long section = WorldEngine.getWorldSectionId(0, 6, 0, 0);
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );

        provider.invalidateSection(section);
        provider.recordCommittedMesh(section, exactCell, 801, 0L);

        if (provider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("Committed mesh with stale request epoch after invalidation must not draw");
        }
        if (!VoxyFarTerrainProvider.FAIL_STALE_UPLOAD_RENDERED.equals(provider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Committed mesh with stale request epoch after invalidation must fail stale-upload authority");
        }
        if (!VoxyFarTerrainProvider.FAIL_STALE_UPLOAD_RENDERED.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("Committed mesh with stale request epoch after invalidation must fail stale-upload coverage");
        }
        if (provider.drawDecision(VoxyTerrainPass.SOLID).reason() != VoxyTerrainFailureReason.STALE_UPLOAD) {
            throw new AssertionError("Stale committed mesh draw decision must report STALE_UPLOAD");
        }
        if (provider.staleUploadRejections() == 0) {
            throw new AssertionError("Committed mesh with stale request epoch after invalidation must increment stale rejection diagnostics");
        }
    }

    private static void assertProviderRejectsCurrentRefreshMeshAfterInvalidation() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long section = WorldEngine.getWorldSectionId(0, 6, 0, 0);
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );

        provider.invalidateSection(section);
        provider.beginCurrentOwnershipRefresh();
        provider.recordCurrentRenderCell(section, exactCell, 802, 0L);
        provider.finishCurrentOwnershipRefresh();

        if (provider.diagnostics().boundaryExactSections() != 0) {
            throw new AssertionError("Stale current-refresh mesh must not count as exact boundary coverage");
        }
        if (provider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("Current-refresh mesh with stale request epoch after invalidation must not draw");
        }
        if (!VoxyFarTerrainProvider.FAIL_STALE_UPLOAD_RENDERED.equals(provider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Current-refresh mesh with stale request epoch after invalidation must fail stale-upload authority");
        }
        if (!VoxyFarTerrainProvider.FAIL_STALE_UPLOAD_RENDERED.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("Current-refresh mesh with stale request epoch after invalidation must fail stale-upload coverage");
        }
        if (provider.drawDecision(VoxyTerrainPass.SOLID).reason() != VoxyTerrainFailureReason.STALE_UPLOAD) {
            throw new AssertionError("Stale current-refresh mesh draw decision must report STALE_UPLOAD");
        }
    }

    private static void assertProviderMaterialUnsupportedPassIsDiagnosed() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));

        provider.recordMaterialRejected(VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED);

        if (provider.diagnostics().unsupportedPassSkips() == 0) {
            throw new AssertionError("Unsupported render-pass material rejection must increment unsupported pass skips");
        }
        if (provider.diagnostics().invalidRejectedCells() == 0) {
            throw new AssertionError("Unsupported render-pass material rejection must still count as rejected invalid material");
        }
    }

    private static void assertProviderSkipDecisionPreservesExplicitSectionCount() {
        VoxyProviderDrawDecision decision = VoxyProviderDrawDecision.skip(
                VoxyTerrainPass.SOLID,
                3,
                VoxyFarTerrainProvider.FAIL_GAP,
                VoxyTerrainFailureReason.MISSING_EXACT_CHILD
        );
        if (decision.draw()) {
            throw new AssertionError("Explicit skip decision must not draw");
        }
        if (decision.sectionCount() != 3) {
            throw new AssertionError("Explicit skip decision must preserve the supplied diagnostic section count");
        }
        if (decision.meshIds().length != 0) {
            throw new AssertionError("Explicit skip decision without a render list must not invent mesh ids");
        }
    }

    private static void assertProviderCurrentRefreshRetainsChildWhenParentIsSuppressed() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long parentSection = WorldEngine.getWorldSectionId(1, 0, 0, 0);
        long childSection = WorldEngine.getWorldSectionId(0, 1, 0, 0);
        VoxyTerrainOwnershipCell childCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        VoxyTerrainOwnershipCell parentCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                1,
                VoxyTerrainOwnership.VOXY_PARENT_FALLBACK,
                VoxyTerrainFailureReason.VALID_PARENT_FALLBACK
        );
        provider.recordCommittedMesh(childSection, childCell, 901, 1L);

        provider.beginCurrentOwnershipRefresh();
        provider.recordCurrentRenderCell(parentSection, parentCell, 902, 2L);
        provider.finishCurrentOwnershipRefresh();

        if (!java.util.Arrays.equals(provider.drawDecision(VoxyTerrainPass.SOLID).meshIds(), new int[] {901})) {
            throw new AssertionError("Current refresh must retain an existing child mesh when incoming parent fallback is suppressed");
        }
        if (!provider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("Current refresh must keep retained child coverage drawable after suppressing incoming parent fallback");
        }
        if (!VoxyFarTerrainProvider.PASS_PROVIDER_RENDER_AUTHORITY.equals(provider.providerRenderAuthorityVerdict())) {
            throw new AssertionError("Current refresh must restore retained child boundary coverage after suppressing incoming parent fallback");
        }
        if (provider.parentSuppressedSections() == 0) {
            throw new AssertionError("Current refresh must diagnose the suppressed incoming parent fallback");
        }
    }

    private static void assertProviderStaleUploadRejectionClearsBoundaryCoverage() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long staleSection = WorldEngine.getWorldSectionId(0, 6, 0, 0);
        long validSection = WorldEngine.getWorldSectionId(0, 7, 0, 0);
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        provider.recordCommittedMesh(staleSection, exactCell, 911, 1L);
        provider.recordCommittedMesh(validSection, exactCell, 912, 1L);

        provider.recordStaleUploadRejection(staleSection);

        if (provider.diagnostics().boundaryExactSections() != 1) {
            throw new AssertionError("Stale upload rejection must remove stale section boundary coverage");
        }
        if (!java.util.Arrays.equals(provider.drawDecision(VoxyTerrainPass.SOLID).meshIds(), new int[] {912})) {
            throw new AssertionError("Stale upload rejection must keep unrelated provider mesh ids");
        }
    }

    private static void assertProviderParentSuppressionClearsBoundaryCoverage() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        long parentSection = WorldEngine.getWorldSectionId(1, 0, 0, 0);
        long exactSection = WorldEngine.getWorldSectionId(0, 6, 0, 0);
        VoxyTerrainOwnershipCell parentCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                1,
                VoxyTerrainOwnership.VOXY_PARENT_FALLBACK,
                VoxyTerrainFailureReason.VALID_PARENT_FALLBACK
        );
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        provider.recordCommittedMesh(parentSection, parentCell, 921, 1L);
        provider.recordCommittedMesh(exactSection, exactCell, 922, 1L);

        provider.recordParentSuppressed(parentSection);

        if (provider.diagnostics().boundaryParentFallbackSections() != 0) {
            throw new AssertionError("Parent suppression must remove suppressed parent fallback boundary coverage");
        }
        if (!java.util.Arrays.equals(provider.drawDecision(VoxyTerrainPass.SOLID).meshIds(), new int[] {922})) {
            throw new AssertionError("Parent suppression must keep unrelated exact provider mesh ids");
        }
    }

    private static void assertProviderCoverageRequiresRenderOwnedBoundaryMesh() {
        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        ));
        provider.recordBoundarySource(
                me.cortex.voxy.common.voxelization.VoxelizedSection.SourceKind.REAL_CHUNK,
                me.cortex.voxy.common.voxelization.VoxelizedSection.LightSourceKind.REAL_LIGHT,
                me.cortex.voxy.common.voxelization.VoxelizedSection.Confidence.HIGH
        );
        if (VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("Trusted source metadata alone must not produce a no-gap terrain verdict");
        }
        provider.recordBoundaryExactSection(501L);
        if (VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("Boundary counters without provider-owned mesh ids must not produce a no-gap verdict");
        }
        VoxyTerrainOwnershipCell exactCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        provider.recordCommittedMesh(501L, exactCell, 501, 1L);
        if (!VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("A trusted exact boundary mesh owned by the provider must produce a no-gap verdict");
        }
        if (!provider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("A trusted exact boundary mesh owned by the provider must be drawable");
        }
        provider.beginCurrentOwnershipRefresh();
        provider.finishCurrentOwnershipRefresh();
        if (VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            throw new AssertionError("A stale render index without current boundary ownership must not produce no-gap");
        }
        if (provider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("A stale render index without current boundary ownership must not draw");
        }
    }

    private static String readSource(Path sourcePath) {
        try {
            return Files.readString(sourcePath);
        } catch (Exception exception) {
            throw new AssertionError("Unable to read source " + sourcePath.toAbsolutePath(), exception);
        }
    }
}
