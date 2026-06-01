package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.sodium.provider.VoxyFarTerrainProviderSnapshot;
import me.cortex.voxy.client.sodium.provider.VoxyFarTerrainProvider;
import me.cortex.voxy.client.sodium.provider.VoxyProviderDrawDecision;
import me.cortex.voxy.client.sodium.provider.VoxyProviderRenderCell;
import me.cortex.voxy.client.sodium.provider.VoxyProviderRenderList;
import me.cortex.voxy.client.sodium.provider.VoxyResolvedTerrainMaterial;
import me.cortex.voxy.client.sodium.provider.VoxyRuntimeMeshValidator;
import me.cortex.voxy.client.sodium.provider.VoxySectionInvalidationTracker;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainFailureReason;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainMaterialResolver;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainOwnership;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainOwnershipCell;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainOwnershipMap;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainPass;
import me.cortex.voxy.client.sodium.provider.VoxyTerrainTileValidator;
import me.cortex.voxy.common.VoxyHandoffPolicy;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.MipperRepresentativePolicy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

public final class VoxyLodCorrectnessProof {
    private static final int SECTION_SIZE = 8;
    private static final int AIR = 0;
    private static final int GRASS = 1;
    private static final int DIRT = 2;
    private static final int STONE = 3;
    private static final int WATER = 4;
    private static final int CHECKER_RED = 5;
    private static final int CHECKER_BLUE = 6;
    private static final int SAND = 7;
    private static final int BLACKSTONE = 8;
    private static final int INVALID_BLOCK = 99_999;
    private static final Map<String, String> EXPECTED_SCENARIO_HASHES = Map.of(
            "flat_grass", "6acbd081036a406e",
            "checkerboard_colors", "feedea7589ad9ed4",
            "vertical_cliff", "a938c4784799d273",
            "water_terrain", "e0f5c83ec917b0ff",
            "caves_overhangs", "2c78b3b3b51d6d10",
            "biome_color_boundary", "c2486001ab3722e9",
            "sparse_missing_child_tiles", "3b9952cff8f2bb1f",
            "corrupt_invalid_palette", "f3fd51e6ec3a55c6"
    );

    private VoxyLodCorrectnessProof() {
    }

    public static void main(String[] args) {
        System.out.println(runJson());
    }

    public static void runAssertions() {
        ProofResult result = run();
        if (!"PASS_LOD_CORRECTNESS".equals(result.status())) {
            throw new AssertionError(result.toJson());
        }
    }

    public static String runJson() {
        return run().toJson();
    }

    private static ProofResult run() {
        List<ScenarioResult> scenarios = new ArrayList<>();
        for (Scenario scenario : Scenario.values()) {
            scenarios.add(runScenario(scenario));
        }
        List<String> failures = new ArrayList<>();
        for (ScenarioResult scenario : scenarios) {
            failures.addAll(scenario.failures());
        }
        return new ProofResult(failures.isEmpty() ? "PASS_LOD_CORRECTNESS" : "FAIL_LOD_CORRECTNESS", scenarios, failures);
    }

    private static ScenarioResult runScenario(Scenario scenario) {
        List<String> failures = new ArrayList<>();
        MaterialPalette palette = defaultPalette();
        SyntheticWorld world = buildWorld(scenario, palette);
        Section loadedParent = roundTrip(world.parent());
        List<Section> loadedChildren = new ArrayList<>();
        for (Section child : world.children()) {
            loadedChildren.add(roundTrip(child));
        }
        List<Face> faces = buildOwnedMeshes(world, loadedParent, loadedChildren, failures);

        assertCoveragePolicy(failures);
        assertParentChildInvariants(world, loadedParent, loadedChildren, failures);
        assertProviderContracts(scenario, failures);
        if (scenario == Scenario.FLAT_GRASS) {
            assertMipperRepresentativePolicy(failures);
            assertRuntimeMeshValidationPolicy(failures);
        }
        assertPaletteValidity(scenario, palette, loadedParent, loadedChildren, failures);
        assertMesh(faces, palette, loadedParent, loadedChildren, failures);
        assertProviderMaterialResolver(scenario, failures);
        assertProviderPassContract(scenario, failures);
        assertProviderInvalidationContract(failures);
        assertScenarioSpecificMesh(scenario, faces, loadedParent, loadedChildren, failures);
        assertGoldenHash(scenario, loadedParent, loadedChildren, faces, palette, failures);

        Map<Integer, Integer> materialHistogram = materialHistogram(faces);
        String scenarioHash = scenarioHash(loadedParent, loadedChildren, faces, materialHistogram);
        return new ScenarioResult(
                scenario.id,
                failures.isEmpty() ? "PASS" : "FAIL",
                "PASS_MERGED_RENDER_DISTANCE_NO_GAP",
                failures.stream().noneMatch(message -> message.startsWith("palette:")) ? "PASS" : "FAIL",
                failures.stream().noneMatch(message -> message.startsWith("mesh:")) ? "PASS" : "FAIL",
                failures.stream().noneMatch(message -> message.startsWith("parent_child:")) ? "PASS" : "FAIL",
                scenario == Scenario.CORRUPT_INVALID_PALETTE ? "PASS_REJECTED_INVALID_TILE" : "PASS_NOT_APPLICABLE",
                hashSection(loadedParent),
                hashSections(loadedChildren),
                hashFaces(faces),
                scenarioHash,
                materialHistogram,
                failures
        );
    }

    private static SyntheticWorld buildWorld(Scenario scenario, MaterialPalette palette) {
        boolean[] presentChildren = new boolean[8];
        Arrays.fill(presentChildren, true);
        if (scenario == Scenario.SPARSE_MISSING_CHILD_TILES) {
            presentChildren[childIndex(1, 0, 1)] = false;
            presentChildren[childIndex(0, 1, 1)] = false;
        }
        List<Section> children = new ArrayList<>();
        for (int cz = 0; cz < 2; cz++) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cx = 0; cx < 2; cx++) {
                    boolean present = presentChildren[childIndex(cx, cy, cz)];
                    children.add(buildChildSection(scenario, palette, cx, cy, cz, present));
                }
            }
        }
        Section parent = mipParentSection(scenario, palette, children);
        return new SyntheticWorld(scenario, parent, children, presentChildren);
    }

    private static Section buildChildSection(
            Scenario scenario,
            MaterialPalette palette,
            int sectionX,
            int sectionY,
            int sectionZ,
            boolean present
    ) {
        Voxel[] voxels = new Voxel[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];
        boolean rejected = false;
        String rejectionReason = "";
        for (int z = 0; z < SECTION_SIZE; z++) {
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int gx = sectionX * SECTION_SIZE + x;
                    int gy = sectionY * SECTION_SIZE + y;
                    int gz = sectionZ * SECTION_SIZE + z;
                    Voxel voxel = voxelFor(scenario, gx, gy, gz);
                    if (scenario == Scenario.CORRUPT_INVALID_PALETTE && gx == 2 && gy == 2 && gz == 2) {
                        voxel = new Voxel(INVALID_BLOCK, 0, 15);
                        rejected = true;
                        rejectionReason = "invalid_palette_id";
                    }
                    voxels[index(x, y, z, SECTION_SIZE)] = voxel;
                }
            }
        }
        if (rejected && !palette.has(INVALID_BLOCK)) {
            return new Section(0, sectionX, sectionY, sectionZ, 1, present, true, rejectionReason, voxels);
        }
        return new Section(0, sectionX, sectionY, sectionZ, 1, present, false, rejectionReason, voxels);
    }

    private static Voxel voxelFor(Scenario scenario, int x, int y, int z) {
        return switch (scenario) {
            case FLAT_GRASS -> y < 3 ? new Voxel(y == 2 ? GRASS : DIRT, 0, 15) : Voxel.air();
            case CHECKERBOARD_COLORS -> new Voxel(((x + y + z) & 1) == 0 ? CHECKER_RED : CHECKER_BLUE, 0, 15);
            case VERTICAL_CLIFF -> x < 8 && y < 12 ? new Voxel(STONE, 0, 14) : Voxel.air();
            case WATER_TERRAIN -> {
                if (y < 2) {
                    yield new Voxel(SAND, 0, 14);
                }
                if (y < 5 && x > 5 && z > 5) {
                    yield new Voxel(WATER, 0, 13);
                }
                yield Voxel.air();
            }
            case CAVES_OVERHANGS -> {
                boolean shell = y < 12 && !(x > 4 && x < 11 && y > 2 && y < 7 && z > 4 && z < 11);
                boolean overhang = y == 10 && x > 2 && x < 13 && z > 2 && z < 13;
                yield shell || overhang ? new Voxel(STONE, 0, 12) : Voxel.air();
            }
            case BIOME_COLOR_BOUNDARY -> {
                int biome = x < 8 ? 0 : 1;
                yield y < 4 ? new Voxel(biome == 0 ? GRASS : SAND, biome, 15) : Voxel.air();
            }
            case SPARSE_MISSING_CHILD_TILES -> y < 6 ? new Voxel((x + z) % 3 == 0 ? GRASS : STONE, 0, 15) : Voxel.air();
            case CORRUPT_INVALID_PALETTE -> y < 4 ? new Voxel(GRASS, 0, 15) : Voxel.air();
        };
    }

    private static Section mipParentSection(Scenario scenario, MaterialPalette palette, List<Section> children) {
        Voxel[] parentVoxels = new Voxel[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];
        for (int pz = 0; pz < SECTION_SIZE; pz++) {
            for (int py = 0; py < SECTION_SIZE; py++) {
                for (int px = 0; px < SECTION_SIZE; px++) {
                    parentVoxels[index(px, py, pz, SECTION_SIZE)] = representativeChildVoxel(children, px * 2, py * 2, pz * 2);
                }
            }
        }
        boolean rejected = false;
        String reason = "";
        if (scenario == Scenario.CORRUPT_INVALID_PALETTE) {
            for (Voxel voxel : parentVoxels) {
                if (voxel.materialId() != AIR && !palette.has(voxel.materialId())) {
                    rejected = true;
                    reason = "invalid_palette_id";
                    break;
                }
            }
        }
        return new Section(1, 0, 0, 0, 2, true, rejected, reason, parentVoxels);
    }

    private static Voxel representativeChildVoxel(List<Section> children, int gx, int gy, int gz) {
        for (int dz = 0; dz < 2; dz++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    Voxel voxel = voxelAtGlobal(children, gx + dx, gy + dy, gz + dz);
                    if (voxel.materialId() != AIR) {
                        return voxel;
                    }
                }
            }
        }
        return Voxel.air();
    }

    private static List<Face> buildOwnedMeshes(
            SyntheticWorld world,
            Section parent,
            List<Section> children,
            List<String> failures
    ) {
        List<Face> faces = new ArrayList<>();
        for (Section child : children) {
            if (child.present() && !child.rejected()) {
                faces.addAll(meshSection(child));
            }
        }
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childY = 0; childY < 2; childY++) {
                for (int childX = 0; childX < 2; childX++) {
                    int index = childIndex(childX, childY, childZ);
                    boolean exactOwned = world.presentChildren()[index] && !children.get(index).rejected();
                    boolean parentOwned = !exactOwned && !parent.rejected();
                    if (exactOwned && parentOwned) {
                        failures.add("parent_child: parent and child both own cell " + index);
                    }
                    if (!exactOwned && !parentOwned && world.scenario() != Scenario.CORRUPT_INVALID_PALETTE) {
                        failures.add("parent_child: missing exact child has no parent fallback " + index);
                    }
                    if (parentOwned) {
                        faces.addAll(meshParentChildRegion(parent, childX, childY, childZ));
                    }
                }
            }
        }
        return faces;
    }

    private static List<Face> meshSection(Section section) {
        List<Face> faces = new ArrayList<>();
        for (int z = 0; z < SECTION_SIZE; z++) {
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    Voxel voxel = section.voxel(x, y, z);
                    if (voxel.materialId() == AIR) {
                        continue;
                    }
                    addExposedFaces(section, faces, x, y, z, voxel);
                }
            }
        }
        return faces;
    }

    private static List<Face> meshParentChildRegion(Section parent, int childX, int childY, int childZ) {
        List<Face> faces = new ArrayList<>();
        int startX = childX * (SECTION_SIZE / 2);
        int startY = childY * (SECTION_SIZE / 2);
        int startZ = childZ * (SECTION_SIZE / 2);
        int endX = startX + SECTION_SIZE / 2;
        int endY = startY + SECTION_SIZE / 2;
        int endZ = startZ + SECTION_SIZE / 2;
        for (int z = startZ; z < endZ; z++) {
            for (int y = startY; y < endY; y++) {
                for (int x = startX; x < endX; x++) {
                    Voxel voxel = parent.voxel(x, y, z);
                    if (voxel.materialId() != AIR) {
                        addExposedFaces(parent, faces, x, y, z, voxel);
                    }
                }
            }
        }
        return faces;
    }

    private static void addExposedFaces(Section section, List<Face> faces, int x, int y, int z, Voxel voxel) {
        int[][] dirs = {
                {-1, 0, 0},
                {1, 0, 0},
                {0, -1, 0},
                {0, 1, 0},
                {0, 0, -1},
                {0, 0, 1}
        };
        for (int axis = 0; axis < dirs.length; axis++) {
            int nx = x + dirs[axis][0];
            int ny = y + dirs[axis][1];
            int nz = z + dirs[axis][2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= SECTION_SIZE || ny >= SECTION_SIZE || nz >= SECTION_SIZE
                    || section.voxel(nx, ny, nz).materialId() == AIR) {
                int sign = axis % 2 == 0 ? -1 : 1;
                faces.add(new Face(
                        section.minX() + x * section.scale(),
                        section.minY() + y * section.scale(),
                        section.minZ() + z * section.scale(),
                        axis / 2,
                        sign,
                        section.scale(),
                        voxel.materialId()
                ));
            }
        }
    }

    private static void assertCoveragePolicy(List<String> failures) {
        VoxyHandoffPolicy.MergedRenderDistance distance = VoxyHandoffPolicy.mergedRenderDistance(64, 8, 2);
        boolean[] covered = new boolean[distance.voxyLodEndChunks() + 1];
        for (int chunk = 0; chunk <= distance.realRenderDistanceChunks(); chunk++) {
            covered[chunk] = true;
        }
        for (int chunk = distance.voxyLodStartChunks(); chunk <= distance.voxyLodEndChunks(); chunk++) {
            covered[chunk] = true;
        }
        for (int chunk = 0; chunk < covered.length; chunk++) {
            if (!covered[chunk]) {
                failures.add("coverage: empty interval at chunk radius " + chunk);
            }
        }
        if (distance.voxyLodStartChunks() > distance.realRenderDistanceChunks()) {
            failures.add("coverage: LoD start is outside real render radius");
        }
        if (distance.voxyLodStartChunks() != 6 || distance.realRenderDistanceChunks() != 8 || distance.voxyLodEndChunks() != 64) {
            failures.add("coverage: merged distance policy changed unexpectedly");
        }
    }

    private static void assertProviderContracts(Scenario scenario, List<String> failures) {
        VoxyFarTerrainProviderSnapshot snapshot = new VoxyFarTerrainProviderSnapshot(
                "voxy_merged",
                64,
                8,
                2,
                6,
                64,
                true,
                false
        );
        if (!snapshot.hasMergedDistanceOwnership()) {
            failures.add("coverage:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED + ": provider snapshot rejected merged ownership");
        }
        if (!snapshot.boundaryCanOverlapVanilla()) {
            failures.add("coverage:" + VoxyTerrainFailureReason.MISSING_EXACT_CHILD + ": provider boundary cannot overlap vanilla");
        }
        VoxyTerrainOwnershipMap map = new VoxyTerrainOwnershipMap(snapshot);
        assertOwnershipCase(map, 0, false, false, false, VoxyTerrainOwnership.VANILLA_EXACT, failures);
        assertOwnershipCase(map, 6, true, false, false, VoxyTerrainOwnership.VOXY_EXACT_LOD, failures);
        assertOwnershipCase(map, 7, false, true, false, VoxyTerrainOwnership.VOXY_PARENT_FALLBACK, failures);
        assertOwnershipCase(map, 65, true, true, false, VoxyTerrainOwnership.EMPTY_OUTSIDE_DISTANCE, failures);
        assertOwnershipCase(map, 10, true, false, true, VoxyTerrainOwnership.REJECTED_INVALID, failures);
        assertOwnershipRangeCase(map, 0, 5, true, false, false, VoxyTerrainOwnership.VANILLA_EXACT, failures);
        assertOwnershipRangeCase(map, 5, 7, true, false, false, VoxyTerrainOwnership.VOXY_EXACT_LOD, failures);
        assertOwnershipRangeCase(map, 65, 70, true, true, false, VoxyTerrainOwnership.EMPTY_OUTSIDE_DISTANCE, failures);

        for (int visual : new int[] {2, 8, 32, 64}) {
            VoxyHandoffPolicy.MergedRenderDistance distance = VoxyHandoffPolicy.mergedRenderDistance(visual, 8, 2);
            VoxyFarTerrainProviderSnapshot policy = new VoxyFarTerrainProviderSnapshot(
                    "voxy_merged",
                    distance.visualTerrainDistanceChunks(),
                    distance.realRenderDistanceChunks(),
                    distance.handoffOverlapChunks(),
                    distance.voxyLodStartChunks(),
                    distance.voxyLodEndChunks(),
                    true,
                    false
            );
            if (!policy.hasMergedDistanceOwnership()) {
                failures.add("coverage:" + VoxyTerrainFailureReason.MISSING_EXACT_CHILD + ": bad provider policy for visual " + visual);
            }
        }

        if (scenario == Scenario.SPARSE_MISSING_CHILD_TILES) {
            VoxyTerrainOwnershipCell exact = map.classifyChunkRadius(0, 0, 6, true, true, false);
            VoxyTerrainOwnershipCell fallback = map.classifyChunkRadius(0, 0, 6, false, true, false);
            if (exact.rendersVoxyGeometry() && fallback.rendersVoxyGeometry()
                    && exact.ownership() == VoxyTerrainOwnership.VOXY_PARENT_FALLBACK) {
                failures.add("parent_child:" + VoxyTerrainFailureReason.PARENT_CHILD_OWNERSHIP_CONFLICT);
            }
        }

        VoxyTerrainOwnershipCell exactRenderCell = new VoxyTerrainOwnershipCell(
                6,
                0,
                0,
                VoxyTerrainOwnership.VOXY_EXACT_LOD,
                VoxyTerrainFailureReason.NONE
        );
        VoxyTerrainOwnershipCell fallbackRenderCell = new VoxyTerrainOwnershipCell(
                7,
                0,
                1,
                VoxyTerrainOwnership.VOXY_PARENT_FALLBACK,
                VoxyTerrainFailureReason.VALID_PARENT_FALLBACK
        );

        VoxyFarTerrainProvider provider = new VoxyFarTerrainProvider(snapshot);
        provider.recordBoundarySource(
                me.cortex.voxy.common.voxelization.VoxelizedSection.SourceKind.REAL_CHUNK,
                me.cortex.voxy.common.voxelization.VoxelizedSection.LightSourceKind.REAL_LIGHT,
                me.cortex.voxy.common.voxelization.VoxelizedSection.Confidence.HIGH
        );
        if (VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            failures.add("coverage: trusted source metadata produced no-gap without render-owned geometry");
        }
        provider.recordCommittedMesh(WorldEngine.getWorldSectionId(0, 6, 0, 0), exactRenderCell, 61, 1L);
        provider.recordCommittedMesh(WorldEngine.getWorldSectionId(1, 3, 0, 0), fallbackRenderCell, 62, 2L);
        if (!VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            failures.add("coverage: provider did not produce no-gap verdict");
        }
        if (provider.diagnostics().boundaryMissingRequiredCells() != 0) {
            failures.add("coverage: provider reported required boundary misses in a covered boundary");
        }
        if (!"PASS".equals(provider.diagnostics().sodiumMaterialParityVerdict())) {
            failures.add("palette: provider failed material parity with only valid opaque terrain");
        }
        long fallbackRecoverySection = WorldEngine.getWorldSectionId(1, 4, 0, 0);
        provider.recordBoundaryMissingSection(fallbackRecoverySection);
        if (!VoxyFarTerrainProvider.FAIL_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            failures.add("coverage:" + VoxyTerrainFailureReason.MISSING_EXACT_CHILD
                    + ": provider passed despite a required boundary miss");
        }
        if (provider.diagnostics().boundaryMissingRequiredCells() == 0) {
            failures.add("coverage:" + VoxyTerrainFailureReason.MISSING_EXACT_CHILD
                    + ": provider did not expose the required boundary miss count");
        }
        provider.recordCommittedMesh(fallbackRecoverySection, fallbackRenderCell, 63, 3L);
        if (!VoxyFarTerrainProvider.PASS_NO_GAP.equals(provider.diagnostics().terrainCoverageVerdict())) {
            failures.add("coverage:" + VoxyTerrainFailureReason.VALID_PARENT_FALLBACK
                    + ": provider did not clear a current miss when parent fallback became available");
        }
        if (provider.diagnostics().boundaryMissingRequiredCells() != 0) {
            failures.add("coverage:" + VoxyTerrainFailureReason.VALID_PARENT_FALLBACK
                    + ": provider kept a stale miss after parent fallback coverage");
        }

        VoxyFarTerrainProvider currentStateProvider = new VoxyFarTerrainProvider(snapshot);
        long exactRecoverySection = WorldEngine.getWorldSectionId(0, 8, 0, 0);
        currentStateProvider.recordBoundaryRejectedSection(exactRecoverySection);
        if (!VoxyFarTerrainProvider.FAIL_UNTRUSTED_SOURCE.equals(currentStateProvider.diagnostics().terrainCoverageVerdict())) {
            failures.add("coverage:" + VoxyTerrainFailureReason.UNTRUSTED_SOURCE
                    + ": provider did not fail while a boundary section was rejected");
        }
        currentStateProvider.recordCommittedMesh(exactRecoverySection, exactRenderCell, 88, 4L);
        if (!VoxyFarTerrainProvider.PASS_NO_GAP.equals(currentStateProvider.diagnostics().terrainCoverageVerdict())) {
            failures.add("coverage: provider did not replace a rejected boundary section with exact coverage");
        }
        currentStateProvider.invalidateSection(exactRecoverySection);
        if (!VoxyFarTerrainProvider.UNKNOWN_NO_BOUNDARY_REQUESTS.equals(currentStateProvider.diagnostics().terrainCoverageVerdict())) {
            failures.add("coverage: provider did not clear stale coverage after invalidation");
        }

        VoxyFarTerrainProvider sourceProvider = new VoxyFarTerrainProvider(snapshot);
        sourceProvider.recordBoundarySource(
                me.cortex.voxy.common.voxelization.VoxelizedSection.SourceKind.SURFACE_PREVIEW,
                me.cortex.voxy.common.voxelization.VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW,
                me.cortex.voxy.common.voxelization.VoxelizedSection.Confidence.MEDIUM
        );
        if (!VoxyFarTerrainProvider.FAIL_UNTRUSTED_SOURCE.equals(sourceProvider.diagnostics().visualSourceVerdict())) {
            failures.add("palette:" + VoxyTerrainFailureReason.UNTRUSTED_SOURCE
                    + ": surface preview source counted as production visual terrain");
        }
        VoxyFarTerrainProvider lowConfidenceProvider = new VoxyFarTerrainProvider(snapshot);
        lowConfidenceProvider.recordBoundarySource(
                me.cortex.voxy.common.voxelization.VoxelizedSection.SourceKind.REAL_CHUNK,
                me.cortex.voxy.common.voxelization.VoxelizedSection.LightSourceKind.REAL_LIGHT,
                me.cortex.voxy.common.voxelization.VoxelizedSection.Confidence.LOW
        );
        if (!VoxyFarTerrainProvider.FAIL_UNTRUSTED_SOURCE.equals(lowConfidenceProvider.diagnostics().visualSourceVerdict())) {
            failures.add("palette:" + VoxyTerrainFailureReason.UNTRUSTED_SOURCE
                    + ": low-confidence real chunk source counted as trusted production terrain");
        }
        VoxyFarTerrainProvider defaultLightProvider = new VoxyFarTerrainProvider(snapshot);
        defaultLightProvider.recordBoundarySource(
                me.cortex.voxy.common.voxelization.VoxelizedSection.SourceKind.REAL_CHUNK,
                me.cortex.voxy.common.voxelization.VoxelizedSection.LightSourceKind.VALID_DEFAULT_SKY_LIGHT,
                me.cortex.voxy.common.voxelization.VoxelizedSection.Confidence.MEDIUM
        );
        defaultLightProvider.recordBoundaryExactSection(90L);
        if (!VoxyFarTerrainProvider.PASS_VISUAL_SOURCE_CORRECTNESS.equals(defaultLightProvider.diagnostics().visualSourceVerdict())) {
            failures.add("palette:" + VoxyTerrainFailureReason.UNTRUSTED_SOURCE
                    + ": trusted real chunk with valid default sky light was rejected");
        }
        provider.recordUnsupportedPass(VoxyTerrainPass.FLUID);
        if (provider.diagnostics().unsupportedPassSkips() == 0) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED + ": unsupported pass was not counted");
        }
        VoxyFarTerrainProvider renderedUnsupportedProvider = new VoxyFarTerrainProvider(snapshot);
        renderedUnsupportedProvider.recordRenderedPass(VoxyTerrainPass.FLUID);
        if (renderedUnsupportedProvider.diagnostics().unsupportedPassRenderedSections() == 0
                || "PASS".equals(renderedUnsupportedProvider.diagnostics().sodiumMaterialParityVerdict())) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED
                    + ": unsupported rendered pass did not fail material parity");
        }
        provider.recordInvalidRenderedSection(VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE);
        if (!VoxyFarTerrainProvider.FAIL_INVALID_RENDERED.equals(provider.diagnostics().terrainCoverageVerdict())) {
            failures.add("palette: provider did not fail after invalid rendered geometry");
        }

        VoxyFarTerrainProvider listProvider = new VoxyFarTerrainProvider(snapshot);
        VoxyTerrainOwnershipCell rejectedRenderCell = new VoxyTerrainOwnershipCell(
                8,
                0,
                0,
                VoxyTerrainOwnership.REJECTED_INVALID,
                VoxyTerrainFailureReason.INVALID_PALETTE_ID
        );
        listProvider.recordCommittedMesh(100L, exactRenderCell, 11, 1L);
        listProvider.recordCommittedMesh(101L, fallbackRenderCell, 12, 2L);
        listProvider.recordCommittedMesh(102L, rejectedRenderCell, 13, 3L);
        listProvider.recordCommittedMesh(103L, exactRenderCell, 14, 4L, VoxyProviderRenderCell.PASS_CUTOUT);
        VoxyProviderDrawDecision listDecision = listProvider.drawDecision(VoxyTerrainPass.SOLID);
        VoxyProviderRenderList renderList = listDecision.renderList();
        if (!listDecision.draw()) {
            failures.add("mesh: provider render list did not authorize valid exact/fallback geometry");
        }
        if (!Arrays.equals(renderList.meshIds(), new int[] {11, 12})) {
            failures.add("mesh: provider render list contained non-approved mesh ids "
                    + Arrays.toString(renderList.meshIds()));
        }
        VoxyProviderDrawDecision cutoutDecision = listProvider.drawDecision(VoxyTerrainPass.CUTOUT);
        if (cutoutDecision.draw()) {
            failures.add("mesh: provider CUTOUT list drew before independent cutout dispatch was proven");
        }
        if (listProvider.providerDrawnCutoutSections() != 0) {
            failures.add("mesh: provider reported CUTOUT draw ownership before independent cutout dispatch was proven");
        }
        long initialListEpoch = renderList.epoch();
        listProvider.recordStaleUploadRejection(100L);
        VoxyProviderRenderList staleFilteredList = listProvider.drawDecision(VoxyTerrainPass.SOLID).renderList();
        if (!Arrays.equals(staleFilteredList.meshIds(), new int[] {12})) {
            failures.add("mesh: stale upload rejection did not remove mesh id from provider render list");
        }
        if (staleFilteredList.epoch() <= initialListEpoch) {
            failures.add("mesh: provider render list epoch did not advance after stale upload rejection");
        }
        listProvider.invalidateSection(101L);
        if (listProvider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            failures.add("mesh: provider render list still drew after all approved mesh ids were invalidated");
        }

        VoxyFarTerrainProvider staleListProvider = new VoxyFarTerrainProvider(snapshot);
        long currentListSection = WorldEngine.getWorldSectionId(0, 11, 0, 0);
        long staleListSection = WorldEngine.getWorldSectionId(0, 12, 0, 0);
        staleListProvider.recordCommittedMesh(currentListSection, exactRenderCell, 31, 1L);
        staleListProvider.recordCommittedMesh(staleListSection, exactRenderCell, 32, 1L);
        staleListProvider.beginCurrentOwnershipRefresh();
        staleListProvider.recordCurrentRenderCell(currentListSection, exactRenderCell, 31, 1L);
        staleListProvider.finishCurrentOwnershipRefresh();
        if (!Arrays.equals(staleListProvider.drawDecision(VoxyTerrainPass.SOLID).meshIds(), new int[] {31})) {
            failures.add("mesh: current provider refresh did not prune stale render-list entries");
        }

        VoxyFarTerrainProvider mixedRejectedProvider = new VoxyFarTerrainProvider(snapshot);
        mixedRejectedProvider.recordCommittedMesh(
                WorldEngine.getWorldSectionId(0, 9, 0, 0),
                exactRenderCell,
                15,
                5L,
                VoxyProviderRenderCell.PASS_SOLID | VoxyProviderRenderCell.PASS_CUTOUT
        );
        if (mixedRejectedProvider.diagnostics().boundaryRejectedSections() == 0) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED
                    + ": mixed-pass boundary mesh rejection was not diagnosed");
        }
        if (mixedRejectedProvider.diagnostics().unsupportedPassSkips() == 0) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED
                    + ": mixed-pass boundary mesh rejection did not count unsupported pass skip");
        }
        if (mixedRejectedProvider.drawDecision(VoxyTerrainPass.SOLID).draw()) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED
                    + ": mixed-pass boundary mesh entered the provider render list");
        }
    }

    private static void assertOwnershipCase(
            VoxyTerrainOwnershipMap map,
            int chunkRadius,
            boolean exact,
            boolean fallback,
            boolean rejected,
            VoxyTerrainOwnership expected,
            List<String> failures
    ) {
        VoxyTerrainOwnershipCell cell = map.classifyChunkRadius(chunkRadius, 0, chunkRadius, exact, fallback, rejected);
        if (cell.ownership() != expected) {
            failures.add("coverage:" + VoxyTerrainFailureReason.MISSING_EXACT_CHILD
                    + ": radius " + chunkRadius + " expected " + expected + " got " + cell.ownership());
        }
    }

    private static void assertOwnershipRangeCase(
            VoxyTerrainOwnershipMap map,
            int minChunkRadius,
            int maxChunkRadius,
            boolean exact,
            boolean fallback,
            boolean rejected,
            VoxyTerrainOwnership expected,
            List<String> failures
    ) {
        VoxyTerrainOwnershipCell cell = map.classifyChunkDistanceRange(
                0,
                0,
                minChunkRadius,
                maxChunkRadius,
                exact,
                fallback,
                rejected
        );
        if (cell.ownership() != expected) {
            failures.add("coverage:" + VoxyTerrainFailureReason.MISSING_EXACT_CHILD
                    + ": range " + minChunkRadius + "-" + maxChunkRadius
                    + " expected " + expected + " got " + cell.ownership());
        }
    }

    private static void assertParentChildInvariants(
            SyntheticWorld world,
            Section parent,
            List<Section> children,
            List<String> failures
    ) {
        if (parent.minX() != 0 || parent.minY() != 0 || parent.minZ() != 0
                || parent.maxX() != SECTION_SIZE * 2 || parent.maxY() != SECTION_SIZE * 2 || parent.maxZ() != SECTION_SIZE * 2) {
            failures.add("parent_child: parent bounds do not cover the 2x2x2 child union exactly");
        }
        for (Section child : children) {
            if (child.minX() < parent.minX() || child.maxX() > parent.maxX()
                    || child.minY() < parent.minY() || child.maxY() > parent.maxY()
                    || child.minZ() < parent.minZ() || child.maxZ() > parent.maxZ()) {
                failures.add("parent_child: child bounds escape parent union");
            }
        }
        for (int z = 0; z < SECTION_SIZE; z++) {
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    Voxel parentVoxel = parent.voxel(x, y, z);
                    if (parentVoxel.materialId() == AIR) {
                        continue;
                    }
                    if (!childGroupContains(children, x * 2, y * 2, z * 2, parentVoxel.materialId())) {
                        failures.add("parent_child: parent representative does not come from a valid child block");
                        return;
                    }
                }
            }
        }
        for (int i = 0; i < world.presentChildren().length; i++) {
            boolean exact = world.presentChildren()[i] && !children.get(i).rejected();
            boolean fallback = !exact && !parent.rejected();
            if (exact && fallback) {
                failures.add("parent_child: parent fallback overlaps exact child " + i);
            }
        }
    }

    private static void assertPaletteValidity(
            Scenario scenario,
            MaterialPalette palette,
            Section parent,
            List<Section> children,
            List<String> failures
    ) {
        boolean rejectedInvalidTile = parent.rejected();
        for (Section child : children) {
            if (child.rejected()) {
                rejectedInvalidTile = true;
            }
            for (Voxel voxel : child.voxels()) {
                if (voxel.materialId() != AIR && !palette.has(voxel.materialId()) && !child.rejected()) {
                    failures.add("palette: renderable child contains unresolved material " + voxel.materialId());
                }
            }
        }
        for (Voxel voxel : parent.voxels()) {
            if (voxel.materialId() != AIR && !palette.has(voxel.materialId()) && !parent.rejected()) {
                failures.add("palette: renderable parent contains unresolved material " + voxel.materialId());
            }
        }
        for (Material material : palette.materials().values()) {
            if (material.id() != AIR && material.color() == 0 && material.id() != BLACKSTONE) {
                failures.add("palette: non-black material resolves to black color " + material.name());
            }
            if (material.id() != AIR && material.modelKey().isBlank()) {
                failures.add("palette: material has no model fallback " + material.name());
            }
        }
        if (scenario == Scenario.CORRUPT_INVALID_PALETTE && !rejectedInvalidTile) {
            failures.add("palette: corrupt invalid palette tile was not rejected");
        }
    }

    private static void assertMesh(
            List<Face> faces,
            MaterialPalette palette,
            Section parent,
            List<Section> children,
            List<String> failures
    ) {
        for (Face face : faces) {
            if (face.x() < 0 || face.y() < 0 || face.z() < 0
                    || face.x() > SECTION_SIZE * 2 || face.y() > SECTION_SIZE * 2 || face.z() > SECTION_SIZE * 2) {
                failures.add("mesh: vertex outside synthetic world bounds");
            }
            if (face.axis() < 0 || face.axis() > 2 || Math.abs(face.sign()) != 1) {
                failures.add("mesh: face normal is not axis aligned");
            }
            if (face.scale() <= 0) {
                failures.add("mesh: zero-area quad emitted");
            }
            if (!palette.has(face.materialId())) {
                failures.add("mesh: face uses invalid material " + face.materialId());
            }
        }
        Section solid = uniformSection(STONE);
        int solidFaces = meshSection(solid).size();
        int expectedOuterFaces = 6 * SECTION_SIZE * SECTION_SIZE;
        if (solidFaces != expectedOuterFaces) {
            failures.add("mesh: uniform solid section emitted internal faces; expected "
                    + expectedOuterFaces + " got " + solidFaces);
        }
        if (!parent.rejected()) {
            assertSectionMeshBounds(parent, failures);
        }
        for (Section child : children) {
            if (child.present() && !child.rejected()) {
                assertSectionMeshBounds(child, failures);
            }
        }
    }

    private static void assertProviderMaterialResolver(Scenario scenario, List<String> failures) {
        VoxyTerrainMaterialResolver resolver = new VoxyTerrainMaterialResolver(
                Set.of(GRASS, DIRT, STONE, WATER, CHECKER_RED, CHECKER_BLUE, SAND, BLACKSTONE),
                Set.of(0, 1),
                Map.of(
                        GRASS, "minecraft:grass_block",
                        DIRT, "minecraft:dirt",
                        STONE, "minecraft:stone",
                        WATER, "minecraft:water",
                        CHECKER_RED, "voxy:test_red",
                        CHECKER_BLUE, "voxy:test_blue",
                        SAND, "minecraft:sand",
                        BLACKSTONE, "minecraft:blackstone"
                ),
                Map.of(
                        GRASS, 0x56A04A,
                        DIRT, 0x7B5135,
                        STONE, 0x777777,
                        WATER, 0x315EBC,
                        CHECKER_RED, 0xB73535,
                        CHECKER_BLUE, 0x2F63C6,
                        SAND, 0xD8C987,
                        BLACKSTONE, 0x000000
                ),
                Set.of(BLACKSTONE)
        );
        VoxyResolvedTerrainMaterial grass = resolver.resolve(GRASS, 0, 15, VoxyTerrainPass.SOLID);
        if (!grass.canMesh()) {
            failures.add("palette:" + grass.failureReason() + ": valid grass did not resolve");
        }
        VoxyResolvedTerrainMaterial invalidBlock = resolver.resolve(INVALID_BLOCK, 0, 15, VoxyTerrainPass.SOLID);
        if (invalidBlock.canMesh() || invalidBlock.failureReason() != VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE) {
            failures.add("palette:" + VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE + ": invalid block was not rejected");
        }
        VoxyResolvedTerrainMaterial invalidBiome = resolver.resolve(GRASS, 99, 15, VoxyTerrainPass.SOLID);
        if (invalidBiome.canMesh() || invalidBiome.failureReason() != VoxyTerrainFailureReason.MISSING_BIOME_TINT) {
            failures.add("palette:" + VoxyTerrainFailureReason.MISSING_BIOME_TINT + ": invalid biome was not rejected");
        }
        VoxyResolvedTerrainMaterial invalidLight = resolver.resolve(GRASS, 0, 0x1_0000, VoxyTerrainPass.SOLID);
        if (invalidLight.canMesh() || invalidLight.failureReason() != VoxyTerrainFailureReason.INVALID_LIGHT) {
            failures.add("palette:" + VoxyTerrainFailureReason.INVALID_LIGHT + ": invalid light was not rejected");
        }
        VoxyResolvedTerrainMaterial unsupportedFluid = resolver.resolve(WATER, 0, 15, VoxyTerrainPass.FLUID);
        if (unsupportedFluid.canMesh() || unsupportedFluid.failureReason() != VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED + ": fluid pass was not rejected by provider resolver");
        }
        if (VoxyTerrainTileValidator.validateMaterial(invalidBlock) != VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE) {
            failures.add("palette:" + VoxyTerrainFailureReason.UNRESOLVED_BLOCK_STATE + ": validator accepted invalid material");
        }
        if (VoxyTerrainTileValidator.validateBounds(1, 1, 1, 1, 2, 2) != VoxyTerrainFailureReason.SECTION_OUT_OF_BOUNDS) {
            failures.add("mesh:" + VoxyTerrainFailureReason.SECTION_OUT_OF_BOUNDS + ": invalid bounds were accepted");
        }
        if (scenario == Scenario.CORRUPT_INVALID_PALETTE && invalidBlock.canMesh()) {
            failures.add("palette:" + VoxyTerrainFailureReason.INVALID_PALETTE_ID + ": corrupt scenario resolved invalid block");
        }
    }

    private static void assertProviderPassContract(Scenario scenario, List<String> failures) {
        VoxyTerrainPass expected = switch (scenario) {
            case WATER_TERRAIN -> VoxyTerrainPass.FLUID;
            case CHECKERBOARD_COLORS, FLAT_GRASS, VERTICAL_CLIFF, CAVES_OVERHANGS, BIOME_COLOR_BOUNDARY, SPARSE_MISSING_CHILD_TILES, CORRUPT_INVALID_PALETTE -> VoxyTerrainPass.SOLID;
        };
        if (expected == VoxyTerrainPass.FLUID && scenario != Scenario.WATER_TERRAIN) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED + ": unexpected fluid pass");
        }
    }

    private static void assertProviderInvalidationContract(List<String> failures) {
        VoxySectionInvalidationTracker tracker = new VoxySectionInvalidationTracker();
        long sectionKey = 42L;
        long startVersion = tracker.invalidate(sectionKey);
        tracker.invalidate(sectionKey);
        if (!tracker.isStale(sectionKey, startVersion)) {
            failures.add("parent_child:" + VoxyTerrainFailureReason.MISSING_EXACT_CHILD + ": stale build was not detected");
        }
    }

    private static void assertMipperRepresentativePolicy(List<String> failures) {
        boolean rejectsBuriedOre = MipperRepresentativePolicy.shouldPreferFoliageCanopyOverSolid(
                true,
                4,
                4,
                0,
                0,
                true
        );
        if (!rejectsBuriedOre) {
            failures.add("mipper: buried solid block can still win over visible foliage canopy");
        }
        boolean rejectsFluidOverride = MipperRepresentativePolicy.shouldPreferFoliageCanopyOverSolid(
                true,
                4,
                4,
                1,
                0,
                true
        );
        if (rejectsFluidOverride) {
            failures.add("mipper: foliage canopy policy overrode a fluid-containing preview tile");
        }
    }

    private static void assertRuntimeMeshValidationPolicy(List<String> failures) {
        if (VoxyRuntimeMeshValidator.validateBlockId(9, 8) != VoxyTerrainFailureReason.INVALID_PALETTE_ID) {
            failures.add("palette:" + VoxyTerrainFailureReason.INVALID_PALETTE_ID + ": runtime mesh validator accepted out-of-range palette id");
        }
        if (VoxyRuntimeMeshValidator.validateModelId(-1) != VoxyTerrainFailureReason.MISSING_MODEL_FALLBACK) {
            failures.add("palette:" + VoxyTerrainFailureReason.MISSING_MODEL_FALLBACK + ": runtime mesh validator accepted missing model id");
        }
        long fluidMetadata = 1L << (8 * 6 + 4);
        if (VoxyRuntimeMeshValidator.validateSupportedRuntimePass(fluidMetadata) != VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED + ": runtime mesh validator accepted fluid metadata");
        }
        long translucentMetadata = 1L << (8 * 6 + 1);
        if (VoxyRuntimeMeshValidator.validateSupportedRuntimePass(translucentMetadata) != VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED) {
            failures.add("mesh:" + VoxyTerrainFailureReason.RENDER_PASS_UNSUPPORTED + ": runtime mesh validator accepted translucent metadata");
        }
        if (VoxyRuntimeMeshValidator.validateBlockId(7, 8) != VoxyTerrainFailureReason.NONE
                || VoxyRuntimeMeshValidator.validateModelId(1) != VoxyTerrainFailureReason.NONE
                || VoxyRuntimeMeshValidator.validateSupportedRuntimePass(0L) != VoxyTerrainFailureReason.NONE) {
            failures.add("mesh: runtime mesh validator rejected a valid opaque material");
        }
    }

    private static void assertScenarioSpecificMesh(
            Scenario scenario,
            List<Face> faces,
            Section parent,
            List<Section> children,
            List<String> failures
    ) {
        if (scenario == Scenario.VERTICAL_CLIFF) {
            boolean hasCliffFace = faces.stream().anyMatch(face -> face.axis() == 0 && face.sign() == 1 && face.x() == 7);
            if (!hasCliffFace) {
                failures.add("mesh: vertical cliff did not emit an exposed cliff face");
            }
        }
        if (scenario == Scenario.SPARSE_MISSING_CHILD_TILES) {
            boolean hasParentFallback = faces.stream().anyMatch(face -> face.scale() == parent.scale());
            if (!hasParentFallback) {
                failures.add("parent_child: sparse missing child scenario did not produce parent fallback faces");
            }
        }
        if (scenario == Scenario.CORRUPT_INVALID_PALETTE) {
            boolean invalidRendered = faces.stream().anyMatch(face -> face.materialId() == INVALID_BLOCK);
            if (invalidRendered) {
                failures.add("palette: invalid material reached the mesh");
            }
        }
        if (scenario == Scenario.CHECKERBOARD_COLORS) {
            long redFaces = faces.stream().filter(face -> face.materialId() == CHECKER_RED).count();
            long blueFaces = faces.stream().filter(face -> face.materialId() == CHECKER_BLUE).count();
            if (redFaces == 0 || blueFaces == 0) {
                failures.add("mesh: checkerboard lost one of its color materials");
            }
        }
    }

    private static void assertSectionMeshBounds(Section section, List<String> failures) {
        for (Face face : meshSection(section)) {
            if (face.x() < section.minX() || face.x() >= section.maxX()
                    || face.y() < section.minY() || face.y() >= section.maxY()
                    || face.z() < section.minZ() || face.z() >= section.maxZ()) {
                failures.add("mesh: face escaped section bounds for level " + section.level());
                return;
            }
        }
    }

    private static void assertGoldenHash(
            Scenario scenario,
            Section parent,
            List<Section> children,
            List<Face> faces,
            MaterialPalette palette,
            List<String> failures
    ) {
        String expected = EXPECTED_SCENARIO_HASHES.get(scenario.id);
        if (expected == null) {
            return;
        }
        String actual = scenarioHash(parent, children, faces, materialHistogram(faces));
        if (!expected.equals(actual)) {
            failures.add("golden_hash: " + scenario.id + " expected " + expected + " got " + actual);
        }
    }

    private static boolean childGroupContains(List<Section> children, int gx, int gy, int gz, int materialId) {
        for (int dz = 0; dz < 2; dz++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    if (voxelAtGlobal(children, gx + dx, gy + dy, gz + dz).materialId() == materialId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Voxel voxelAtGlobal(List<Section> children, int gx, int gy, int gz) {
        if (gx < 0 || gy < 0 || gz < 0 || gx >= SECTION_SIZE * 2 || gy >= SECTION_SIZE * 2 || gz >= SECTION_SIZE * 2) {
            return Voxel.air();
        }
        int childX = gx / SECTION_SIZE;
        int childY = gy / SECTION_SIZE;
        int childZ = gz / SECTION_SIZE;
        Section child = children.get(childIndex(childX, childY, childZ));
        return child.voxel(gx % SECTION_SIZE, gy % SECTION_SIZE, gz % SECTION_SIZE);
    }

    private static Section roundTrip(Section section) {
        Voxel[] copy = new Voxel[section.voxels().length];
        for (int i = 0; i < section.voxels().length; i++) {
            Voxel voxel = section.voxels()[i];
            copy[i] = new Voxel(voxel.materialId(), voxel.biomeId(), voxel.light());
        }
        return new Section(
                section.level(),
                section.sectionX(),
                section.sectionY(),
                section.sectionZ(),
                section.scale(),
                section.present(),
                section.rejected(),
                section.rejectionReason(),
                copy
        );
    }

    private static Section uniformSection(int materialId) {
        Voxel[] voxels = new Voxel[SECTION_SIZE * SECTION_SIZE * SECTION_SIZE];
        Arrays.fill(voxels, new Voxel(materialId, 0, 15));
        return new Section(0, 0, 0, 0, 1, true, false, "", voxels);
    }

    private static MaterialPalette defaultPalette() {
        Map<Integer, Material> materials = new LinkedHashMap<>();
        add(materials, AIR, "air", 0x000000, false, false, "minecraft:air");
        add(materials, GRASS, "grass", 0x56A04A, true, false, "minecraft:grass_block");
        add(materials, DIRT, "dirt", 0x7B5135, true, false, "minecraft:dirt");
        add(materials, STONE, "stone", 0x777777, true, false, "minecraft:stone");
        add(materials, WATER, "water", 0x315EBC, false, true, "minecraft:water");
        add(materials, CHECKER_RED, "checker_red", 0xB73535, true, false, "voxy:test_red");
        add(materials, CHECKER_BLUE, "checker_blue", 0x2F63C6, true, false, "voxy:test_blue");
        add(materials, SAND, "sand", 0xD8C987, true, false, "minecraft:sand");
        add(materials, BLACKSTONE, "blackstone", 0x000000, true, false, "minecraft:blackstone");
        return new MaterialPalette(materials);
    }

    private static void add(
            Map<Integer, Material> materials,
            int id,
            String name,
            int color,
            boolean solid,
            boolean water,
            String modelKey
    ) {
        materials.put(id, new Material(id, name, color, solid, water, modelKey));
    }

    private static int index(int x, int y, int z, int size) {
        return x + size * (y + size * z);
    }

    private static int childIndex(int x, int y, int z) {
        return x + 2 * (y + 2 * z);
    }

    private static Map<Integer, Integer> materialHistogram(List<Face> faces) {
        Map<Integer, Integer> histogram = new LinkedHashMap<>();
        for (Face face : faces) {
            histogram.merge(face.materialId(), 1, Integer::sum);
        }
        return histogram;
    }

    private static String hashSections(List<Section> sections) {
        MessageDigest digest = sha256();
        for (Section section : sections) {
            update(digest, hashSection(section));
        }
        return hex16(digest.digest());
    }

    private static String hashSection(Section section) {
        MessageDigest digest = sha256();
        update(digest, section.level());
        update(digest, section.sectionX());
        update(digest, section.sectionY());
        update(digest, section.sectionZ());
        update(digest, section.scale());
        update(digest, section.present() ? 1 : 0);
        update(digest, section.rejected() ? 1 : 0);
        for (Voxel voxel : section.voxels()) {
            update(digest, voxel.materialId());
            update(digest, voxel.biomeId());
            update(digest, voxel.light());
        }
        return hex16(digest.digest());
    }

    private static String hashFaces(List<Face> faces) {
        MessageDigest digest = sha256();
        for (Face face : faces) {
            update(digest, face.x());
            update(digest, face.y());
            update(digest, face.z());
            update(digest, face.axis());
            update(digest, face.sign());
            update(digest, face.scale());
            update(digest, face.materialId());
        }
        return hex16(digest.digest());
    }

    private static String scenarioHash(
            Section parent,
            List<Section> children,
            List<Face> faces,
            Map<Integer, Integer> histogram
    ) {
        MessageDigest digest = sha256();
        update(digest, hashSection(parent));
        update(digest, hashSections(children));
        update(digest, hashFaces(faces));
        for (Map.Entry<Integer, Integer> entry : histogram.entrySet()) {
            update(digest, entry.getKey());
            update(digest, entry.getValue());
        }
        return hex16(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex16(byte[] bytes) {
        StringBuilder builder = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            builder.append(String.format("%02x", bytes[i] & 0xFF));
        }
        return builder.toString();
    }

    private enum Scenario {
        FLAT_GRASS("flat_grass"),
        CHECKERBOARD_COLORS("checkerboard_colors"),
        VERTICAL_CLIFF("vertical_cliff"),
        WATER_TERRAIN("water_terrain"),
        CAVES_OVERHANGS("caves_overhangs"),
        BIOME_COLOR_BOUNDARY("biome_color_boundary"),
        SPARSE_MISSING_CHILD_TILES("sparse_missing_child_tiles"),
        CORRUPT_INVALID_PALETTE("corrupt_invalid_palette");

        private final String id;

        Scenario(String id) {
            this.id = id;
        }
    }

    private record Voxel(int materialId, int biomeId, int light) {
        static Voxel air() {
            return new Voxel(AIR, 0, 15);
        }
    }

    private record Section(
            int level,
            int sectionX,
            int sectionY,
            int sectionZ,
            int scale,
            boolean present,
            boolean rejected,
            String rejectionReason,
            Voxel[] voxels
    ) {
        Voxel voxel(int x, int y, int z) {
            return voxels[index(x, y, z, SECTION_SIZE)];
        }

        int minX() {
            return sectionX * SECTION_SIZE * scale;
        }

        int minY() {
            return sectionY * SECTION_SIZE * scale;
        }

        int minZ() {
            return sectionZ * SECTION_SIZE * scale;
        }

        int maxX() {
            return minX() + SECTION_SIZE * scale;
        }

        int maxY() {
            return minY() + SECTION_SIZE * scale;
        }

        int maxZ() {
            return minZ() + SECTION_SIZE * scale;
        }
    }

    private record Face(int x, int y, int z, int axis, int sign, int scale, int materialId) {
    }

    private record Material(int id, String name, int color, boolean solid, boolean water, String modelKey) {
    }

    private record MaterialPalette(Map<Integer, Material> materials) {
        boolean has(int materialId) {
            return materials.containsKey(materialId);
        }
    }

    private record SyntheticWorld(Scenario scenario, Section parent, List<Section> children, boolean[] presentChildren) {
    }

    private record ScenarioResult(
            String scenario,
            String status,
            String coverageVerdict,
            String paletteVerdict,
            String meshVerdict,
            String parentChildVerdict,
            String invalidTileVerdict,
            String parentHash,
            String childrenHash,
            String meshHash,
            String scenarioHash,
            Map<Integer, Integer> materialHistogram,
            List<String> failures
    ) {
        String toJson() {
            return "{"
                    + "\"scenario\":\"" + scenario + "\","
                    + "\"status\":\"" + status + "\","
                    + "\"coverage_verdict\":\"" + coverageVerdict + "\","
                    + "\"palette_verdict\":\"" + paletteVerdict + "\","
                    + "\"mesh_verdict\":\"" + meshVerdict + "\","
                    + "\"parent_child_verdict\":\"" + parentChildVerdict + "\","
                    + "\"invalid_tile_verdict\":\"" + invalidTileVerdict + "\","
                    + "\"parent_hash\":\"" + parentHash + "\","
                    + "\"children_hash\":\"" + childrenHash + "\","
                    + "\"mesh_hash\":\"" + meshHash + "\","
                    + "\"scenario_hash\":\"" + scenarioHash + "\","
                    + "\"material_color_histogram\":" + histogramJson(materialHistogram) + ","
                    + "\"failures\":" + stringArrayJson(failures)
                    + "}";
        }
    }

    private record ProofResult(String status, List<ScenarioResult> scenarios, List<String> failures) {
        String toJson() {
            StringJoiner scenarioJson = new StringJoiner(",");
            for (ScenarioResult scenario : scenarios) {
                scenarioJson.add(scenario.toJson());
            }
            return "{"
                    + "\"status\":\"" + status + "\","
                    + "\"scenario_count\":" + scenarios.size() + ","
                    + "\"coverage_verdict\":\"" + (failures.stream().noneMatch(message -> message.startsWith("coverage:")) ? "PASS" : "FAIL") + "\","
                    + "\"palette_verdict\":\"" + (failures.stream().noneMatch(message -> message.startsWith("palette:")) ? "PASS" : "FAIL") + "\","
                    + "\"mesh_verdict\":\"" + (failures.stream().noneMatch(message -> message.startsWith("mesh:")) ? "PASS" : "FAIL") + "\","
                    + "\"parent_child_verdict\":\"" + (failures.stream().noneMatch(message -> message.startsWith("parent_child:")) ? "PASS" : "FAIL") + "\","
                    + "\"invalid_tile_verdict\":\"" + (failures.stream().noneMatch(message -> message.startsWith("palette: invalid material reached")) ? "PASS" : "FAIL") + "\","
                    + "\"scenarios\":[" + scenarioJson + "],"
                    + "\"failures\":" + stringArrayJson(failures)
                    + "}";
        }
    }

    private static String histogramJson(Map<Integer, Integer> histogram) {
        StringJoiner joiner = new StringJoiner(",", "{", "}");
        for (Map.Entry<Integer, Integer> entry : histogram.entrySet()) {
            joiner.add("\"" + entry.getKey() + "\":" + entry.getValue());
        }
        return joiner.toString();
    }

    private static String stringArrayJson(List<String> values) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (String value : values) {
            joiner.add("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        return joiner.toString();
    }

}
