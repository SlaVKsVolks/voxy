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
        assertSodiumCompatibleProviderBoundaryExists();
        assertSodiumCompatibleProviderIsRuntimeAuthority();
        assertProviderIsProductionRenderAuthority();
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
        if (!cutoutProvider.shouldRenderDuringSodiumPass(VoxyTerrainPass.CUTOUT)) {
            throw new AssertionError("Pure CUTOUT provider geometry must be advertised as Sodium-compatible after independent CUTOUT dispatch is proven");
        }
        if (cutoutProvider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            throw new AssertionError("CUTOUT provider geometry must not enter the SOLID production render list");
        }
        VoxyProviderDrawDecision cutoutDecision = cutoutProvider.drawDecision(VoxyTerrainPass.CUTOUT);
        if (!cutoutDecision.draw()) {
            throw new AssertionError("Pure CUTOUT provider geometry must draw through the independent CUTOUT render list");
        }
        if (cutoutDecision.meshIds().length != 1 || cutoutDecision.meshIds()[0] != 8) {
            throw new AssertionError("CUTOUT provider draw decision must expose only CUTOUT-owned mesh ids");
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
        if (!mixedSolidDecision.draw() || mixedSolidDecision.meshIds().length != 1 || mixedSolidDecision.meshIds()[0] != 9) {
            throw new AssertionError("Mixed SOLID/CUTOUT provider geometry must be present in the SOLID render list");
        }
        if (!mixedCutoutDecision.draw() || mixedCutoutDecision.meshIds().length != 1 || mixedCutoutDecision.meshIds()[0] != 9) {
            throw new AssertionError("Mixed SOLID/CUTOUT provider geometry must be present in the CUTOUT render list");
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

    private static String readSource(Path sourcePath) {
        try {
            return Files.readString(sourcePath);
        } catch (Exception exception) {
            throw new AssertionError("Unable to read source " + sourcePath.toAbsolutePath(), exception);
        }
    }
}
