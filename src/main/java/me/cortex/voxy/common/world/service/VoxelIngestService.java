package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class VoxelIngestService {
    private static final boolean ALLOW_SYNTHETIC_PREVIEW_INGEST = Boolean.parseBoolean(
            System.getProperty("voxy.allowSyntheticLightPreviewIngest", "true")
    );
    private static final boolean USE_SURFACE_SKY_FALLBACK_FOR_EMPTY_SKY_LIGHT = Boolean.parseBoolean(
            System.getProperty("voxy.useSurfaceSkyFallbackForEmptySkyLight", "true")
    );
    private static final int SURFACE_SKY_FALLBACK_MIN_SECTION_Y = Integer.getInteger(
            "voxy.surfaceSkyFallbackMinSectionY",
            3
    );
    private static final ThreadLocal<Boolean> IN_INGEST_JOB = ThreadLocal.withInitial(() -> false);

    private final Service service;
    private interface IngestTask {
        void process();

        String describe();
    }

    private record ImmutableSectionSnapshot(
            int x,
            int y,
            int z,
            boolean hasOnlyAir,
            BlockState[] states,
            Holder<Biome>[] biomes,
            byte[] lights,
            VoxelizedSection.LightSourceKind lightSourceKind,
            VoxelizedSection.SourceKind sourceKind,
            VoxelizedSection.Confidence confidence,
            long dataEpoch
    ) {}

    private record IngestVoxelized(WorldEngine world, VoxelizedSection section, String stage) implements IngestTask {
        @Override
        public void process() {
            long key = SectionPos.asLong(this.section.x, this.section.y, this.section.z);
            var service = world.instanceIn == null ? null : world.instanceIn.getIngestService();
            if (this.section.lightSourceKind == VoxelizedSection.LightSourceKind.MISSING_SKY_LIGHT) {
                RenderCorrectnessDiagnostics.ingest(
                        this.stage + "_missing_light_rejected",
                        this.section.x,
                        this.section.y,
                        this.section.z,
                        this.section.lvl0NonAirCount == 0,
                        false,
                        "missing_light_snapshot_never_committed"
                );
                return;
            }

            boolean provisionalPreview = this.section.isProvisional();
            if (provisionalPreview) {
                boolean sectionAir = this.section.lvl0NonAirCount == 0;
                if (!ALLOW_SYNTHETIC_PREVIEW_INGEST) {
                    RenderCorrectnessDiagnostics.ingest(
                            "synthetic_preview_rejected",
                            this.section.x,
                            this.section.y,
                            this.section.z,
                            this.section.lvl0NonAirCount == 0,
                            false,
                            "synthetic_light_preview_not_final_lod"
                    );
                    return;
                }
                boolean hasTrustedLight = service != null && service.trustedLightSections.containsKey(key);
                boolean hadSyntheticPreview = service != null && service.syntheticPreviewSections.containsKey(key);
                if (hasTrustedLight) {
                    RenderCorrectnessDiagnostics.provisionalOverRealRejectedTotal.increment();
                    RenderCorrectnessDiagnostics.previewOverRealRejected.increment();
                    RenderCorrectnessDiagnostics.ingest(
                            "synthetic_preview_skip",
                            this.section.x,
                            this.section.y,
                            this.section.z,
                            this.section.lvl0NonAirCount == 0,
                            false,
                            "trusted_light_already_present"
                    );
                    return;
                }
                if (sectionAir && !hadSyntheticPreview) {
                    RenderCorrectnessDiagnostics.ingest(
                            "synthetic_preview_zero_skip",
                            this.section.x,
                            this.section.y,
                            this.section.z,
                            true,
                            false,
                            "no_prior_preview_to_clear"
                    );
                    return;
                }
                if (service != null) {
                    if (sectionAir) {
                        service.syntheticPreviewSections.remove(key);
                    } else {
                        service.syntheticPreviewSections.put(key, Boolean.TRUE);
                    }
                }
            } else if (service != null && this.section.isTrustedRealData()) {
                service.trustedLightSections.put(key, Boolean.TRUE);
                if (service.syntheticPreviewSections.remove(key) != null) {
                    RenderCorrectnessDiagnostics.previewSupersededByReal.increment();
                }
            }
            world.markActive();
            WorldUpdater.insertUpdate(world, section);
        }

        @Override
        public String describe() {
            return "voxelized_section:"
                    + this.section.x + ","
                    + this.section.y + ","
                    + this.section.z
                    + ",non_air=" + this.section.lvl0NonAirCount
                    + ",light_source=" + this.section.lightSourceKind
                    + ",source=" + this.section.sourceKind
                    + ",confidence=" + this.section.confidence
                    + ",epoch=" + this.section.dataEpoch
                    + ",stage=" + this.stage;
        }
    }
    private final ConcurrentLinkedDeque<IngestTask> ingestQueue = new ConcurrentLinkedDeque<>();
    private final LongAdder enqueuedCount = new LongAdder();
    private final LongAdder processedCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();
    private final AtomicLong maxProcessNanos = new AtomicLong();
    private final AtomicLong dataEpochCounter = new AtomicLong();
    private final ConcurrentHashMap<Long, Boolean> trustedLightSections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> syntheticPreviewSections = new ConcurrentHashMap<>();
    private volatile long currentJobStartNanos = 0L;
    private volatile String currentJobDescription = "";

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createService(
                () -> new Pair<>(this::processJob, () -> {}),
                5000,
                "Ingest service",
                () -> !IN_INGEST_JOB.get()
        );
    }

    private void processJob() {
        var task = this.ingestQueue.poll();
        if (task == null) {
            RenderCorrectnessDiagnostics.ingestLifecycle(
                    "empty_poll",
                    this.ingestQueue.size(),
                    this.processedCount.sum(),
                    this.failedCount.sum(),
                    0L,
                    "service_permit_without_queue_entry"
            );
            return;
        }
        long started = System.nanoTime();
        boolean wasInIngestJob = IN_INGEST_JOB.get();
        IN_INGEST_JOB.set(true);
        this.currentJobStartNanos = started;
        this.currentJobDescription = task.describe();
        RenderCorrectnessDiagnostics.ingestLifecycle(
                "started",
                this.ingestQueue.size(),
                this.processedCount.sum(),
                this.failedCount.sum(),
                0L,
                this.currentJobDescription
        );
        try {
            task.process();
            long duration = Math.max(0L, System.nanoTime() - started);
            this.processedCount.increment();
            this.maxProcessNanos.accumulateAndGet(duration, Math::max);
            if (duration >= 250_000_000L || (this.processedCount.sum() & 0x3FFL) == 0L) {
                RenderCorrectnessDiagnostics.ingestLifecycle(
                        "processed",
                        this.ingestQueue.size(),
                        this.processedCount.sum(),
                        this.failedCount.sum(),
                        duration,
                        duration >= 250_000_000L ? "slow_task" : "periodic"
                );
            } else {
                RenderCorrectnessDiagnostics.ingestProcessedTotal.increment();
            }
        } catch (RuntimeException exception) {
            long duration = Math.max(0L, System.nanoTime() - started);
            this.failedCount.increment();
            RenderCorrectnessDiagnostics.ingestLifecycle(
                    "failed",
                    this.ingestQueue.size(),
                    this.processedCount.sum(),
                    this.failedCount.sum(),
                    duration,
                    exception.getClass().getSimpleName()
            );
            throw exception;
        } finally {
            IN_INGEST_JOB.set(wasInIngestJob);
            this.currentJobStartNanos = 0L;
            this.currentJobDescription = "";
        }
    }

    private static VoxelizedSection buildUpdate(
            WorldEngine world,
            ImmutableSectionSnapshot snapshot,
            VoxelizedSection destination
    ) {
        destination.setPosition(snapshot.x, snapshot.y, snapshot.z);
        destination.setLightSourceKind(snapshot.lightSourceKind);
        destination.setSource(snapshot.sourceKind, snapshot.confidence);
        destination.setDataEpoch(snapshot.dataEpoch);
        if (snapshot.hasOnlyAir && !hasAnyLight(snapshot.lights)) {
            return destination.zero();
        }

        var mapper = world.getMapper();
        long[] data = destination.section;
        int nonAir = 0;
        for (int index = 0; index <= 0xFFF; index++) {
            BlockState state = snapshot.states[index];
            byte light = snapshot.lights[index];
            if (state == null || state.isAir()) {
                data[index] = Mapper.airWithLight(light);
                continue;
            }

            int biomeIndex = Integer.compress(index, 0b1100_1100_1100);
            Holder<Biome> biome = snapshot.biomes[biomeIndex];
            data[index] = Mapper.composeMappingId(light, mapper.getIdForBlockState(state), mapper.getIdForBiome(biome));
            nonAir++;
        }

        destination.lvl0NonAirCount = nonAir;
        WorldVoxilizedSectionMipper.mipSection(destination, mapper);
        return destination;
    }

    private static boolean hasAnyLight(byte[] lights) {
        for (byte light : lights) {
            if (light != 0) {
                return true;
            }
        }
        return false;
    }

    public static VoxelizedSection snapshotAuthoritativeSection(
            WorldEngine world,
            Level level,
            LevelChunkSection section,
            int x,
            int y,
            int z,
            DataLayer blockLight,
            DataLayer skyLight,
            LayerLightSectionStorage.SectionType skySectionType,
            long dataEpoch,
            String stage
    ) {
        if (world == null || !world.isLive()) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, section == null || section.hasOnlyAir(), false, "engine_not_live");
            return null;
        }
        if (level == null || section == null) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, true, false, "missing_level_or_section");
            return null;
        }
        int fallbackSkyLight = fallbackSkyLightFor(skySectionType, section, skyLight, y);
        VoxelizedSection.LightSourceKind lightSourceKind = resolveLightSourceKind(
                level.dimensionType().hasSkyLight(),
                section,
                skyLight,
                skySectionType,
                fallbackSkyLight
        );
        if (lightSourceKind == VoxelizedSection.LightSourceKind.MISSING_SKY_LIGHT) {
            RenderCorrectnessDiagnostics.ingest(stage + "_missing_sky_light_rejected", x, y, z, section.hasOnlyAir(), false, "minecraft_authored_light_required");
            return null;
        }
        try {
            ImmutableSectionSnapshot immutableSnapshot = captureImmutableSection(
                    section,
                    x,
                    y,
                    z,
                    blockLight,
                    skyLight,
                    fallbackSkyLight,
                    lightSourceKind,
                    sourceKindForStage(stage, lightSourceKind),
                    confidenceFor(stage, lightSourceKind),
                    dataEpoch
            );
            return buildUpdate(world, immutableSnapshot, VoxelizedSection.createEmpty()).copy();
        } catch (Exception exception) {
            RenderCorrectnessDiagnostics.ingest(stage + "_snapshot_failed", x, y, z, false, false, exception.getClass().getSimpleName());
            Logger.error("Failed to snapshot authoritative Voxy section", x, y, z, exception);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static ImmutableSectionSnapshot captureImmutableSection(
            LevelChunkSection section,
            int x,
            int y,
            int z,
            DataLayer blockLight,
            DataLayer skyLight,
            int fallbackSkyLight,
            VoxelizedSection.LightSourceKind lightSourceKind,
            VoxelizedSection.SourceKind sourceKind,
            VoxelizedSection.Confidence confidence,
            long dataEpoch
    ) {
        BlockState[] states = new BlockState[4096];
        Holder<Biome>[] biomes = new Holder[64];
        byte[] lights = new byte[4096];
        ILightingSupplier lightingSupplier = getLightingSupplier(section, blockLight, skyLight, fallbackSkyLight);

        for (int by = 0; by < 16; by++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int bx = 0; bx < 16; bx++) {
                    int index = bx | (bz << 4) | (by << 8);
                    states[index] = section.getStates().get(bx, by, bz);
                    lights[index] = lightingSupplier.supply(bx, by, bz);
                }
            }
        }

        for (int by = 0; by < 4; by++) {
            for (int bz = 0; bz < 4; bz++) {
                for (int bx = 0; bx < 4; bx++) {
                    int index = bx | (bz << 2) | (by << 4);
                    biomes[index] = section.getBiomes().get(bx, by, bz);
                }
            }
        }

        return new ImmutableSectionSnapshot(x, y, z, section.hasOnlyAir(), states, biomes, lights, lightSourceKind, sourceKind, confidence, dataEpoch);
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(LevelChunkSection section, DataLayer blockLight, DataLayer skyLight) {
        return getLightingSupplier(section, blockLight, skyLight, -1);
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(LevelChunkSection section, DataLayer blockLight, DataLayer skyLight, int fallbackSkyLight) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = skyLight;
        var bla = blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        boolean syntheticSky = fallbackSkyLight >= 0;
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.max(Math.min(15,sla.get(x, y, z)), syntheticSky ? Math.min(15, fallbackSkyLight) : 0);
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = syntheticSky ? Math.min(15, fallbackSkyLight) : 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.max(Math.min(15,sla.get(x, y, z)), syntheticSky ? Math.min(15, fallbackSkyLight) : 0);
                    return (byte) (sky|(block<<4));
                };
            }
        } else if (syntheticSky) {
            supplier = (x,y,z) -> (byte) Math.min(15, fallbackSkyLight);
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        int i = chunk.getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            i = chunk.getMinSection() - 1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                engine.markActive();
                if (!this.snapshotAndQueue("chunk_empty_column", engine, section, chunk.getPos().x, i, chunk.getPos().z, null, null)) {
                    break;
                }
            }
        }

        if (!gotLighting) {
            return false;
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        i = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = blp.getDataLayerData(pos);
            if (bl != null) {
                bl = bl.copy();
            }

            var skySectionType = lightingProvider.getDebugSectionType(LightLayer.SKY, pos);
            var sl = slp.getDataLayerData(pos);
            if (sl != null) {
                sl = sl.copy();
            }

            var lightSourceKind = resolveLightSourceKind(chunk.getLevel().dimensionType().hasSkyLight(), section, sl, skySectionType, -1);
            if (lightSourceKind == VoxelizedSection.LightSourceKind.MISSING_SKY_LIGHT) {
                RenderCorrectnessDiagnostics.ingest("chunk_load", chunk.getPos().x, i, chunk.getPos().z, section.hasOnlyAir(), false, "missing_sky_light");
                this.rejectMissingSkyLightSurfacePreview(section, chunk.getPos().x, i, chunk.getPos().z);
                continue;
            }
            int fallbackSkyLight = fallbackSkyLightFor(skySectionType, section, sl, i);
            lightSourceKind = resolveLightSourceKind(chunk.getLevel().dimensionType().hasSkyLight(), section, sl, skySectionType, fallbackSkyLight);

            //If its null for either, assume failure to obtain lighting and ignore section
            //if (blNone && slNone) {
            //    continue;
            //}
            engine.markActive();
            if (!this.snapshotAndQueue("chunk_load", engine, section, chunk.getPos().x, i, chunk.getPos().z, bl, sl, fallbackSkyLight, lightSourceKind)) {
                break;
            }
        }
        return true;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean snapshotAndQueue(String stage, WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return this.snapshotAndQueue(stage, engine, section, x, y, z, bl, sl, -1);
    }

    private boolean snapshotAndQueue(String stage, WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl, int fallbackSkyLight) {
        return this.snapshotAndQueue(stage, engine, section, x, y, z, bl, sl, fallbackSkyLight, resolveLightSourceKind(true, section, sl, null, fallbackSkyLight));
    }

    private boolean snapshotAndQueue(String stage, WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl, int fallbackSkyLight, VoxelizedSection.LightSourceKind lightSourceKind) {
        VoxelizedSection snapshot;
        try {
            ImmutableSectionSnapshot immutableSnapshot = captureImmutableSection(
                    section,
                    x,
                    y,
                    z,
                    bl,
                    sl,
                    fallbackSkyLight,
                    lightSourceKind,
                    sourceKindForStage(stage, lightSourceKind),
                    confidenceFor(stage, lightSourceKind),
                    this.dataEpochCounter.incrementAndGet()
            );
            snapshot = buildUpdate(engine, immutableSnapshot, VoxelizedSection.createEmpty()).copy();
        } catch (Exception e) {
            RenderCorrectnessDiagnostics.ingest(stage + "_snapshot_failed", x, y, z, false, false, e.getClass().getSimpleName());
            Logger.error("Failed to snapshot Voxy ingest section", x, y, z, e);
            return false;
        }

        boolean sectionAir = snapshot.lvl0NonAirCount == 0;
        this.ingestQueue.add(new IngestVoxelized(engine, snapshot, stage));
        this.enqueuedCount.increment();
        try {
            this.service.execute();
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, sectionAir, true, "queued");
            return true;
        } catch (Exception e) {
            RenderCorrectnessDiagnostics.ingest(stage + "_execute_failed", x, y, z, sectionAir, false, e.getClass().getSimpleName());
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            return false;
        }
    }

    private static VoxelizedSection.SourceKind sourceKindForStage(String stage, VoxelizedSection.LightSourceKind lightSourceKind) {
        if (stage != null && stage.startsWith("surface_preview")) {
            return lightSourceKind == VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW
                    ? VoxelizedSection.SourceKind.SYNTHETIC_PREVIEW
                    : VoxelizedSection.SourceKind.SURFACE_PREVIEW;
        }
        if (stage != null && stage.contains("clear")) {
            return VoxelizedSection.SourceKind.ZERO_CLEAR;
        }
        return VoxelizedSection.SourceKind.REAL_CHUNK;
    }

    private static VoxelizedSection.Confidence confidenceFor(String stage, VoxelizedSection.LightSourceKind lightSourceKind) {
        if (lightSourceKind == VoxelizedSection.LightSourceKind.MISSING_SKY_LIGHT
                || lightSourceKind == VoxelizedSection.LightSourceKind.UNKNOWN) {
            return VoxelizedSection.Confidence.LOW;
        }
        if (lightSourceKind == VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW) {
            return VoxelizedSection.Confidence.LOW;
        }
        if (stage != null && stage.startsWith("surface_preview")) {
            return VoxelizedSection.isTrustedLight(lightSourceKind)
                    ? VoxelizedSection.Confidence.MEDIUM
                    : VoxelizedSection.Confidence.LOW;
        }
        if (lightSourceKind == VoxelizedSection.LightSourceKind.REAL_LIGHT) {
            return VoxelizedSection.Confidence.HIGH;
        }
        return VoxelizedSection.Confidence.MEDIUM;
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return this.snapshotAndQueue("raw_snapshot", engine, section, x, y, z, bl, sl);
    }

    public boolean rejectMissingSkyLightSurfacePreview(LevelChunkSection section, int x, int y, int z) {
        RenderCorrectnessDiagnostics.ingest(
                "chunk_load_missing_sky_light_surface_preview_rejected",
                x,
                y,
                z,
                section == null || section.hasOnlyAir(),
                false,
                "waiting_for_real_sky_light"
        );
        return false;
    }

    public boolean queueVoxelizedSection(WorldEngine engine, VoxelizedSection section, String stage) {
        if (engine == null || !engine.isLive()) {
            RenderCorrectnessDiagnostics.ingest(stage, section == null ? 0 : section.x, section == null ? 0 : section.y, section == null ? 0 : section.z, true, false, "engine_not_live");
            return false;
        }
        if (section == null) {
            RenderCorrectnessDiagnostics.ingest(stage, 0, 0, 0, true, false, "missing_section");
            return false;
        }
        VoxelizedSection snapshot = section.copy();
        if (snapshot.sourceKind == VoxelizedSection.SourceKind.UNKNOWN) {
            snapshot.setSource(sourceKindForStage(stage, snapshot.lightSourceKind), confidenceFor(stage, snapshot.lightSourceKind));
        }
        if (snapshot.dataEpoch == 0L) {
            snapshot.setDataEpoch(this.dataEpochCounter.incrementAndGet());
        }
        boolean sectionAir = snapshot.lvl0NonAirCount == 0;
        this.ingestQueue.add(new IngestVoxelized(engine, snapshot, stage));
        this.enqueuedCount.increment();
        try {
            this.service.execute();
            RenderCorrectnessDiagnostics.ingest(stage, snapshot.x, snapshot.y, snapshot.z, sectionAir, true, "queued_voxelized");
            return true;
        } catch (Exception e) {
            RenderCorrectnessDiagnostics.ingest(stage + "_execute_failed", snapshot.x, snapshot.y, snapshot.z, sectionAir, false, e.getClass().getSimpleName());
            Logger.error("Executing Voxy voxelized-section ingest failed", e);
            return false;
        }
    }

    public boolean queueZeroSection(WorldEngine engine, int x, int y, int z, String stage) {
        if (engine == null || !engine.isLive()) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, true, false, "engine_not_live");
            return false;
        }
        boolean previewClear = stage != null && stage.startsWith("surface_preview");
        VoxelizedSection zero = VoxelizedSection.createEmpty()
                .setPosition(x, y, z)
                .setSyntheticPreview(previewClear)
                .setLightSourceKind(VoxelizedSection.LightSourceKind.REAL_LIGHT)
                .setSource(VoxelizedSection.SourceKind.ZERO_CLEAR,
                        previewClear
                                ? VoxelizedSection.Confidence.LOW
                                : VoxelizedSection.Confidence.HIGH)
                .setDataEpoch(this.dataEpochCounter.incrementAndGet())
                .zero()
                .copy();
        this.ingestQueue.add(new IngestVoxelized(engine, zero, stage));
        this.enqueuedCount.increment();
        try {
            this.service.execute();
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, true, true, previewClear ? "queued_preview_zero" : "queued_zero");
            return true;
        } catch (Exception e) {
            RenderCorrectnessDiagnostics.ingest(stage + "_execute_failed", x, y, z, true, false, e.getClass().getSimpleName());
            Logger.error("Executing Voxy zero-section ingest failed", e);
            return false;
        }
    }

    public static boolean clearSection(WorldEngine engine, int x, int y, int z, String stage) {
        if (engine == null) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, true, false, "missing_engine");
            return false;
        }
        if (engine.instanceIn == null) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, true, false, "missing_instance");
            return false;
        }
        if (!engine.instanceIn.isIngestEnabled(null)) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, true, false, "ingest_disabled");
            return false;
        }
        return engine.instanceIn.getIngestService().queueZeroSection(engine, x, y, z, stage);
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) {
            RenderCorrectnessDiagnostics.ingest("raw_snapshot", x, y, z, false, false, "missing_world_id");
            return false;
        }
        var engine = id.getOrCreateEngine();
        if (engine == null) {
            RenderCorrectnessDiagnostics.ingest("raw_snapshot", x, y, z, false, false, "missing_engine");
            return false;
        }
        return rawIngest(engine, id, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return rawIngest(engine, null, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return rawIngest(engine, id, section, x, y, z, bl, sl, -1);
    }

    public static boolean rawIngest(WorldEngine engine, WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl, int fallbackSkyLight) {
        if (section == null) {
            RenderCorrectnessDiagnostics.ingest("raw_snapshot", x, y, z, true, false, "missing_section");
            return false;
        }
        if (!shouldIngestSection(section, x, y, z)) {
            RenderCorrectnessDiagnostics.ingest("raw_snapshot", x, y, z, section.hasOnlyAir(), false, "section_filtered");
            return false;
        }
        if (engine == null || engine.instanceIn == null) {
            RenderCorrectnessDiagnostics.ingest("raw_snapshot", x, y, z, section.hasOnlyAir(), false, "missing_instance");
            return false;
        }
        if (!engine.instanceIn.isIngestEnabled(id)) {
            RenderCorrectnessDiagnostics.ingest("raw_snapshot", x, y, z, section.hasOnlyAir(), false, "ingest_disabled");
            return false;
        }
        var lightSourceKind = resolveLightSourceKind(expectsSkyLight(id), section, sl, null, fallbackSkyLight);
        if (lightSourceKind == VoxelizedSection.LightSourceKind.MISSING_SKY_LIGHT) {
            RenderCorrectnessDiagnostics.ingest("raw_snapshot_missing_sky_light_skip", x, y, z, section.hasOnlyAir(), false, "waiting_for_light_data");
            return false;
        }
        return engine.instanceIn.getIngestService().snapshotAndQueue("raw_snapshot", engine, section, x, y, z, bl, sl, fallbackSkyLight, lightSourceKind);
    }

    private static boolean expectsSkyLight(WorldIdentifier id) {
        return id != null && Level.OVERWORLD.equals(id.key);
    }

    private static boolean shouldRejectMissingSkyLight(
            String stage,
            boolean skyExpected,
            LevelChunkSection section,
            int x,
            int y,
            int z,
            DataLayer skyLight,
            LayerLightSectionStorage.SectionType skySectionType,
            int fallbackSkyLight
    ) {
        if (!skyExpected || section == null || section.hasOnlyAir()) {
            return false;
        }
        if (fallbackSkyLight >= 0 || skySectionType == LayerLightSectionStorage.SectionType.LIGHT_ONLY) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, false, false, "valid_default_sky_light");
            return false;
        }
        if (skyLight == null) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, false, false, "missing_sky_light");
            return true;
        }
        if (skyLight.isEmpty()) {
            RenderCorrectnessDiagnostics.ingest(stage, x, y, z, false, false, "valid_empty_sky_light");
            return false;
        }
        return false;
    }

    private static VoxelizedSection.LightSourceKind resolveLightSourceKind(
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

    public static int fallbackSkyLightFor(LayerLightSectionStorage.SectionType skySectionType) {
        return skySectionType == LayerLightSectionStorage.SectionType.LIGHT_ONLY ? 15 : -1;
    }

    public static int fallbackSkyLightFor(
            LayerLightSectionStorage.SectionType skySectionType,
            LevelChunkSection section,
            DataLayer skyLight,
            int sectionY
    ) {
        int fallback = fallbackSkyLightFor(skySectionType);
        if (fallback >= 0) {
            return fallback;
        }
        if (!USE_SURFACE_SKY_FALLBACK_FOR_EMPTY_SKY_LIGHT || section == null || section.hasOnlyAir()) {
            return -1;
        }
        if (sectionY < SURFACE_SKY_FALLBACK_MIN_SECTION_Y) {
            return -1;
        }
        if (skySectionType == LayerLightSectionStorage.SectionType.LIGHT_AND_DATA
                && skyLight != null
                && skyLight.isEmpty()
                && sectionHasLikelySkyExposedSurface(section)) {
            return 15;
        }
        return -1;
    }

    private static boolean sectionHasLikelySkyExposedSurface(LevelChunkSection section) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean openAbove = true;
                for (int y = 15; y >= 0; y--) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (openAbove) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    public long getEnqueuedCount() {
        return this.enqueuedCount.sum();
    }

    public long getProcessedCount() {
        return this.processedCount.sum();
    }

    public long getFailedCount() {
        return this.failedCount.sum();
    }

    public long getMaxProcessNanos() {
        return this.maxProcessNanos.get();
    }

    public long getCurrentJobAgeNanos() {
        long started = this.currentJobStartNanos;
        return started <= 0L ? 0L : Math.max(0L, System.nanoTime() - started);
    }

    public String getCurrentJobDescription() {
        return this.currentJobDescription;
    }

    public int getRawQueueSize() {
        return this.ingestQueue.size();
    }
}
