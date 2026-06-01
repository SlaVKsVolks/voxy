package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoxySurfacePregen {
    public static final int DEFAULT_BELOW_SURFACE_BLOCKS = Integer.getInteger("voxy.surfacePregenBelowSurfaceBlocks", 50);
    public static final int DEFAULT_ABOVE_SURFACE_BLOCKS = Integer.getInteger("voxy.surfacePregenAboveSurfaceBlocks", 32);
    private static final int DEFAULT_SKY_LIGHT_THRESHOLD = Integer.getInteger("voxy.surfacePregenSkyLightThreshold", 1);
    private static final int EXPOSED_COLUMN_SOLID_DEPTH_BLOCKS = Integer.getInteger("voxy.surfacePregenExposedColumnSolidDepthBlocks", 8);
    private static final int MAX_INGEST_BACKLOG = Integer.getInteger("voxy.surfacePregenMaxIngestBacklog", 16384);
    private static final int MAX_INGEST_SERVICE_TASKS = Integer.getInteger("voxy.surfacePregenMaxIngestServiceTasks", 8192);
    private static final int MAX_CHUNKS_PER_BATCH = Integer.getInteger("voxy.surfacePregenMaxChunksPerBatch", 32);
    private static final long MIN_BATCH_INTERVAL_NANOS = Math.max(
            0L,
            Long.getLong("voxy.surfacePregenMinBatchIntervalMillis", 50L)
    ) * 1_000_000L;
    private static final int ZERO_CLEARS_PER_BATCH = Integer.getInteger("voxy.surfacePregenZeroClearsPerBatch", 512);
    private static final boolean EMIT_ZERO_CLEARS = Boolean.parseBoolean(System.getProperty("voxy.surfacePregenEmitZeroClears", "true"));
    private static final boolean DEFER_ZERO_CLEARS = Boolean.parseBoolean(System.getProperty("voxy.surfacePregenDeferZeroClears", "true"));
    private static final double MAX_HEAP_USED_FRACTION = Double.parseDouble(System.getProperty("voxy.surfacePregenMaxHeapUsedFraction", "0.82"));
    private static final long BACKPRESSURE_STATUS_INTERVAL_MILLIS = 5000L;
    private static final boolean SHOW_PROGRESS_OVERLAY = Boolean.parseBoolean(System.getProperty("voxy.surfacePregenProgressOverlay", "false"));
    private static final boolean SHOW_CHUNKY_STYLE_BOSSBAR = Boolean.parseBoolean(System.getProperty("voxy.surfacePregenChunkyStyleBossbar", "true"));
    private static final boolean CHAT_PROGRESS = Boolean.parseBoolean(System.getProperty("voxy.surfacePregenChatProgress", "false"));
    private static final long PROGRESS_OVERLAY_INTERVAL_MILLIS = Long.getLong("voxy.surfacePregenProgressOverlayIntervalMillis", 1000L);
    private static final long CHAT_PROGRESS_INTERVAL_MILLIS = Long.getLong("voxy.surfacePregenChatProgressIntervalMillis", 30000L);
    private static final int PROGRESS_BAR_WIDTH = Integer.getInteger("voxy.surfacePregenProgressBarWidth", 20);
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static volatile Job activeJob;
    private static volatile long lastBatchNanos = 0L;

    private VoxySurfacePregen() {
    }

    public static synchronized boolean start(
            int centerChunkX,
            int centerChunkZ,
            int radiusChunks,
            int fullGenerationRadiusChunks,
            int belowSurfaceBlocks,
            int aboveSurfaceBlocks,
            int chunksPerBatch,
            ChunkStatus sourceStatus
    ) {
        if (activeJob != null && !activeJob.cancelled) {
            return false;
        }
        var client = Minecraft.getInstance();
        if (client.level == null || client.getSingleplayerServer() == null) {
            return false;
        }
        var worldId = WorldIdentifier.of(client.level);
        if (worldId == null) {
            return false;
        }
        int requestedFullGenerationRadius = Math.max(0, fullGenerationRadiusChunks);
        int effectiveFullGenerationRadius = effectiveFullGenerationRadius(requestedFullGenerationRadius);
        activeJob = new Job(
                worldId,
                client.level.dimension(),
                centerChunkX,
                centerChunkZ,
                Math.max(0, radiusChunks),
                effectiveFullGenerationRadius,
                Math.max(0, belowSurfaceBlocks),
                Math.max(0, aboveSurfaceBlocks),
                safeChunksPerBatch(chunksPerBatch),
                false,
                sourceStatus == null ? ChunkStatus.LIGHT : sourceStatus
        );
        notifyBatchCapIfNeeded(chunksPerBatch, activeJob.chunksPerBatch);
        if (effectiveFullGenerationRadius != requestedFullGenerationRadius) {
            postChat(Component.literal("Voxy surface LoD pregen capped full-radius skip from "
                    + requestedFullGenerationRadius
                    + " to "
                    + effectiveFullGenerationRadius
                    + " chunks to match vanilla render distance"));
        }
        RenderCorrectnessDiagnostics.resetOutput();
        postChat(Component.literal("Started Voxy surface LoD pregen: " + compactStatusText(activeJob)));
        updateChunkyStyleBossBar(activeJob, "generating");
        return true;
    }

    public static synchronized boolean start(
            int centerChunkX,
            int centerChunkZ,
            int radiusChunks,
            int fullGenerationRadiusChunks,
            int belowSurfaceBlocks,
            int aboveSurfaceBlocks,
            int chunksPerBatch,
            boolean squareArea,
            ChunkStatus sourceStatus
    ) {
        if (activeJob != null && !activeJob.cancelled) {
            return false;
        }
        var client = Minecraft.getInstance();
        if (client.level == null || client.getSingleplayerServer() == null) {
            return false;
        }
        var worldId = WorldIdentifier.of(client.level);
        if (worldId == null) {
            return false;
        }
        int requestedFullGenerationRadius = Math.max(0, fullGenerationRadiusChunks);
        int effectiveFullGenerationRadius = effectiveFullGenerationRadius(requestedFullGenerationRadius);
        activeJob = new Job(
                worldId,
                client.level.dimension(),
                centerChunkX,
                centerChunkZ,
                Math.max(0, radiusChunks),
                effectiveFullGenerationRadius,
                Math.max(0, belowSurfaceBlocks),
                Math.max(0, aboveSurfaceBlocks),
                safeChunksPerBatch(chunksPerBatch),
                squareArea,
                sourceStatus == null ? ChunkStatus.LIGHT : sourceStatus
        );
        notifyBatchCapIfNeeded(chunksPerBatch, activeJob.chunksPerBatch);
        if (effectiveFullGenerationRadius != requestedFullGenerationRadius) {
            postChat(Component.literal("Voxy surface LoD pregen capped full-radius skip from "
                    + requestedFullGenerationRadius
                    + " to "
                    + effectiveFullGenerationRadius
                    + " chunks to match vanilla render distance"));
        }
        RenderCorrectnessDiagnostics.resetOutput();
        postChat(Component.literal("Started Voxy surface LoD pregen: " + compactStatusText(activeJob)));
        updateChunkyStyleBossBar(activeJob, "generating");
        return true;
    }

    public static synchronized boolean cancel() {
        var job = activeJob;
        if (job == null) {
            return false;
        }
        job.cancelled = true;
        removeChunkyStyleBossBar(job);
        activeJob = null;
        postChat(Component.literal("Cancelled Voxy surface LoD pregen"));
        return true;
    }

    public static synchronized boolean pause() {
        var job = activeJob;
        if (job == null || job.cancelled) {
            return false;
        }
        job.paused = true;
        postChat(Component.literal("Paused Voxy surface LoD pregen: " + compactStatusText(job)));
        updateChunkyStyleBossBar(job, "paused");
        return true;
    }

    public static synchronized boolean resume() {
        var job = activeJob;
        if (job == null || job.cancelled) {
            return false;
        }
        job.paused = false;
        postChat(Component.literal("Resumed Voxy surface LoD pregen: " + compactStatusText(job)));
        updateChunkyStyleBossBar(job, "generating");
        return true;
    }

    public static String statusText() {
        var job = activeJob;
        if (job == null) {
            return "idle";
        }
        return "processed=" + job.processedChunks
                + "/" + job.totalChunks
                + " queued_sections=" + job.queuedSections
                + " skipped_sections=" + job.skippedSections
                + " skipped_air_sections=" + job.skippedAirSections
                + " skipped_preview_mask_sections=" + job.skippedPreviewMaskSections
                + " cleared_sections=" + job.clearedSections
                + " deferred_clears=" + job.deferredClearSections
                + " skipped_zero_clears=" + job.skippedZeroClearSections
                + " skipped_missing_sky_light=" + job.skippedMissingSkyLightSections
                + " synthetic_sky_light=" + job.syntheticSkyLightSections
                + " exposed_empty_sky_fallback=" + job.exposedEmptySkyFallbackSections
                + " queued_synthetic_sky_light=" + job.queuedSyntheticSkyLightSections
                + " queued_real_light=" + job.queuedRealLightSections
                + " surface_columns_total=" + job.surfaceColumnsTotal
                + " surface_columns_without_retained_voxel=" + job.surfaceColumnsWithoutRetainedVoxel
                + " ocean_floor_columns_without_retained_voxel=" + job.oceanFloorColumnsWithoutRetainedVoxel
                + " chunk_boundary_exposure_misses=" + job.chunkBoundaryExposureMisses
                + " preview_gap_sections=" + job.previewGapSections
                + " preview_open_fluid=" + job.previewOpenFluidCells
                + " sky_lit_open=" + job.skyLitOpenCells
                + " sky_lit_solid_faces=" + job.skyLitSolidFaceCells
                + " sky_exposure_no_light_sections=" + job.skyExposureNoLightSections
                + " depth=" + job.belowSurfaceBlocks
                + " sky_threshold=" + DEFAULT_SKY_LIGHT_THRESHOLD
                + " failed_chunks=" + job.failedChunks
                + " source_status=" + job.sourceStatus.getName()
                + " shape=" + (job.squareArea ? "square" : "circle")
                + " paused=" + job.paused
                + " remaining=" + job.queue.size();
    }

    public static ChunkStatus parseSourceStatus(String statusName) {
        if (statusName == null || statusName.isBlank()) {
            return ChunkStatus.LIGHT;
        }
        return switch (statusName.toLowerCase(Locale.ROOT)) {
            case "surface" -> ChunkStatus.SURFACE;
            case "carvers", "caves", "ravines" -> ChunkStatus.CARVERS;
            case "features", "structures" -> ChunkStatus.FEATURES;
            case "light", "lighting" -> ChunkStatus.LIGHT;
            case "full" -> ChunkStatus.FULL;
            default -> throw new IllegalArgumentException("Unknown Voxy surface pregen source status: " + statusName);
        };
    }

    private static int effectiveFullGenerationRadius(int requestedFullGenerationRadius) {
        // A radius of zero means "do not skip any chunks". Validation and repair
        // passes need this so the generated square has no intentional center hole.
        return Math.max(0, requestedFullGenerationRadius);
    }

    private static int safeChunksPerBatch(int requestedChunksPerBatch) {
        int requested = Math.max(1, requestedChunksPerBatch);
        int cap = Math.max(1, MAX_CHUNKS_PER_BATCH);
        return Math.min(requested, cap);
    }

    private static void notifyBatchCapIfNeeded(int requestedChunksPerBatch, int effectiveChunksPerBatch) {
        if (Math.max(1, requestedChunksPerBatch) <= effectiveChunksPerBatch) {
            return;
        }
        postChat(Component.literal("Voxy surface LoD pregen capped chunks-per-batch from "
                + requestedChunksPerBatch
                + " to "
                + effectiveChunksPerBatch
                + " for live-client render stability"));
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        var job = activeJob;
        if (job == null || job.cancelled || IN_FLIGHT.get()) {
            return;
        }
        if (job.paused) {
            return;
        }
        long now = System.nanoTime();
        if (MIN_BATCH_INTERVAL_NANOS > 0L && now - lastBatchNanos < MIN_BATCH_INTERVAL_NANOS) {
            return;
        }
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server == null) {
            activeJob = null;
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        lastBatchNanos = now;
        server.execute(() -> {
            try {
                processBatch(job);
            } finally {
                IN_FLIGHT.set(false);
            }
        });
    }

    private static void processBatch(Job job) {
        if (job.cancelled) {
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null || instance.getIngestService() == null) {
            job.cancelled = true;
            activeJob = null;
            Logger.warn("Voxy surface LoD pregen stopped because Voxy ingest is unavailable");
            return;
        }
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            job.cancelled = true;
            activeJob = null;
            return;
        }
        ServerLevel level = server.getLevel(job.dimension);
        if (level == null) {
            job.cancelled = true;
            activeJob = null;
            Logger.warn("Voxy surface LoD pregen stopped because server level is missing: ", job.dimension.location().toString());
            return;
        }
        WorldEngine engine = job.worldId.getOrCreateEngine();
        if (engine == null) {
            job.cancelled = true;
            activeJob = null;
            return;
        }

        if (isBackpressured(job, instance.getIngestService())) {
            return;
        }

        if (!job.queue.isEmpty()) {
            int processedThisBatch = 0;
            while (processedThisBatch < job.chunksPerBatch && !job.queue.isEmpty() && !job.cancelled) {
                if (processedThisBatch > 0 && isBackpressured(job, instance.getIngestService())) {
                    break;
                }
                ChunkCoord coord = job.queue.removeFirst();
                processedThisBatch++;
                try {
                    processChunk(level, engine, instance.getIngestService(), coord.x, coord.z, job);
                    job.processedChunks++;
                } catch (RuntimeException error) {
                    job.failedChunks++;
                    Logger.warn("Voxy surface LoD pregen failed for chunk ", coord.x, ",", coord.z, error);
                }
            }

            if (!job.queue.isEmpty() && (job.processedChunks % 256) == 0) {
                postProgress(job, "generating");
            }
        }

        if (job.queue.isEmpty() && !job.deferredClears.isEmpty()) {
            int clearsThisBatch = 0;
            while (clearsThisBatch < ZERO_CLEARS_PER_BATCH && !job.deferredClears.isEmpty() && !job.cancelled) {
                if (clearsThisBatch > 0 && isBackpressured(job, instance.getIngestService())) {
                    break;
                }
                SectionClear clear = job.deferredClears.removeFirst();
                instance.getIngestService().queueZeroSection(engine, clear.x, clear.y, clear.z, clear.stage);
                job.clearedSections++;
                clearsThisBatch++;
            }

            if (!job.deferredClears.isEmpty()) {
                postProgress(job, "clearing");
                return;
            }
        }

        if (job.queue.isEmpty() && job.deferredClears.isEmpty()) {
            activeJob = null;
            postProgress(job, "finished");
            removeChunkyStyleBossBar(job);
            postChat(Component.literal("Finished Voxy surface LoD pregen: " + statusText(job)));
        }
    }

    private static boolean isBackpressured(Job job, VoxelIngestService ingestService) {
        int rawQueue = ingestService.getRawQueueSize();
        int serviceTasks = ingestService.getTaskCount();
        Runtime runtime = Runtime.getRuntime();
        long max = Math.max(1L, runtime.maxMemory());
        long used = runtime.totalMemory() - runtime.freeMemory();
        boolean heapHigh = used > (long) (max * MAX_HEAP_USED_FRACTION);
        boolean queueHigh = rawQueue > MAX_INGEST_BACKLOG || serviceTasks > MAX_INGEST_SERVICE_TASKS;
        if (!heapHigh && !queueHigh) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - job.lastBackpressureMessageMillis >= BACKPRESSURE_STATUS_INTERVAL_MILLIS) {
            job.lastBackpressureMessageMillis = now;
            postProgress(
                    job,
                    "waiting q=" + rawQueue
                            + " tasks=" + serviceTasks
                            + " heap=" + (used >> 20)
                            + "/" + (max >> 20) + "MB"
            );
        }
        return true;
    }

    private static void processChunk(
            ServerLevel level,
            WorldEngine engine,
            VoxelIngestService ingestService,
            int chunkX,
            int chunkZ,
            Job job
    ) {
        ChunkAccess chunk = level.getChunk(chunkX, chunkZ, job.sourceStatus, true);
        SurfacePreviewColumns columns = surfaceColumns(chunk);
        LevelChunkSection[] sections = chunk.getSections();
        var lightEngine = level.getLightEngine();
        var blockLight = lightEngine.getLayerListener(LightLayer.BLOCK);
        var skyLight = lightEngine.getLayerListener(LightLayer.SKY);
        boolean[][] exposedPreviewVolume = skyExposureVolumeBySection(chunk, skyLight, job);
        boolean[] retainedSurfaceColumns = new boolean[16 * 16];
        boolean[] retainedOceanFloorColumns = new boolean[16 * 16];
        boolean[] retainedBoundaryColumns = new boolean[16 * 16];
        boolean[] boundarySkirtAllowedColumns = boundarySkirtAllowedColumns(chunkX, chunkZ, job);

        int sectionY = chunk.getMinSection();
        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) {
                job.skippedAirSections++;
                queueOrDeferZeroSection(ingestService, engine, chunkX, sectionY, chunkZ, "surface_preview_clear_air", job);
                sectionY++;
                continue;
            }

            var pos = SectionPos.of(new ChunkPos(chunkX, chunkZ), sectionY);
            DataLayer bl = blockLight.getDataLayerData(pos);
            DataLayer sl = skyLight.getDataLayerData(pos);
            LayerLightSectionStorage.SectionType skySectionType = lightEngine.getDebugSectionType(LightLayer.SKY, pos);
            boolean[] exposedSectionPreview = exposedPreviewVolume[sectionIndex(chunk, sectionY)];
            boolean hasExposedPreviewCells = hasExposedPreviewCells(exposedSectionPreview);
            int fallbackSkyLight = VoxelIngestService.fallbackSkyLightFor(skySectionType, section, sl, sectionY);
            boolean exposedEmptySkyFallback = false;
            if (fallbackSkyLight < 0
                    && level.dimensionType().hasSkyLight()
                    && !section.hasOnlyAir()
                    && hasExposedPreviewCells
                    && sl != null
                    && sl.isEmpty()) {
                fallbackSkyLight = 15;
                exposedEmptySkyFallback = true;
            }
            boolean missingSkyLight = level.dimensionType().hasSkyLight()
                    && !section.hasOnlyAir()
                    && sl == null
                    && fallbackSkyLight < 0;
            if (missingSkyLight) {
                job.skippedMissingSkyLightSections++;
                RenderCorrectnessDiagnostics.ingest(
                        "surface_preview_missing_sky_light_skip",
                        chunkX,
                        sectionY,
                        chunkZ,
                        false,
                        false,
                        "waiting_for_initialized_sky_light"
                );
                sectionY++;
                continue;
            }
            boolean defaultSkyLight = fallbackSkyLight >= 0;
            if (defaultSkyLight) {
                job.syntheticSkyLightSections++;
                if (exposedEmptySkyFallback) {
                    job.exposedEmptySkyFallbackSections++;
                }
            }
            VoxelizedSection.LightSourceKind lightSourceKind = exposedEmptySkyFallback
                    ? VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW
                    : resolvePreviewLightSourceKind(
                            level.dimensionType().hasSkyLight(),
                            section,
                            sl,
                            skySectionType,
                            fallbackSkyLight
                    );
            if (exposedEmptySkyFallback) {
                RenderCorrectnessDiagnostics.ingest(
                        "surface_preview_exposed_empty_sky_fallback",
                        chunkX,
                        sectionY,
                        chunkZ,
                        false,
                        true,
                        "exposed_preview_empty_sky_light_defaulted"
                );
            } else if (level.dimensionType().hasSkyLight() && !section.hasOnlyAir() && sl != null && sl.isEmpty()) {
                RenderCorrectnessDiagnostics.ingest(
                        "surface_preview_valid_empty_sky_light",
                        chunkX,
                        sectionY,
                        chunkZ,
                        false,
                        false,
                        "valid_zero_sky_light"
                );
            } else if (fallbackSkyLight >= 0) {
                RenderCorrectnessDiagnostics.ingest(
                        "surface_preview_valid_default_sky_light",
                        chunkX,
                        sectionY,
                        chunkZ,
                        false,
                        false,
                        "light_only_default"
                );
            }
            if (bl != null) {
                bl = bl.copy();
            }
            if (sl != null && !sl.isEmpty()) {
                sl = sl.copy();
            }
            ILightingSupplier lightingSupplier = exposedEmptySkyFallback
                    ? lightSupplier(bl, sl, fallbackSkyLight, exposedSectionPreview, true)
                    : lightSupplier(bl, sl, fallbackSkyLight);
            VoxelizedSection preview = WorldConversionFactory.convert(
                    VoxelizedSection.createEmpty()
                            .setPosition(chunkX, sectionY, chunkZ)
                            .setSyntheticPreview(false)
                            .setLightSourceKind(lightSourceKind)
                            .setSource(
                                    lightSourceKind == VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW
                                            ? VoxelizedSection.SourceKind.SYNTHETIC_PREVIEW
                                            : VoxelizedSection.SourceKind.SURFACE_PREVIEW,
                                    VoxelizedSection.isTrustedLight(lightSourceKind)
                                            ? VoxelizedSection.Confidence.MEDIUM
                                            : VoxelizedSection.Confidence.LOW
                            ),
                    engine.getMapper(),
                    section.getStates(),
                    section.getBiomes(),
                    lightingSupplier
            );
            WorldConversionFactory.SurfacePreviewMaskResult maskResult = WorldConversionFactory.applySurfacePreviewMaskDetailed(
                    preview,
                    lightingSupplier,
                    sectionY,
                    columns.worldSurface(),
                    columns.oceanFloor(),
                    columns.motionBlocking(),
                    columns.fluidDepth(),
                    exposedSectionPreview,
                    job.belowSurfaceBlocks,
                    job.aboveSurfaceBlocks,
                    DEFAULT_SKY_LIGHT_THRESHOLD,
                    lightSourceKind == VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW,
                    retainedSurfaceColumns,
                    retainedOceanFloorColumns,
                    retainedBoundaryColumns,
                    boundarySkirtAllowedColumns
            );
            int kept = maskResult.kept();
            kept += WorldConversionFactory.fillSurfacePreviewCoverageGaps(
                    preview,
                    engine.getMapper(),
                    lightingSupplier,
                    sectionY,
                    columns.worldSurface(),
                    columns.oceanFloor(),
                    columns.motionBlocking(),
                    columns.fluidDepth(),
                    job.belowSurfaceBlocks,
                    DEFAULT_SKY_LIGHT_THRESHOLD,
                    retainedSurfaceColumns,
                    retainedOceanFloorColumns,
                    retainedBoundaryColumns,
                    boundarySkirtAllowedColumns
            );
            if (kept > 0) {
                WorldVoxilizedSectionMipper.mipSection(preview, engine.getMapper());
                if (ingestService.queueVoxelizedSection(engine, preview, "surface_preview")) {
                    job.queuedSections++;
                    if (defaultSkyLight) {
                        job.queuedSyntheticSkyLightSections++;
                    } else {
                        job.queuedRealLightSections++;
                    }
                }
            } else {
                job.skippedSections++;
                job.skippedPreviewMaskSections++;
                queueOrDeferZeroSection(ingestService, engine, chunkX, sectionY, chunkZ, "surface_preview_clear_mask", job);
            }
            sectionY++;
        }
        recordChunkCoverageDiagnostics(chunkX, chunkZ, columns, retainedSurfaceColumns, retainedOceanFloorColumns, retainedBoundaryColumns, job);
    }

    private static void recordChunkCoverageDiagnostics(
            int chunkX,
            int chunkZ,
            SurfacePreviewColumns columns,
            boolean[] retainedSurfaceColumns,
            boolean[] retainedOceanFloorColumns,
            boolean[] retainedBoundaryColumns,
            Job job
    ) {
        int surfaceTotal = 0;
        int missingSurface = 0;
        int missingOceanFloor = 0;
        int boundaryMisses = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = (z << 4) | x;
                surfaceTotal++;
                if (!retainedSurfaceColumns[index]) {
                    missingSurface++;
                    if (x == 0 || x == 15 || z == 0 || z == 15) {
                        boundaryMisses++;
                    }
                }
                if (columns.fluidDepth()[index] > 0 && !retainedOceanFloorColumns[index]) {
                    missingOceanFloor++;
                }
            }
        }
        int gapSections = (missingSurface > 0 || missingOceanFloor > 0 || boundaryMisses > 0) ? 1 : 0;
        job.surfaceColumnsTotal += surfaceTotal;
        job.surfaceColumnsWithoutRetainedVoxel += missingSurface;
        job.oceanFloorColumnsWithoutRetainedVoxel += missingOceanFloor;
        job.chunkBoundaryExposureMisses += boundaryMisses;
        job.previewGapSections += gapSections;
        RenderCorrectnessDiagnostics.surfacePreviewGap(
                gapSections == 0 ? "chunk_coverage_ok" : "chunk_coverage_gap",
                chunkX,
                chunkZ,
                surfaceTotal,
                missingSurface,
                missingOceanFloor,
                boundaryMisses,
                gapSections,
                "retained_boundary_columns=" + retainedColumnCount(retainedBoundaryColumns)
        );
    }

    private static boolean[] boundarySkirtAllowedColumns(int chunkX, int chunkZ, Job job) {
        boolean[] allowed = new boolean[16 * 16];
        boolean west = !job.includesGeneratedChunk(chunkX - 1, chunkZ);
        boolean east = !job.includesGeneratedChunk(chunkX + 1, chunkZ);
        boolean north = !job.includesGeneratedChunk(chunkX, chunkZ - 1);
        boolean south = !job.includesGeneratedChunk(chunkX, chunkZ + 1);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                allowed[(z << 4) | x] = (x == 0 && west)
                        || (x == 15 && east)
                        || (z == 0 && north)
                        || (z == 15 && south);
            }
        }
        return allowed;
    }

    private static int retainedColumnCount(boolean[] columns) {
        int count = 0;
        for (boolean retained : columns) {
            if (retained) {
                count++;
            }
        }
        return count;
    }

    private static void queueOrDeferZeroSection(
            VoxelIngestService ingestService,
            WorldEngine engine,
            int chunkX,
            int sectionY,
            int chunkZ,
            String stage,
            Job job
    ) {
        if (!EMIT_ZERO_CLEARS) {
            job.skippedZeroClearSections++;
            return;
        }
        if (DEFER_ZERO_CLEARS) {
            job.deferredClears.add(new SectionClear(chunkX, sectionY, chunkZ, stage));
            job.deferredClearSections++;
            RenderCorrectnessDiagnostics.ingest(stage + "_deferred", chunkX, sectionY, chunkZ, true, true, "deferred_zero");
            return;
        }
        ingestService.queueZeroSection(engine, chunkX, sectionY, chunkZ, stage);
        job.clearedSections++;
    }

    private static SurfacePreviewColumns surfaceColumns(ChunkAccess chunk) {
        int[] worldSurface = new int[16 * 16];
        int[] oceanFloor = new int[16 * 16];
        int[] motionBlocking = new int[16 * 16];
        int[] fluidDepth = new int[16 * 16];
        Arrays.fill(worldSurface, chunk.getMinBuildHeight());
        Arrays.fill(oceanFloor, chunk.getMinBuildHeight());
        Arrays.fill(motionBlocking, chunk.getMinBuildHeight());
        LevelChunkSection[] sections = chunk.getSections();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int columnIndex = (z << 4) | x;
                boolean foundWorldSurface = false;
                boolean foundOceanFloor = false;
                boolean foundMotionBlocking = false;
                boolean sawFluid = false;
                int contiguousFluidDepth = 0;
                columnScan:
                for (int sectionIndex = sections.length - 1; sectionIndex >= 0; sectionIndex--) {
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int sectionY = chunk.getMinSection() + sectionIndex;
                    for (int y = 15; y >= 0; y--) {
                        BlockState state = section.getBlockState(x, y, z);
                        int worldY = (sectionY << 4) + y;
                        boolean fluid = !state.getFluidState().isEmpty();
                        boolean frozenWaterCap = isFrozenWaterCap(state);
                        boolean nonAir = !state.isAir();
                        if (!foundWorldSurface && nonAir) {
                            worldSurface[columnIndex] = worldY;
                            foundWorldSurface = true;
                        }
                        if (!foundMotionBlocking && (state.canOcclude() || fluid)) {
                            motionBlocking[columnIndex] = worldY;
                            foundMotionBlocking = true;
                        }
                        if (fluid || frozenWaterCap) {
                            sawFluid = true;
                            contiguousFluidDepth++;
                            continue;
                        }
                        if (sawFluid && !foundOceanFloor && nonAir) {
                            oceanFloor[columnIndex] = worldY;
                            fluidDepth[columnIndex] = contiguousFluidDepth;
                            foundOceanFloor = true;
                        }
                        if (foundWorldSurface && foundMotionBlocking && (!sawFluid || foundOceanFloor)) {
                            break columnScan;
                        }
                    }
                }
                if (!foundOceanFloor) {
                    oceanFloor[columnIndex] = worldSurface[columnIndex];
                    fluidDepth[columnIndex] = sawFluid ? contiguousFluidDepth : 0;
                }
            }
        }
        return new SurfacePreviewColumns(worldSurface, oceanFloor, motionBlocking, fluidDepth);
    }

    private static boolean[][] skyExposureVolumeBySection(
            ChunkAccess chunk,
            LayerLightEventListener skyLight,
            Job job
    ) {
        LevelChunkSection[] sections = chunk.getSections();
        boolean[][] exposed = new boolean[sections.length][16 * 16 * 16];

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int sectionY = chunk.getMinSection() + sectionIndex;
            DataLayer sky = skyLight == null ? null : skyLight.getDataLayerData(SectionPos.of(chunk.getPos(), sectionY));
            if (sky == null || sky.isEmpty()) {
                job.skyExposureNoLightSections++;
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int skyValue = sky.get(x, y, z);
                        if (skyValue < DEFAULT_SKY_LIGHT_THRESHOLD) {
                            continue;
                        }
                        BlockState state = blockState(section, x, y, z);
                        if (!isPreviewOpen(state)) {
                            continue;
                        }
                        markExposed(exposed, sectionIndex, x, y, z);
                        job.skyLitOpenCells++;
                        if (state != null && !state.getFluidState().isEmpty()) {
                            job.previewOpenFluidCells++;
                        }
                    }
                }
            }
        }
        return expandExposedPreviewShell(exposed, sections, job);
    }

    private static BlockState blockState(LevelChunkSection section, int x, int y, int z) {
        if (section == null || section.hasOnlyAir()) {
            return null;
        }
        return section.getBlockState(x, y, z);
    }

    private static boolean isPreviewOpen(BlockState state) {
        return state == null || state.isAir() || !state.getFluidState().isEmpty() || isFrozenWaterCap(state);
    }

    private static boolean isFrozenWaterCap(BlockState state) {
        return state != null
                && (state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.FROSTED_ICE));
    }

    private static void markExposedSolidColumn(boolean[][] exposed, LevelChunkSection[] sections, int sectionIndex, int x, int y, int z) {
        int remaining = Math.max(1, EXPOSED_COLUMN_SOLID_DEPTH_BLOCKS);
        for (int idx = sectionIndex; idx >= 0 && remaining > 0; idx--) {
            LevelChunkSection section = sections[idx];
            int startY = idx == sectionIndex ? y : 15;
            for (int yy = startY; yy >= 0 && remaining > 0; yy--) {
                BlockState state = blockState(section, x, yy, z);
                if (isPreviewOpen(state)) {
                    markExposed(exposed, idx, x, yy, z);
                    continue;
                }
                markExposed(exposed, idx, x, yy, z);
                remaining--;
            }
        }
    }

    private static boolean[][] expandExposedPreviewShell(boolean[][] exposed, LevelChunkSection[] sections, Job job) {
        boolean[][] expanded = new boolean[exposed.length][];
        for (int i = 0; i < exposed.length; i++) {
            expanded[i] = Arrays.copyOf(exposed[i], exposed[i].length);
        }

        int marked = 0;
        for (int sectionIndex = 0; sectionIndex < exposed.length; sectionIndex++) {
            boolean[] sectionMask = exposed[sectionIndex];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!sectionMask[x | (z << 4) | (y << 8)]) {
                            continue;
                        }
                        marked += markVisibleSolidNeighbor(expanded, sections, sectionIndex, x + 1, y, z);
                        marked += markVisibleSolidNeighbor(expanded, sections, sectionIndex, x - 1, y, z);
                        marked += markVisibleSolidNeighbor(expanded, sections, sectionIndex, x, y + 1, z);
                        marked += markVisibleSolidNeighbor(expanded, sections, sectionIndex, x, y - 1, z);
                        marked += markVisibleSolidNeighbor(expanded, sections, sectionIndex, x, y, z + 1);
                        marked += markVisibleSolidNeighbor(expanded, sections, sectionIndex, x, y, z - 1);
                    }
                }
            }
        }
        if (marked > 0) {
            job.skyLitSolidFaceCells += marked;
            RenderCorrectnessDiagnostics.ingest(
                    "surface_preview_exposed_shell_expanded",
                    0,
                    0,
                    0,
                    false,
                    true,
                    "marked_visible_solid_neighbors=" + marked
            );
        }
        return expanded;
    }

    private static int markVisibleSolidNeighbor(boolean[][] exposed, LevelChunkSection[] sections, int sectionIndex, int x, int y, int z) {
        int adjustedSection = sectionIndex;
        int adjustedY = y;
        if (adjustedY < 0) {
            adjustedSection--;
            adjustedY = 15;
        } else if (adjustedY > 15) {
            adjustedSection++;
            adjustedY = 0;
        }
        if (adjustedSection < 0 || adjustedSection >= sections.length || x < 0 || x > 15 || z < 0 || z > 15) {
            return 0;
        }
        BlockState state = blockState(sections[adjustedSection], x, adjustedY, z);
        if (isPreviewOpen(state)) {
            return 0;
        }
        int marked = 0;
        int remaining = Math.max(1, EXPOSED_COLUMN_SOLID_DEPTH_BLOCKS / 2);
        for (int idx = adjustedSection; idx >= 0 && remaining > 0; idx--) {
            int startY = idx == adjustedSection ? adjustedY : 15;
            for (int yy = startY; yy >= 0 && remaining > 0; yy--) {
                BlockState shellState = blockState(sections[idx], x, yy, z);
                if (isPreviewOpen(shellState)) {
                    break;
                }
                int maskIndex = x | (z << 4) | (yy << 8);
                if (!exposed[idx][maskIndex]) {
                    exposed[idx][maskIndex] = true;
                    marked++;
                }
                remaining--;
            }
        }
        return marked;
    }

    private static void markExposed(boolean[][] exposed, int sectionIndex, int x, int y, int z) {
        exposed[sectionIndex][x | (z << 4) | (y << 8)] = true;
    }

    private static boolean hasExposedPreviewCells(boolean[] exposedPreviewVolume) {
        if (exposedPreviewVolume == null) {
            return false;
        }
        for (boolean exposed : exposedPreviewVolume) {
            if (exposed) {
                return true;
            }
        }
        return false;
    }

    private static int sectionIndex(ChunkAccess chunk, int sectionY) {
        return Math.max(0, Math.min(chunk.getSections().length - 1, sectionY - chunk.getMinSection()));
    }

    private static ILightingSupplier lightSupplier(DataLayer blockLight, DataLayer skyLight) {
        return lightSupplier(blockLight, skyLight, -1);
    }

    private static ILightingSupplier lightSupplier(DataLayer blockLight, DataLayer skyLight, int syntheticSkyLight) {
        return lightSupplier(blockLight, skyLight, syntheticSkyLight, null, false);
    }

    private static ILightingSupplier lightSupplier(
            DataLayer blockLight,
            DataLayer skyLight,
            int syntheticSkyLight,
            boolean[] syntheticSkyMask,
            boolean syntheticSkyOnlyForMask
    ) {
        return (x, y, z) -> {
            int block = blockLight == null ? 0 : Math.min(15, blockLight.get(x, y, z));
            int sky = skyLight == null
                    ? Math.max(0, syntheticSkyLight)
                    : Math.min(15, skyLight.get(x, y, z));
            if (syntheticSkyLight >= 0) {
                boolean applySyntheticSky = !syntheticSkyOnlyForMask
                        || (syntheticSkyMask != null && syntheticSkyMask[x | (z << 4) | (y << 8)]);
                if (applySyntheticSky) {
                    sky = Math.max(sky, Math.min(15, syntheticSkyLight));
                }
            }
            return (byte) (sky | (block << 4));
        };
    }

    private static VoxelizedSection.LightSourceKind resolvePreviewLightSourceKind(
            boolean skyExpected,
            LevelChunkSection section,
            DataLayer skyLight,
            LayerLightSectionStorage.SectionType skySectionType,
            int fallbackSkyLight
    ) {
        if (!skyExpected) {
            return VoxelizedSection.LightSourceKind.NO_SKY_DIMENSION;
        }
        if (section == null || section.hasOnlyAir()) {
            return VoxelizedSection.LightSourceKind.REAL_LIGHT;
        }
        if (fallbackSkyLight >= 0 || skySectionType == LayerLightSectionStorage.SectionType.LIGHT_ONLY) {
            return VoxelizedSection.LightSourceKind.VALID_DEFAULT_SKY_LIGHT;
        }
        if (skyLight == null) {
            return VoxelizedSection.LightSourceKind.MISSING_SKY_LIGHT;
        }
        if (skyLight.isEmpty()) {
            return VoxelizedSection.LightSourceKind.VALID_EMPTY_SKY_LIGHT;
        }
        return VoxelizedSection.LightSourceKind.REAL_LIGHT;
    }

    private static void postChat(Component message) {
        var client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.gui != null) {
                client.gui.getChat().addMessage(message);
            }
        });
    }

    private static void postOverlay(Component message) {
        if (!SHOW_PROGRESS_OVERLAY) {
            return;
        }
        var client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.gui != null) {
                client.gui.setOverlayMessage(message, false);
            }
        });
    }

    private static void postProgress(Job job, String phase) {
        long now = System.currentTimeMillis();
        Component message = null;
        if (SHOW_CHUNKY_STYLE_BOSSBAR && now - job.lastBossBarUpdateMillis >= PROGRESS_OVERLAY_INTERVAL_MILLIS) {
            job.lastBossBarUpdateMillis = now;
            updateChunkyStyleBossBar(job, phase);
        }
        if (SHOW_PROGRESS_OVERLAY && now - job.lastProgressOverlayMillis >= PROGRESS_OVERLAY_INTERVAL_MILLIS) {
            job.lastProgressOverlayMillis = now;
            message = Component.literal(progressText(job, phase));
            postOverlay(message);
        }
        if (CHAT_PROGRESS && now - job.lastProgressChatMillis >= CHAT_PROGRESS_INTERVAL_MILLIS) {
            job.lastProgressChatMillis = now;
            postChat(message == null ? Component.literal(progressText(job, phase)) : message);
        }
    }

    private static void updateChunkyStyleBossBar(Job job, String phase) {
        if (!SHOW_CHUNKY_STYLE_BOSSBAR) {
            return;
        }
        var client = Minecraft.getInstance();
        client.execute(() -> {
            var server = client.getSingleplayerServer();
            if (server == null) {
                return;
            }
            ServerBossEvent bossBar = job.bossBar;
            if (bossBar == null) {
                bossBar = new ServerBossEvent(
                        Component.nullToEmpty(job.dimension.location().toString()),
                        bossBarColor(job.dimension.location()),
                        BossEvent.BossBarOverlay.PROGRESS
                );
                bossBar.setDarkenScreen(false);
                bossBar.setPlayBossMusic(false);
                bossBar.setCreateWorldFog(false);
                bossBar.setVisible(true);
                job.bossBar = bossBar;
            }
            for (var player : server.getPlayerList().getPlayers()) {
                bossBar.addPlayer(player);
            }
            bossBar.setName(Component.nullToEmpty(chunkyStyleBossBarTitle(job, phase)));
            bossBar.setProgress(progressFraction(job));
        });
    }

    private static void removeChunkyStyleBossBar(Job job) {
        ServerBossEvent bossBar = job.bossBar;
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            job.bossBar = null;
        }
    }

    private static String chunkyStyleBossBarTitle(Job job, String phase) {
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - job.startedAtMillis) / 1000L);
        long hours = elapsedSeconds / 3600L;
        long minutes = (elapsedSeconds / 60L) % 60L;
        long seconds = elapsedSeconds % 60L;
        return String.format(
                Locale.ROOT,
                "%s | %.2f%% | %01d:%02d:%02d%s",
                job.dimension.location(),
                progressFraction(job) * 100.0F,
                hours,
                minutes,
                seconds,
                phase.isBlank() || "generating".equals(phase) ? "" : " | " + phase
        );
    }

    private static float progressFraction(Job job) {
        int total = Math.max(1, job.totalChunks);
        return (float) Math.min(1.0D, Math.max(0.0D, job.processedChunks / (double) total));
    }

    private static BossEvent.BossBarColor bossBarColor(ResourceLocation dimension) {
        if (Level.OVERWORLD.location().equals(dimension)) {
            return BossEvent.BossBarColor.GREEN;
        }
        if (Level.NETHER.location().equals(dimension)) {
            return BossEvent.BossBarColor.RED;
        }
        if (Level.END.location().equals(dimension)) {
            return BossEvent.BossBarColor.PURPLE;
        }
        return BossEvent.BossBarColor.BLUE;
    }

    private static String progressText(Job job, String phase) {
        int total = Math.max(1, job.totalChunks);
        double progress = Math.min(1.0D, Math.max(0.0D, job.processedChunks / (double) total));
        int width = Math.max(4, PROGRESS_BAR_WIDTH);
        int filled = Math.min(width, (int) Math.round(progress * width));
        String bar = "#".repeat(filled) + "-".repeat(width - filled);
        return String.format(
                Locale.ROOT,
                "Voxy LoD %s [%s] %.2f%% %d/%d sections=%d failed=%d",
                phase,
                bar,
                progress * 100.0D,
                job.processedChunks,
                job.totalChunks,
                job.queuedSections,
                job.failedChunks
        );
    }

    private static String compactStatusText(Job job) {
        return "chunks="
                + job.totalChunks
                + " depth="
                + job.belowSurfaceBlocks
                + " source_status="
                + job.sourceStatus.getName()
                + " shape="
                + (job.squareArea ? "square" : "circle")
                + " chunks_per_batch="
                + job.chunksPerBatch;
    }

    private static String statusText(Job job) {
        return "processed=" + job.processedChunks
                + "/" + job.totalChunks
                + " queued_sections=" + job.queuedSections
                + " skipped_sections=" + job.skippedSections
                + " skipped_air_sections=" + job.skippedAirSections
                + " skipped_preview_mask_sections=" + job.skippedPreviewMaskSections
                + " cleared_sections=" + job.clearedSections
                + " deferred_clears=" + job.deferredClearSections
                + " skipped_zero_clears=" + job.skippedZeroClearSections
                + " skipped_missing_sky_light=" + job.skippedMissingSkyLightSections
                + " synthetic_sky_light=" + job.syntheticSkyLightSections
                + " exposed_empty_sky_fallback=" + job.exposedEmptySkyFallbackSections
                + " queued_synthetic_sky_light=" + job.queuedSyntheticSkyLightSections
                + " queued_real_light=" + job.queuedRealLightSections
                + " surface_columns_total=" + job.surfaceColumnsTotal
                + " surface_columns_without_retained_voxel=" + job.surfaceColumnsWithoutRetainedVoxel
                + " ocean_floor_columns_without_retained_voxel=" + job.oceanFloorColumnsWithoutRetainedVoxel
                + " chunk_boundary_exposure_misses=" + job.chunkBoundaryExposureMisses
                + " preview_gap_sections=" + job.previewGapSections
                + " preview_open_fluid=" + job.previewOpenFluidCells
                + " sky_lit_open=" + job.skyLitOpenCells
                + " sky_lit_solid_faces=" + job.skyLitSolidFaceCells
                + " sky_exposure_no_light_sections=" + job.skyExposureNoLightSections
                + " depth=" + job.belowSurfaceBlocks
                + " sky_threshold=" + DEFAULT_SKY_LIGHT_THRESHOLD
                + " failed_chunks=" + job.failedChunks
                + " source_status=" + job.sourceStatus.getName()
                + " shape=" + (job.squareArea ? "square" : "circle")
                + " paused=" + job.paused;
    }

    private record ChunkCoord(int x, int z) {
    }

    private record SectionClear(int x, int y, int z, String stage) {
    }

    private record SurfacePreviewColumns(int[] worldSurface, int[] oceanFloor, int[] motionBlocking, int[] fluidDepth) {
    }

    private static final class Job {
        final WorldIdentifier worldId;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final int belowSurfaceBlocks;
        final int aboveSurfaceBlocks;
        final int chunksPerBatch;
        final ChunkStatus sourceStatus;
        final boolean squareArea;
        final int centerChunkX;
        final int centerChunkZ;
        final int radiusChunks;
        final int fullGenerationRadiusChunks;
        final ArrayDeque<ChunkCoord> queue;
        final ArrayDeque<SectionClear> deferredClears = new ArrayDeque<>();
        final int totalChunks;
        final long startedAtMillis = System.currentTimeMillis();
        volatile boolean cancelled;
        volatile boolean paused;
        volatile ServerBossEvent bossBar;
        volatile int processedChunks;
        volatile int queuedSections;
        volatile int skippedSections;
        volatile int skippedAirSections;
        volatile int skippedPreviewMaskSections;
        volatile int clearedSections;
        volatile int deferredClearSections;
        volatile int skippedZeroClearSections;
        volatile int skippedMissingSkyLightSections;
        volatile int syntheticSkyLightSections;
        volatile int exposedEmptySkyFallbackSections;
        volatile int queuedSyntheticSkyLightSections;
        volatile int queuedRealLightSections;
        volatile int failedChunks;
        volatile long lastBackpressureMessageMillis;
        volatile long lastBossBarUpdateMillis;
        volatile long lastProgressOverlayMillis;
        volatile long lastProgressChatMillis;
        volatile long previewOpenFluidCells;
        volatile long skyLitOpenCells;
        volatile long skyLitSolidFaceCells;
        volatile long skyExposureNoLightSections;
        volatile long surfaceColumnsTotal;
        volatile long surfaceColumnsWithoutRetainedVoxel;
        volatile long oceanFloorColumnsWithoutRetainedVoxel;
        volatile long chunkBoundaryExposureMisses;
        volatile long previewGapSections;

        Job(
                WorldIdentifier worldId,
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                int centerChunkX,
                int centerChunkZ,
                int radiusChunks,
                int fullGenerationRadiusChunks,
                int belowSurfaceBlocks,
                int aboveSurfaceBlocks,
                int chunksPerBatch,
                boolean squareArea,
                ChunkStatus sourceStatus
        ) {
            this.worldId = worldId;
            this.dimension = dimension;
            this.belowSurfaceBlocks = belowSurfaceBlocks;
            this.aboveSurfaceBlocks = aboveSurfaceBlocks;
            this.chunksPerBatch = chunksPerBatch;
            this.squareArea = squareArea;
            this.sourceStatus = sourceStatus;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.radiusChunks = radiusChunks;
            this.fullGenerationRadiusChunks = fullGenerationRadiusChunks;
            this.queue = buildQueue(centerChunkX, centerChunkZ, radiusChunks, fullGenerationRadiusChunks, squareArea);
            this.totalChunks = this.queue.size();
        }

        boolean includesGeneratedChunk(int chunkX, int chunkZ) {
            int dx = chunkX - this.centerChunkX;
            int dz = chunkZ - this.centerChunkZ;
            int distSq = dx * dx + dz * dz;
            boolean outside = this.squareArea
                    ? Math.max(Math.abs(dx), Math.abs(dz)) > this.radiusChunks
                    : distSq > this.radiusChunks * this.radiusChunks;
            if (outside) {
                return false;
            }
            return this.fullGenerationRadiusChunks <= 0 || (this.squareArea
                    ? Math.max(Math.abs(dx), Math.abs(dz)) > this.fullGenerationRadiusChunks
                    : distSq > this.fullGenerationRadiusChunks * this.fullGenerationRadiusChunks);
        }

        private static ArrayDeque<ChunkCoord> buildQueue(int centerChunkX, int centerChunkZ, int radiusChunks, int fullGenerationRadiusChunks, boolean squareArea) {
            List<ChunkCoord> coords = new ArrayList<>();
            int radiusSq = radiusChunks * radiusChunks;
            int fullRadiusSq = fullGenerationRadiusChunks * fullGenerationRadiusChunks;
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                    int distSq = dx * dx + dz * dz;
                    boolean outside = squareArea
                            ? Math.max(Math.abs(dx), Math.abs(dz)) > radiusChunks
                            : distSq > radiusSq;
                    boolean insideFullGenerationArea = fullGenerationRadiusChunks > 0 && (squareArea
                            ? Math.max(Math.abs(dx), Math.abs(dz)) <= fullGenerationRadiusChunks
                            : distSq <= fullRadiusSq);
                    if (outside || insideFullGenerationArea) {
                        continue;
                    }
                    coords.add(new ChunkCoord(centerChunkX + dx, centerChunkZ + dz));
                }
            }
            coords.sort(Comparator
                    .comparingInt((ChunkCoord coord) -> {
                        int dx = coord.x - centerChunkX;
                        int dz = coord.z - centerChunkZ;
                        return dx * dx + dz * dz;
                    })
                    .thenComparingInt(coord -> Math.abs(coord.x - centerChunkX) + Math.abs(coord.z - centerChunkZ)));
            return new ArrayDeque<>(coords);
        }
    }
}
