package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorPalettedContainer;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorPalettedContainerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.WeakHashMap;

public class WorldConversionFactory {
    private static final Set<Class<?>> GENERIC_PALETTE_WARNED = ConcurrentHashMap.newKeySet();
    private static final int SURFACE_PREVIEW_SHELL_DEPTH_BLOCKS = Integer.getInteger("voxy.surfacePreviewShellDepthBlocks", 6);
    private static final int SURFACE_PREVIEW_SYNTHETIC_SHELL_DEPTH_BLOCKS = Integer.getInteger("voxy.surfacePreviewSyntheticShellDepthBlocks", 1);
    private static final boolean SURFACE_PREVIEW_USE_REQUESTED_DEPTH = Boolean.parseBoolean(System.getProperty("voxy.surfacePreviewUseRequestedDepth", "true"));
    private static final int SURFACE_PREVIEW_EXPOSED_SOLID_DEPTH_BLOCKS = Integer.getInteger("voxy.surfacePreviewExposedSolidDepthBlocks", 64);
    private static final int SURFACE_PREVIEW_SKY_CONNECTED_DEPTH_BLOCKS = Integer.getInteger("voxy.surfacePreviewSkyConnectedDepthBlocks", 128);
    private static final boolean SURFACE_PREVIEW_WATERTIGHT = Boolean.parseBoolean(
            System.getProperty("voxy.surfacePreviewWatertight", "true")
    );
    private static final boolean SURFACE_PREVIEW_BOUNDARY_SKIRTS = Boolean.parseBoolean(
            System.getProperty("voxy.surfacePreviewBoundarySkirts", "true")
    );

    public record SurfacePreviewMaskResult(
            int kept,
            int boundarySkirtKept,
            int sectionHadRetainedSurface,
            int sectionHadRetainedOceanFloor
    ) {
    }

    private static final class Cache {
        private final int[] biomeCache = new int[4*4*4];
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();
        private int[] paletteCache = new int[1024];
        private final long[] zoomCellCache = new long[5*5*5];
        private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }
        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }

    //TODO: create a mapping for world/mapper -> local mapping
    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    private static int mapBlockState(BlockState state, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper) {
        if (state == null) {
            return 0;
        }

        int blockId = blockCache.getOrDefault(state, -1);
        if (blockId == -1) {
            blockId = mapper.getIdForBlockState(state);
            blockCache.put(state, blockId);
        }
        return blockId;
    }

    private static int setupLocalPalette(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        int c = vp.getSize();
        if (c <= 0) {
            pc[0] = 0;
            return 1;
        }

        if (!(vp instanceof LinearPalette<BlockState>)
                && !(vp instanceof HashMapPalette<BlockState>)
                && !(vp instanceof SingleValuePalette<BlockState>)
                && GENERIC_PALETTE_WARNED.add(vp.getClass())) {
            Logger.info("Using compatibility Voxy palette reader for ", vp.getClass().getName());
        }

        boolean readFailureLogged = false;
        for (int i = 0; i < c; i++) {
            BlockState state = null;
            try {
                state = vp.valueFor(i);
            } catch (Exception e) {
                if (!readFailureLogged) {
                    Logger.warn("Failed to read palette value from ", vp.getClass().getName(), " (logging once)", e);
                    readFailureLogged = true;
                }
            }
            pc[i] = mapBlockState(state, blockCache, mapper);
        }
        return c;
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           PalettedContainerRO<Holder<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier) {
        return convert(section, stateMapper, blockContainer, biomeContainer, lightSupplier, false, 0);
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           PalettedContainerRO<Holder<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier,
                                           boolean shouldZoom,
                                           long zoomSeed) {
        //Cheat by creating a local pallet then read the data directly
        var cache = THREAD_LOCAL.get();
        var blockCache = cache.getLocalMapping(stateMapper);

        var biomes = cache.biomeCache;
        var data = section.section;
        var zoomCells = cache.zoomCellCache;

        var containerData = ((AccessorPalettedContainer<BlockState>) blockContainer).voxy$getData();
        var dataAccessor = (AccessorPalettedContainerData<BlockState>) (Object) containerData;
        var vp = dataAccessor.voxy$getPalette();
        var pc = cache.getPaletteCache(vp.getSize());
        GlobalPalette<BlockState> bps = null;

        int pcc = 0;
        if (vp instanceof GlobalPalette<BlockState> _bps) {
            bps = _bps;
            pcc = bps.getSize();
        } else {
            pcc = setupLocalPalette(vp, blockCache, stateMapper, pc);
            pcc = Math.max(0,pcc-1);
        }

        {
            int i = 0;
            int inital = -1;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int bid = stateMapper.getIdForBiome(biomeContainer.get(x, y, z));
                        biomes[i++] = bid;
                        if (inital==-1) inital = bid;
                        shouldZoom &= inital == bid;//Evil hacky trick, we only need to zoom if on a biome boarder
                    }
                }
            }

            if (shouldZoom) {
                computeZoomCells(biomes, zoomSeed, zoomCells);
            }
        }


        int nonZeroCnt = 0;
        var storage = dataAccessor.voxy$getStorage();
        if (storage instanceof SimpleBitStorage bStor) {
            var bDat = bStor.getRaw();
            int iterPerLong = (64 / bStor.getBits()) - 1;

            int MSK = (1 << bStor.getBits()) - 1;
            int eBits = bStor.getBits();

            long sample = 0;
            int c = 0;
            int dec = 0;
            for (int i = 0; i <= 0xFFF; i++) {
                if (dec-- == 0) {
                    sample = bDat[c++];
                    dec = iterPerLong;
                }
                int bId;
                if (bps == null) {
                    bId = pc[Math.min((int) (sample & MSK), pcc)];
                } else {
                    var state = bps.valueFor((int) (sample&MSK));
                    bId = state == null ? 0 : stateMapper.getIdForBlockState(state);
                }
                sample >>>= eBits;

                byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                nonZeroCnt += (bId != 0)?1:0;
                data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
            }
        } else {
            if (!(storage instanceof ZeroBitStorage)) {
                throw new IllegalStateException();
            }
            int bId = pc[0];
            if (bId == 0) {//Its air
                for (int i = 0; i <= 0xFFF; i++) {
                    data[i] = Mapper.airWithLight(lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF));
                }
            } else {
                nonZeroCnt = 4096;
                for (int i = 0; i <= 0xFFF; i++) {
                    byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                    data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
                }
            }
        }
        section.lvl0NonAirCount = nonZeroCnt;
        return section;
    }

    public static int applySurfacePreviewMask(
            VoxelizedSection section,
            ILightingSupplier lightSupplier,
            int sectionY,
            int[] surfaceHeights,
            int belowSurfaceBlocks,
            int aboveSurfaceBlocks,
            int skyLightThreshold
    ) {
        return applySurfacePreviewMask(section, lightSupplier, sectionY, surfaceHeights, null, belowSurfaceBlocks, aboveSurfaceBlocks, skyLightThreshold);
    }

    public static int applySurfacePreviewMask(
            VoxelizedSection section,
            ILightingSupplier lightSupplier,
            int sectionY,
            int[] surfaceHeights,
            boolean[] exposedPreviewVolume,
            int belowSurfaceBlocks,
            int aboveSurfaceBlocks,
            int skyLightThreshold
    ) {
        return applySurfacePreviewMask(
                section,
                lightSupplier,
                sectionY,
                surfaceHeights,
                null,
                null,
                null,
                exposedPreviewVolume,
                belowSurfaceBlocks,
                aboveSurfaceBlocks,
                skyLightThreshold,
                false
        );
    }

    public static int applySurfacePreviewMask(
            VoxelizedSection section,
            ILightingSupplier lightSupplier,
            int sectionY,
            int[] surfaceHeights,
            boolean[] exposedPreviewVolume,
            int belowSurfaceBlocks,
            int aboveSurfaceBlocks,
            int skyLightThreshold,
            boolean syntheticLightPreview
    ) {
        return applySurfacePreviewMask(
                section,
                lightSupplier,
                sectionY,
                surfaceHeights,
                null,
                null,
                null,
                exposedPreviewVolume,
                belowSurfaceBlocks,
                aboveSurfaceBlocks,
                skyLightThreshold,
                syntheticLightPreview
        );
    }

    public static int applySurfacePreviewMask(
            VoxelizedSection section,
            ILightingSupplier lightSupplier,
            int sectionY,
            int[] surfaceHeights,
            int[] oceanFloorHeights,
            int[] motionBlockingHeights,
            int[] fluidDepths,
            boolean[] exposedPreviewVolume,
            int belowSurfaceBlocks,
            int aboveSurfaceBlocks,
            int skyLightThreshold,
            boolean syntheticLightPreview
    ) {
        return applySurfacePreviewMaskDetailed(
                section,
                lightSupplier,
                sectionY,
                surfaceHeights,
                oceanFloorHeights,
                motionBlockingHeights,
                fluidDepths,
                exposedPreviewVolume,
                belowSurfaceBlocks,
                aboveSurfaceBlocks,
                skyLightThreshold,
                syntheticLightPreview,
                null,
                null,
                null,
                null
        ).kept();
    }

    public static SurfacePreviewMaskResult applySurfacePreviewMaskDetailed(
            VoxelizedSection section,
            ILightingSupplier lightSupplier,
            int sectionY,
            int[] surfaceHeights,
            int[] oceanFloorHeights,
            int[] motionBlockingHeights,
            int[] fluidDepths,
            boolean[] exposedPreviewVolume,
            int belowSurfaceBlocks,
            int aboveSurfaceBlocks,
            int skyLightThreshold,
            boolean syntheticLightPreview,
            boolean[] retainedSurfaceColumns,
            boolean[] retainedOceanFloorColumns,
            boolean[] retainedBoundaryColumns,
            boolean[] boundarySkirtAllowedColumns
    ) {
        if (surfaceHeights.length < 16 * 16) {
            throw new IllegalArgumentException("surfaceHeights must contain 256 entries");
        }
        if (oceanFloorHeights != null && oceanFloorHeights.length < 16 * 16) {
            throw new IllegalArgumentException("oceanFloorHeights must contain 256 entries");
        }
        if (motionBlockingHeights != null && motionBlockingHeights.length < 16 * 16) {
            throw new IllegalArgumentException("motionBlockingHeights must contain 256 entries");
        }
        if (fluidDepths != null && fluidDepths.length < 16 * 16) {
            throw new IllegalArgumentException("fluidDepths must contain 256 entries");
        }
        if (exposedPreviewVolume != null && exposedPreviewVolume.length < 16 * 16 * 16) {
            throw new IllegalArgumentException("exposedPreviewVolume must contain 4096 entries");
        }

        int kept = 0;
        int keptTopShell = 0;
        int keptWaterFloor = 0;
        int keptMotionBlocking = 0;
        int keptExposedShell = 0;
        int keptSkyLitFaces = 0;
        int keptRavineWalls = 0;
        int rejectedSealedCaves = 0;
        int realLightPreserved = 0;
        int syntheticPreviewProvisional = 0;
        int forcedLightApplied = 0;
        int keptBoundarySkirt = 0;
        int sectionHadRetainedSurface = 0;
        int sectionHadRetainedOceanFloor = 0;
        int cleared = 0;
        int baseBlockY = sectionY << 4;
        int requestedShellDepth = SURFACE_PREVIEW_USE_REQUESTED_DEPTH
                ? belowSurfaceBlocks
                : 0;
        int shellDepth = Math.max(
                syntheticLightPreview
                        ? SURFACE_PREVIEW_SYNTHETIC_SHELL_DEPTH_BLOCKS
                        : SURFACE_PREVIEW_SHELL_DEPTH_BLOCKS,
                requestedShellDepth
        );
        for (int i = 0; i <= 0xFFF; i++) {
            long id = section.section[i];
            if (Mapper.isAir(id)) {
                continue;
            }

            int x = i & 0xF;
            int y = (i >> 8) & 0xF;
            int z = (i >> 4) & 0xF;
            int worldY = baseBlockY + y;
            int columnIndex = (z << 4) | x;
            int surfaceY = surfaceHeights[columnIndex];
            int oceanFloorY = oceanFloorHeights == null ? surfaceY : oceanFloorHeights[columnIndex];
            int motionBlockingY = motionBlockingHeights == null ? surfaceY : motionBlockingHeights[columnIndex];
            int fluidDepth = fluidDepths == null ? 0 : Math.max(0, fluidDepths[columnIndex]);

            int lowerExposureBoundY = surfaceY - Math.max(
                    Math.max(belowSurfaceBlocks, SURFACE_PREVIEW_EXPOSED_SOLID_DEPTH_BLOCKS),
                    SURFACE_PREVIEW_SKY_CONNECTED_DEPTH_BLOCKS
            );
            int lowerShellBoundY = surfaceY - Math.min(belowSurfaceBlocks, Math.max(0, shellDepth));
            boolean keep = worldY >= lowerShellBoundY && worldY <= surfaceY + aboveSurfaceBlocks;
            boolean topShell = keep;
            boolean waterFloor = false;
            boolean motionBlocking = false;
            boolean exposedShell = false;
            boolean boundarySkirt = false;
            if (!keep && fluidDepth > 0 && oceanFloorY > Integer.MIN_VALUE / 2) {
                int waterFloorLower = oceanFloorY - Math.min(belowSurfaceBlocks, Math.max(1, shellDepth));
                int waterFloorUpper = oceanFloorY + Math.max(1, aboveSurfaceBlocks);
                waterFloor = worldY >= waterFloorLower && worldY <= waterFloorUpper;
                keep = waterFloor;
            }
            if (!keep && motionBlockingY > Integer.MIN_VALUE / 2) {
                int motionLower = motionBlockingY - Math.min(belowSurfaceBlocks, Math.max(0, shellDepth));
                int motionUpper = motionBlockingY + aboveSurfaceBlocks;
                motionBlocking = worldY >= motionLower && worldY <= motionUpper;
                keep = motionBlocking;
            }
            if (!keep && !syntheticLightPreview) {
                exposedShell = worldY >= lowerExposureBoundY
                        && (isDirectPreviewOpen(exposedPreviewVolume, x, y, z)
                        || isExposedSolid(section, lightSupplier, exposedPreviewVolume, x, y, z, skyLightThreshold));
                keep = exposedShell;
            }
            boolean boundarySkirtAllowed = boundarySkirtAllowedColumns == null || boundarySkirtAllowedColumns[columnIndex];
            if (!keep && boundarySkirtAllowed && SURFACE_PREVIEW_WATERTIGHT && SURFACE_PREVIEW_BOUNDARY_SKIRTS && isBoundaryColumn(x, z)) {
                int boundaryDepth = Math.max(Math.max(1, belowSurfaceBlocks), SURFACE_PREVIEW_EXPOSED_SOLID_DEPTH_BLOCKS);
                int boundaryLower = surfaceY - boundaryDepth;
                boundarySkirt = worldY >= boundaryLower && worldY <= surfaceY + aboveSurfaceBlocks;
                keep = boundarySkirt;
            }

            if (keep) {
                long keptId = syntheticLightPreview ? withMinimumSkyLight(id, skyLightThreshold) : id;
                if (syntheticLightPreview) {
                    syntheticPreviewProvisional++;
                    if (keptId != id) {
                        forcedLightApplied++;
                    }
                } else {
                    realLightPreserved++;
                }
                section.section[i] = keptId;
                kept++;
                if (topShell) {
                    keptTopShell++;
                } else if (waterFloor) {
                    keptWaterFloor++;
                } else if (motionBlocking) {
                    keptMotionBlocking++;
                } else if (exposedShell) {
                    keptExposedShell++;
                    keptSkyLitFaces++;
                    if (worldY < lowerShellBoundY) {
                        keptRavineWalls++;
                    }
                } else if (boundarySkirt) {
                    keptBoundarySkirt++;
                }
                if (retainedSurfaceColumns != null
                        && worldY >= lowerShellBoundY
                        && worldY <= surfaceY + aboveSurfaceBlocks) {
                    if (!retainedSurfaceColumns[columnIndex]) {
                        sectionHadRetainedSurface++;
                    }
                    retainedSurfaceColumns[columnIndex] = true;
                }
                if (retainedOceanFloorColumns != null
                        && fluidDepth > 0
                        && worldY >= oceanFloorY - Math.min(belowSurfaceBlocks, Math.max(1, shellDepth))
                        && worldY <= oceanFloorY + Math.max(1, aboveSurfaceBlocks)) {
                    if (!retainedOceanFloorColumns[columnIndex]) {
                        sectionHadRetainedOceanFloor++;
                    }
                    retainedOceanFloorColumns[columnIndex] = true;
                }
                if (retainedBoundaryColumns != null && isBoundaryColumn(x, z)) {
                    retainedBoundaryColumns[columnIndex] = true;
                }
            } else {
                if (worldY < lowerShellBoundY && worldY >= lowerExposureBoundY) {
                    rejectedSealedCaves++;
                }
                section.section[i] = Mapper.airWithLight(lightSupplier.supply(x, y, z));
                cleared++;
            }
        }

        section.lvl0NonAirCount = kept;
        RenderCorrectnessDiagnostics.ingest(
                "surface_preview_mask_summary",
                section.x,
                sectionY,
                section.z,
                kept == 0,
                kept > 0,
                "kept=" + kept
                        + ",top_shell=" + keptTopShell
                        + ",water_floor=" + keptWaterFloor
                        + ",motion_blocking=" + keptMotionBlocking
                        + ",exposed_shell=" + keptExposedShell
                        + ",boundary_skirt=" + keptBoundarySkirt
                        + ",kept_sky_lit_faces=" + keptSkyLitFaces
                        + ",kept_ravine_walls=" + keptRavineWalls
                        + ",kept_river_bottoms=" + keptWaterFloor
                        + ",rejected_sealed_caves=" + rejectedSealedCaves
                        + ",real_light_preserved=" + realLightPreserved
                        + ",synthetic_preview_provisional=" + syntheticPreviewProvisional
                        + ",forced_light_applied=" + forcedLightApplied
                        + ",sky_connected_depth=" + SURFACE_PREVIEW_SKY_CONNECTED_DEPTH_BLOCKS
                        + ",requested_shell_depth=" + requestedShellDepth
                        + ",effective_shell_depth=" + Math.min(belowSurfaceBlocks, Math.max(0, shellDepth))
                        + ",cleared=" + cleared
        );
        return new SurfacePreviewMaskResult(
                kept,
                keptBoundarySkirt,
                sectionHadRetainedSurface,
                sectionHadRetainedOceanFloor
        );
    }

    public static int fillSurfacePreviewCoverageGaps(
            VoxelizedSection section,
            Mapper mapper,
            ILightingSupplier lightSupplier,
            int sectionY,
            int[] surfaceHeights,
            int[] oceanFloorHeights,
            int[] motionBlockingHeights,
            int[] fluidDepths,
            int belowSurfaceBlocks,
            int skyLightThreshold,
            boolean[] retainedSurfaceColumns,
            boolean[] retainedOceanFloorColumns,
            boolean[] retainedBoundaryColumns,
            boolean[] boundarySkirtAllowedColumns
    ) {
        int baseBlockY = sectionY << 4;
        int snowId = mapper.getIdForBlockState(Blocks.SNOW_BLOCK.defaultBlockState());
        int stoneId = mapper.getIdForBlockState(Blocks.STONE.defaultBlockState());
        int waterId = mapper.getIdForBlockState(Blocks.WATER.defaultBlockState());
        int grassId = mapper.getIdForBlockState(Blocks.GRASS_BLOCK.defaultBlockState());
        int filled = 0;
        int waterFloors = 0;
        int boundarySkirts = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int columnIndex = (z << 4) | x;
                int surfaceY = surfaceHeights[columnIndex];
                int oceanFloorY = oceanFloorHeights == null ? surfaceY : oceanFloorHeights[columnIndex];
                int motionBlockingY = motionBlockingHeights == null ? surfaceY : motionBlockingHeights[columnIndex];
                int fluidDepth = fluidDepths == null ? 0 : Math.max(0, fluidDepths[columnIndex]);
                if (!retainedSurfaceColumns[columnIndex]) {
                    int targetY = Math.max(surfaceY, motionBlockingY);
                    int blockId = fluidDepth > 0 ? waterId : topFillBlockId(surfaceY, oceanFloorY, snowId, grassId, stoneId);
                    if (writeSyntheticCoverageVoxel(section, lightSupplier, x, z, baseBlockY, targetY, blockId, skyLightThreshold)) {
                        retainedSurfaceColumns[columnIndex] = true;
                        filled++;
                    }
                }
                if (fluidDepth > 0 && !retainedOceanFloorColumns[columnIndex]) {
                    if (writeSyntheticCoverageVoxel(section, lightSupplier, x, z, baseBlockY, oceanFloorY, stoneId, skyLightThreshold)) {
                        retainedOceanFloorColumns[columnIndex] = true;
                        waterFloors++;
                        filled++;
                    }
                }
                boolean boundarySkirtAllowed = boundarySkirtAllowedColumns == null || boundarySkirtAllowedColumns[columnIndex];
                if (boundarySkirtAllowed && SURFACE_PREVIEW_BOUNDARY_SKIRTS && retainedBoundaryColumns != null && isBoundaryColumn(x, z) && !retainedBoundaryColumns[columnIndex]) {
                    int bottomY = Math.max(baseBlockY, surfaceY - Math.max(1, belowSurfaceBlocks));
                    int topY = Math.min(baseBlockY + 15, surfaceY);
                    boolean anySkirt = false;
                    for (int y = bottomY; y <= topY; y++) {
                        if (writeSyntheticCoverageVoxel(section, lightSupplier, x, z, baseBlockY, y, stoneId, skyLightThreshold)) {
                            filled++;
                            anySkirt = true;
                        }
                    }
                    if (anySkirt) {
                        retainedBoundaryColumns[columnIndex] = true;
                        boundarySkirts++;
                    }
                }
            }
        }
        if (filled > 0) {
            RenderCorrectnessDiagnostics.ingest(
                    "surface_preview_synthetic_gap_fill",
                    section.x,
                    sectionY,
                    section.z,
                    false,
                    true,
                    "filled=" + filled + ",water_floors=" + waterFloors + ",boundary_skirts=" + boundarySkirts
            );
        }
        return filled;
    }

    private static int topFillBlockId(int surfaceY, int oceanFloorY, int snowId, int grassId, int stoneId) {
        if (surfaceY <= oceanFloorY + 1) {
            return stoneId;
        }
        return surfaceY > 80 ? snowId : grassId;
    }

    private static boolean writeSyntheticCoverageVoxel(
            VoxelizedSection section,
            ILightingSupplier lightSupplier,
            int x,
            int z,
            int baseBlockY,
            int worldY,
            int blockId,
            int skyLightThreshold
    ) {
        int localY = worldY - baseBlockY;
        if (localY < 0 || localY > 15 || blockId == 0) {
            return false;
        }
        int index = (localY << 8) | (z << 4) | x;
        if (!Mapper.isAir(section.section[index])) {
            return false;
        }
        byte light = (byte) Math.max(lightSupplier.supply(x, localY, z), skyLightThreshold);
        section.section[index] = Mapper.composeMappingId(light, blockId, 0);
        section.lvl0NonAirCount++;
        return true;
    }

    private static boolean isBoundaryColumn(int x, int z) {
        return x == 0 || x == 15 || z == 0 || z == 15;
    }

    private static boolean isDirectPreviewOpen(boolean[] exposedPreviewVolume, int x, int y, int z) {
        if (exposedPreviewVolume == null) {
            return false;
        }
        return exposedPreviewVolume[x | (z << 4) | (y << 8)];
    }

    private static long withMinimumSkyLight(long id, int minSkyLight) {
        int light = Mapper.getLightId(id);
        int sky = light & 0x0F;
        if (sky >= minSkyLight) {
            return id;
        }
        int block = (light >>> 4) & 0x0F;
        return Mapper.withLight(id, (block << 4) | Math.min(15, Math.max(0, minSkyLight)));
    }

    private static boolean isExposedSolid(
            VoxelizedSection section,
            ILightingSupplier lightSupplier,
            boolean[] exposedPreviewVolume,
            int x,
            int y,
            int z,
            int skyLightThreshold
    ) {
        return isExposedPreviewNeighbor(section, lightSupplier, exposedPreviewVolume, x + 1, y, z, skyLightThreshold)
                || isExposedPreviewNeighbor(section, lightSupplier, exposedPreviewVolume, x - 1, y, z, skyLightThreshold)
                || isExposedPreviewNeighbor(section, lightSupplier, exposedPreviewVolume, x, y + 1, z, skyLightThreshold)
                || isExposedPreviewNeighbor(section, lightSupplier, exposedPreviewVolume, x, y - 1, z, skyLightThreshold)
                || isExposedPreviewNeighbor(section, lightSupplier, exposedPreviewVolume, x, y, z + 1, skyLightThreshold)
                || isExposedPreviewNeighbor(section, lightSupplier, exposedPreviewVolume, x, y, z - 1, skyLightThreshold);
    }

    private static boolean isExposedPreviewNeighbor(
            VoxelizedSection section,
            ILightingSupplier lightSupplier,
            boolean[] exposedPreviewVolume,
            int x,
            int y,
            int z,
            int skyLightThreshold
    ) {
        if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) {
            return false;
        }
        int idx = x | (z << 4) | (y << 8);
        if (exposedPreviewVolume != null) {
            return exposedPreviewVolume[idx];
        }
        if (!Mapper.isAir(section.section[idx])) {
            return false;
        }
        int sky = Byte.toUnsignedInt(lightSupplier.supply(x, y, z)) & 0xF;
        return sky >= skyLightThreshold;
    }


    private static void computeZoomCells(int[] biomes, long zoomSeed, long[] zoomInfo) {
        for (int cy = 0; cy<4; cy++) {
            for (int cz = 0; cz<4; cz++) {
                for (int cx = 0; cx<4; cx++) {

                }
            }
        }
    }

    //Support for other mods etc that use this entry point
    @Deprecated(forRemoval = true)
    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        WorldVoxilizedSectionMipper.mipSection(section, mapper);
    }
}
