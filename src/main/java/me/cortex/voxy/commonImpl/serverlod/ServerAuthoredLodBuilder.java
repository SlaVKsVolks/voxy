package me.cortex.voxy.commonImpl.serverlod;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.ServerLodTileSectionStorage;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class ServerAuthoredLodBuilder {
    private static final int DEFAULT_CHUNKS_PER_TICK = Integer.getInteger("voxy.serverLodAuthoredChunksPerTick", 8);
    private static final int SMALL_JOB_PREEMPT_CHUNKS = Integer.getInteger("voxy.serverLodAuthoredSmallPreemptChunks", 256);
    private static final boolean AUTO_BUILD_ENABLED = Boolean.parseBoolean(
            System.getProperty("voxy.serverLodAutoBuildEnabled", "true"));
    private static final int AUTO_BUILD_RADIUS = Math.max(
            0,
            Integer.getInteger("voxy.serverLodAutoBuildRadius", 64));
    private static final int AUTO_BUILD_CHUNKS_PER_TICK = Math.max(
            1,
            Integer.getInteger("voxy.serverLodAutoBuildChunksPerTick", 128));
    private static final ServerLodNeoForgeContract.ContractSnapshot CONTRACT = ServerLodNeoForgeContract.snapshot();
    private static volatile Job activeJob;
    private static volatile Job lastJob;
    private static final Deque<Job> pausedJobs = new ArrayDeque<>();

    private ServerAuthoredLodBuilder() {}

    public static int automaticCoverageRadius(int requestedRadiusChunks) {
        if (!AUTO_BUILD_ENABLED) {
            return 0;
        }
        return Math.min(Math.max(0, requestedRadiusChunks), AUTO_BUILD_RADIUS);
    }

    public static boolean ensureCoverage(ServerPlayer player, int requestedRadiusChunks, String reason) {
        if (!AUTO_BUILD_ENABLED || player == null) {
            return false;
        }
        if (activeJob != null) {
            return false;
        }
        int radiusChunks = automaticCoverageRadius(requestedRadiusChunks);
        if (radiusChunks <= 0) {
            return false;
        }
        CommandSourceStack source = player.createCommandSourceStack().withPermission(4);
        String options = "status=light"
                + " chunksPerTick=" + AUTO_BUILD_CHUNKS_PER_TICK
                + " publishStore=true"
                + " shape=square";
        int centerX = player.getBlockX() >> 4;
        int centerZ = player.getBlockZ() >> 4;
        int code = start(source, centerX, centerZ, radiusChunks, options);
        if (code > 0) {
            Logger.info("Started automatic Minecraft-authored Voxy LoD build around player "
                    + player.getGameProfile().getName()
                    + " radius=" + radiusChunks
                    + " reason=" + (reason == null || reason.isBlank() ? "visible coverage insufficient" : reason));
            return true;
        }
        return false;
    }

    public static int start(CommandSourceStack source, int centerX, int centerZ, int radiusChunks, String rawOptions) {
        Options options;
        try {
            options = Options.parse(rawOptions, source.getLevel());
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        Job current = activeJob;
        int requestedChunks = requestedChunkCount(radiusChunks);
        if (current != null && requestedChunks > SMALL_JOB_PREEMPT_CHUNKS) {
            source.sendFailure(Component.literal("Voxy authored LoD build already running: " + statusLine()));
            return 0;
        }
        if (options.storeRoot != null) {
            ServerLodSyncManager.mountStoreRoot(options.storeRoot);
        }
        ServerLodTileStore store = ServerLodSyncManager.getStore();
        Job job = new Job(source.getLevel(), centerX, centerZ, radiusChunks, options, store);
        String pausedStatus = null;
        synchronized (ServerAuthoredLodBuilder.class) {
            if (activeJob != null) {
                pausedStatus = activeJob.statusLine();
                pausedJobs.addFirst(activeJob);
            }
            activeJob = job;
        }
        ServerLodDiagnostics.authoredChunksRequested.addAndGet(job.totalChunks);
        String message = "Started Minecraft-authored Voxy LoD build: " + job.describe();
        if (pausedStatus != null) {
            message += " paused_previous={" + pausedStatus + "}";
        }
        String finalMessage = message;
        source.sendSuccess(() -> Component.literal(finalMessage), true);
        return 1;
    }

    private static int requestedChunkCount(int radiusChunks) {
        int side = radiusChunks * 2 + 1;
        return side * side;
    }

    public static String statusLine() {
        Job job = activeJob;
        if (job != null) {
            return job.statusLine() + pausedStatusSuffix();
        }
        Job last = lastJob;
        return last == null ? "authored_builder=idle" : "authored_builder=idle last={" + last.statusLine() + "}";
    }

    public static int cancelAll(CommandSourceStack source, String reason) {
        ArrayDeque<Job> jobsToCancel = new ArrayDeque<>();
        synchronized (ServerAuthoredLodBuilder.class) {
            if (activeJob != null) {
                jobsToCancel.add(activeJob);
                activeJob = null;
            }
            jobsToCancel.addAll(pausedJobs);
            pausedJobs.clear();
        }
        if (jobsToCancel.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Voxy authored LoD builder already idle: " + statusLine()), false);
            return 1;
        }
        Job lastCancelled = null;
        for (Job job : jobsToCancel) {
            job.markCancelled(reason);
            job.level.getServer().execute(job::finish);
            lastCancelled = job;
        }
        if (lastCancelled != null) {
            lastJob = lastCancelled;
        }
        int cancelledJobs = jobsToCancel.size();
        source.sendSuccess(() -> Component.literal("Cancelled Voxy authored LoD builder jobs=" + cancelledJobs + " " + statusLine()), true);
        return 1;
    }

    private static String pausedStatusSuffix() {
        synchronized (ServerAuthoredLodBuilder.class) {
            return pausedJobs.isEmpty() ? "" : " paused_jobs=" + pausedJobs.size();
        }
    }

    public static String statusJson() {
        Job job = activeJob;
        if (job != null) {
            return job.statusJson("active");
        }
        Job last = lastJob;
        if (last != null) {
            return last.statusJson("completed");
        }
            return "{"
                + "\"schema\":\"voxy.serverlod.authored_builder_status.v1\","
                + "\"builder_state\":\"idle\","
                + "\"source_mode\":\"minecraft_authored\","
                + "\"server_lod_contract\":" + ServerLodNeoForgeContract.json() + ","
                + "\"paused_jobs\":0,"
                + "\"progress_chunks\":0,"
                + "\"requested_chunks\":0,"
                + "\"generated_chunks\":0,"
                + "\"failed_chunks\":0,"
                + "\"sections\":0,"
                + "\"missing_light\":0,"
                + "\"tiles\":0,"
                + "\"rejected_tiles\":0,"
                + "\"cps\":0.0,"
                + "\"vlcp3_output\":\"\","
                + "\"report_path\":\"\","
                + "\"failure\":\"\""
                + "}";
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Job job = activeJob;
        if (job == null) {
            return;
        }
        if (job.level.getServer() != event.getServer()) {
            return;
        }
        try {
            job.tick();
            if (job.done()) {
                job.finish();
                completeActiveJob(job);
            }
        } catch (RuntimeException exception) {
            job.failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            job.finish();
            completeActiveJob(job);
            Logger.error("Minecraft-authored Voxy LoD build failed", exception);
        }
    }

    private static void completeActiveJob(Job job) {
        synchronized (ServerAuthoredLodBuilder.class) {
            if (activeJob != job) {
                return;
            }
            lastJob = job;
            activeJob = pausedJobs.pollFirst();
            if (activeJob != null) {
                Logger.info("Resuming paused Minecraft-authored Voxy LoD build: " + activeJob.describe());
            }
        }
    }

    private static final class Job {
        private final ServerLevel level;
        private final int centerX;
        private final int centerZ;
        private final int radiusChunks;
        private final Options options;
        private final WorldEngine world;
        private final ServerLodTileSectionStorage storage;
        private final ServerLodTileStore store;
        private final Vlcp3RegionWriter regionWriter;
        private final Path deferredStoreRoot;
        private final boolean deferredVlcp3Write;
        private final String dimension;
        private final int side;
        private final int totalChunks;
        private final long startedMillis = System.currentTimeMillis();
        private long lastProgressReportMillis;
        private long nextEpoch = 1L;
        private int cursor;
        private int chunksGenerated;
        private int lightChunksGenerated;
        private int fullChunksGenerated;
        private int chunksFailed;
        private int sectionsAccepted;
        private int sectionsRejectedMissingLight;
        private int tilesPublished;
        private int tilesRejected;
        private int tilesWrittenToVlcp3;
        private int tilesWrittenToStore;
        private String failure = "";
        private boolean finished;
        private boolean vlcp3Closed;
        private boolean vlcp3Mounted;
        private boolean validationPassed;
        private String validationStatus = "";
        private volatile boolean cancelled;

        private Job(ServerLevel level, int centerX, int centerZ, int radiusChunks, Options options, ServerLodTileStore store) {
            this.level = level;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radiusChunks = radiusChunks;
            this.options = options;
            this.dimension = level.dimension().location().toString();
            this.deferredVlcp3Write = options.vlcp3Path != null
                    && options.deferVlcp3Write
                    && options.publishStore;
            this.deferredStoreRoot = this.deferredVlcp3Write ? deferredStoreRoot(options.vlcp3Path) : null;
            if (this.deferredStoreRoot != null) {
                deleteRecursively(this.deferredStoreRoot);
            }
            this.store = this.deferredVlcp3Write ? new ServerLodTileStore(this.deferredStoreRoot, false) : store;
            this.storage = new ServerLodTileSectionStorage(this.store, this.dimension);
            this.world = new WorldEngine(this.storage);
            this.side = radiusChunks * 2 + 1;
            this.totalChunks = this.side * this.side;
            this.regionWriter = this.deferredVlcp3Write
                    ? null
                    : createRegionWriter(options.vlcp3Path, centerX, centerZ, radiusChunks, this.totalChunks);
        }

        private static Path deferredStoreRoot(Path vlcp3Path) {
            String name = vlcp3Path.getFileName().toString() + ".tmp-store";
            Path parent = vlcp3Path.getParent();
            return parent == null ? Path.of(name) : parent.resolve(name);
        }

        private static Vlcp3RegionWriter createRegionWriter(Path vlcp3Path, int centerX, int centerZ, int radiusChunks, long totalChunks) {
            if (vlcp3Path == null) {
                return null;
            }
            try {
                return new Vlcp3RegionWriter(vlcp3Path, centerX, centerZ, radiusChunks, totalChunks);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to open authored VLCP0003 writer at " + vlcp3Path, exception);
            }
        }

        private void tick() {
            if (this.cancelled) {
                return;
            }
            int processedThisTick = 0;
            while (!done() && processedThisTick < this.options.chunksPerTick) {
                int localX = this.cursor % this.side;
                int localZ = this.cursor / this.side;
                this.cursor++;
                int chunkX = this.centerX - this.radiusChunks + localX;
                int chunkZ = this.centerZ - this.radiusChunks + localZ;
                if (this.options.shapeCircle && !insideCircle(chunkX, chunkZ)) {
                    continue;
                }
                processChunk(chunkX, chunkZ);
                processedThisTick++;
            }
            maybeWriteProgressReport();
        }

        private boolean insideCircle(int chunkX, int chunkZ) {
            long dx = chunkX - this.centerX;
            long dz = chunkZ - this.centerZ;
            long r = this.radiusChunks;
            return dx * dx + dz * dz <= r * r;
        }

        private void processChunk(int chunkX, int chunkZ) {
            Set<Long> changedSections = new HashSet<>();
            try {
                ChunkAccess chunk = this.level.getChunk(chunkX, chunkZ, this.options.status, true);
                if (chunk == null) {
                    this.chunksFailed++;
                    return;
                }
                var lightingProvider = this.level.getLightEngine();
                var blockLightProvider = lightingProvider.getLayerListener(LightLayer.BLOCK);
                var skyLightProvider = lightingProvider.getLayerListener(LightLayer.SKY);
                int sectionY = chunk.getMinSection();
                for (LevelChunkSection section : chunk.getSections()) {
                    if (section == null) {
                        sectionY++;
                        continue;
                    }
                    var sectionPos = net.minecraft.core.SectionPos.of(chunk.getPos(), sectionY);
                    var blockLight = blockLightProvider.getDataLayerData(sectionPos);
                    if (blockLight != null) {
                        blockLight = blockLight.copy();
                    }
                    var skyLight = skyLightProvider.getDataLayerData(sectionPos);
                    if (skyLight != null) {
                        skyLight = skyLight.copy();
                    }
                    LayerLightSectionStorage.SectionType skySectionType = lightingProvider.getDebugSectionType(LightLayer.SKY, sectionPos);
                    VoxelizedSection snapshot = VoxelIngestService.snapshotAuthoritativeSection(
                            this.world,
                            this.level,
                            section,
                            chunkX,
                            sectionY,
                            chunkZ,
                            blockLight,
                            skyLight,
                            skySectionType,
                            this.nextEpoch++,
                            this.options.status == ChunkStatus.FULL ? "server_authored_full" : "server_authored_light"
                    );
                    if (snapshot == null) {
                        this.sectionsRejectedMissingLight++;
                        ServerLodDiagnostics.authoredSectionsRejectedMissingLight.incrementAndGet();
                        sectionY++;
                        continue;
                    }
                    WorldUpdater.insertUpdate(this.world, snapshot);
                    this.sectionsAccepted++;
                    ServerLodDiagnostics.authoredSectionsAccepted.incrementAndGet();
                    addChangedSections(changedSections, snapshot);
                    sectionY++;
                }
                saveChangedSections(changedSections);
                suppressChunkSaveIfRequested(chunk);
                this.chunksGenerated++;
                if (this.options.status == ChunkStatus.FULL) {
                    this.fullChunksGenerated++;
                } else {
                    this.lightChunksGenerated++;
                }
                ServerLodDiagnostics.authoredChunksGenerated.incrementAndGet();
            } catch (RuntimeException exception) {
                this.chunksFailed++;
                Logger.warn("Failed to generate Minecraft-authored Voxy LoD chunk " + chunkX + "," + chunkZ + ": " + exception.getMessage());
            }
        }

        private void suppressChunkSaveIfRequested(ChunkAccess chunk) {
            if (this.options.saveChunks) {
                return;
            }
            try {
                var method = ChunkAccess.class.getMethod("setUnsaved", boolean.class);
                method.invoke(chunk, false);
            } catch (ReflectiveOperationException ignored) {
                // NeoForge/Minecraft mappings can move this method; generation correctness must not depend on save suppression.
            }
        }

        private static void addChangedSections(Set<Long> changedSections, VoxelizedSection section) {
            for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
                changedSections.add(WorldEngine.getWorldSectionId(
                        level,
                        section.x >> (level + 1),
                        section.y >> (level + 1),
                        section.z >> (level + 1)
                ));
            }
        }

        private void saveChangedSections(Set<Long> changedSections) {
            for (long key : changedSections) {
                WorldSection section = this.world.acquireIfExists(key);
                if (section == null) {
                    continue;
                }
                try {
                    if (!hasPositiveCoverage(section)) {
                        section.setNotDirty();
                        continue;
                    }
                    ServerLodTile tile = ServerLodSectionTileCodec.createMinecraftAuthoredTile(
                            this.dimension,
                            section,
                            this.storage.getIdMappingsData()
                    );
                    boolean wroteTile = false;
                    if (this.deferredVlcp3Write) {
                        ServerLodTileStore.StoreResult result = ServerLodGenerationBridge.publishTile(this.store, tile);
                        if (result == ServerLodTileStore.StoreResult.STORED) {
                            this.tilesWrittenToStore++;
                            wroteTile = true;
                        } else {
                            this.tilesRejected++;
                            ServerLodDiagnostics.authoredTilesRejected.incrementAndGet();
                        }
                    } else if (this.regionWriter != null) {
                        this.regionWriter.write(tile);
                        this.tilesWrittenToVlcp3++;
                        wroteTile = true;
                    }
                    if (this.options.publishStore) {
                        ServerLodTileStore.StoreResult result = ServerLodGenerationBridge.publishTile(this.store, tile);
                        if (result == ServerLodTileStore.StoreResult.STORED) {
                            this.tilesWrittenToStore++;
                            wroteTile = true;
                        } else {
                            this.tilesRejected++;
                            ServerLodDiagnostics.authoredTilesRejected.incrementAndGet();
                        }
                    }
                    if (wroteTile) {
                        this.tilesPublished++;
                        ServerLodDiagnostics.authoredTilesPublished.incrementAndGet();
                    }
                    section.setNotDirty();
                } catch (IOException exception) {
                    this.tilesRejected++;
                    ServerLodDiagnostics.authoredTilesRejected.incrementAndGet();
                    Logger.warn("Failed writing authored VLCP0003 tile: " + exception.getMessage());
                } finally {
                    section.release();
                }
            }
        }

        private static boolean hasPositiveCoverage(WorldSection section) {
            if (section.lvl == 0) {
                return section.getNonEmptyBlockCount() > 0;
            }
            return section.getNonEmptyChildren() != 0;
        }

        private boolean done() {
            return this.cancelled || this.cursor >= this.totalChunks;
        }

        private void markCancelled(String reason) {
            this.cancelled = true;
            appendFailure("cancelled:" + (reason == null || reason.isBlank() ? "command" : reason));
        }

        private void finish() {
            if (this.finished) {
                return;
            }
            this.finished = true;
            Logger.info("Finishing Minecraft-authored Voxy LoD build: " + statusLine());
            try {
                this.storage.flush();
            } catch (RuntimeException exception) {
                appendFailure("storage_flush_failed:" + exception.getMessage());
            }
            if (this.regionWriter != null) {
                try {
                    this.regionWriter.close();
                    this.vlcp3Closed = true;
                } catch (IOException exception) {
                    appendFailure("vlcp3_close_failed:" + exception.getMessage());
                    Logger.warn("Failed closing authored VLCP0003 region writer: " + exception.getMessage());
                }
                if (this.vlcp3Closed && !this.cancelled) {
                    try {
                        ServerLodSyncManager.mountVlcp3(this.options.vlcp3Path);
                        this.vlcp3Mounted = true;
                        this.validationStatus = ServerLodSyncManager.validateVisible(4096);
                        this.validationPassed = this.validationStatus.contains("passed=true")
                                && !this.validationStatus.contains("passed=false");
                    } catch (RuntimeException exception) {
                        appendFailure("vlcp3_mount_or_validate_failed:" + exception.getMessage());
                        Logger.warn("Failed mounting/validating authored VLCP0003 region: " + exception.getMessage());
                    }
                }
            }
            if (this.deferredVlcp3Write && this.options.vlcp3Path != null && !this.cancelled) {
                try {
                    this.tilesWrittenToVlcp3 = this.store.exportVlcp3(
                            this.options.vlcp3Path,
                            this.centerX,
                            this.centerZ,
                            this.radiusChunks,
                            this.totalChunks
                    );
                    this.vlcp3Closed = true;
                } catch (IOException exception) {
                    appendFailure("vlcp3_export_failed:" + exception.getMessage());
                    Logger.warn("Failed exporting deduplicated authored VLCP0003 region: " + exception.getMessage());
                }
                if (this.vlcp3Closed) {
                    try {
                        ServerLodSyncManager.mountVlcp3(this.options.vlcp3Path);
                        this.vlcp3Mounted = true;
                        this.validationStatus = ServerLodSyncManager.validateVisible(4096);
                        this.validationPassed = this.validationStatus.contains("passed=true")
                                && !this.validationStatus.contains("passed=false");
                    } catch (RuntimeException exception) {
                        appendFailure("vlcp3_mount_or_validate_failed:" + exception.getMessage());
                        Logger.warn("Failed mounting/validating authored VLCP0003 region: " + exception.getMessage());
                    }
                }
                deleteRecursively(this.deferredStoreRoot);
            }
            writeReport();
            if (!this.cancelled && this.tilesPublished > 0) {
                ServerLodSyncManager.broadcastManifests("authored build finished");
            }
            Logger.info("Finished Minecraft-authored Voxy LoD build: " + statusLine() + " failure=" + this.failure);
        }

        private void appendFailure(String message) {
            if (message == null || message.isBlank()) {
                return;
            }
            if (this.failure == null || this.failure.isBlank()) {
                this.failure = message;
            } else {
                this.failure += "; " + message;
            }
        }

        private void writeReport() {
            Path report = this.options.reportPath == null
                    ? this.storageRootReportFallback()
                    : this.options.reportPath;
            writeReportTo(report);
        }

        private void maybeWriteProgressReport() {
            long now = System.currentTimeMillis();
            if (now - this.lastProgressReportMillis < 10_000L) {
                return;
            }
            this.lastProgressReportMillis = now;
            writeReportTo(progressReportPath());
        }

        private Path progressReportPath() {
            Path finalReport = this.options.reportPath == null
                    ? this.storageRootReportFallback()
                    : this.options.reportPath;
            Path parent = finalReport.getParent();
            String name = finalReport.getFileName() == null
                    ? "authored_lod_build_progress.json"
                    : finalReport.getFileName().toString().replace(".json", "_progress.json");
            return parent == null ? Path.of(name) : parent.resolve(name);
        }

        private void writeReportTo(Path report) {
            try {
                Path parent = report.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(report, this.reportJson(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                Logger.warn("Failed writing Voxy authored LoD build report: " + exception.getMessage());
            }
        }

        private Path storageRootReportFallback() {
            return ServerLodSyncManager.getStore().root().resolve("authored_lod_build_report.json");
        }

        private String reportJson() {
            long elapsedMillis = Math.max(1L, System.currentTimeMillis() - this.startedMillis);
            return "{\n"
                    + "  \"schema\": \"voxy.serverlod.authored_build.v1\",\n"
                    + "  \"source_mode\": \"minecraft_authored\",\n"
                    + "  \"server_lod_contract\": " + CONTRACT.toJson() + ",\n"
                    + "  \"dimension\": \"" + escape(this.dimension) + "\",\n"
                    + "  \"status\": \"" + escape(this.options.status.toString()) + "\",\n"
                    + "  \"chunk_status_target\": \"" + chunkStatusTarget(this.options.status) + "\",\n"
                    + "  \"save_chunks\": " + this.options.saveChunks + ",\n"
                    + "  \"shape\": \"" + (this.options.shapeCircle ? "circle" : "square") + "\",\n"
                    + "  \"center_x\": " + this.centerX + ",\n"
                    + "  \"center_z\": " + this.centerZ + ",\n"
                    + "  \"radius_chunks\": " + this.radiusChunks + ",\n"
                    + "  \"target_chunks\": " + this.totalChunks + ",\n"
                    + "  \"processed_chunks\": " + this.cursor + ",\n"
                    + "  \"generated_chunks\": " + this.chunksGenerated + ",\n"
                    + "  \"light_chunks_generated\": " + this.lightChunksGenerated + ",\n"
                    + "  \"full_chunks_generated\": " + this.fullChunksGenerated + ",\n"
                    + "  \"failed_chunks\": " + this.chunksFailed + ",\n"
                    + "  \"sections_accepted\": " + this.sectionsAccepted + ",\n"
                    + "  \"sections_rejected_missing_light\": " + this.sectionsRejectedMissingLight + ",\n"
                    + "  \"tiles_published\": " + this.tilesPublished + ",\n"
                    + "  \"tiles_written_to_vlcp3\": " + this.tilesWrittenToVlcp3 + ",\n"
                    + "  \"tiles_written_to_store\": " + this.tilesWrittenToStore + ",\n"
                    + "  \"vlcp3_deferred_write\": " + this.deferredVlcp3Write + ",\n"
                    + "  \"vlcp3_deferred_store\": \"" + escape(this.deferredStoreRoot == null ? "" : this.deferredStoreRoot.toString()) + "\",\n"
                    + "  \"tiles_rejected\": " + this.tilesRejected + ",\n"
                    + "  \"synthetic_tiles_written\": 0,\n"
                    + "  \"publish_store\": " + this.options.publishStore + ",\n"
                    + "  \"cancelled\": " + this.cancelled + ",\n"
                    + "  \"vlcp3_output\": \"" + escape(this.options.vlcp3Path == null ? "" : this.options.vlcp3Path.toString()) + "\",\n"
                    + "  \"vlcp3_tiles\": " + this.tilesWrittenToVlcp3 + ",\n"
                    + "  \"vlcp3_closed\": " + this.vlcp3Closed + ",\n"
                    + "  \"vlcp3_mounted\": " + this.vlcp3Mounted + ",\n"
                    + "  \"validation_passed\": " + this.validationPassed + ",\n"
                    + "  \"validation_status\": \"" + escape(this.validationStatus) + "\",\n"
                    + "  \"elapsed_seconds\": " + (elapsedMillis / 1000.0D) + ",\n"
                    + "  \"chunks_per_sec\": " + (this.chunksGenerated * 1000.0D / elapsedMillis) + ",\n"
                    + "  \"failure\": \"" + escape(this.failure) + "\"\n"
                    + "}\n";
        }

        private String describe() {
            return "source_mode=minecraft_authored dimension=" + this.dimension
                    + " contract=" + CONTRACT.schema()
                    + " center=" + this.centerX + "," + this.centerZ
                    + " radius=" + this.radiusChunks
                    + " chunks=" + this.totalChunks
                    + " chunk_status_target=" + chunkStatusTarget(this.options.status)
                    + " save_chunks=" + this.options.saveChunks
                    + " chunks_per_tick=" + this.options.chunksPerTick;
        }

        private String statusLine() {
            long elapsedMillis = Math.max(1L, System.currentTimeMillis() - this.startedMillis);
            String state = this.cancelled ? "cancelled" : this.finished ? "completed" : "running";
            return "authored_builder=" + state
                    + " source_mode=minecraft_authored"
                    + " contract_pass=" + CONTRACT.pass()
                    + " progress=" + this.cursor + "/" + this.totalChunks
                    + " generated=" + this.chunksGenerated
                    + " light_generated=" + this.lightChunksGenerated
                    + " full_generated=" + this.fullChunksGenerated
                    + " failed=" + this.chunksFailed
                    + " sections=" + this.sectionsAccepted
                    + " missing_light=" + this.sectionsRejectedMissingLight
                    + " tiles=" + this.tilesPublished
                    + " vlcp3_tiles=" + this.tilesWrittenToVlcp3
                    + " store_tiles=" + this.tilesWrittenToStore
                    + " rejected_tiles=" + this.tilesRejected
                    + " cps=" + String.format(Locale.ROOT, "%.1f", this.chunksGenerated * 1000.0D / elapsedMillis);
        }

        private String statusJson(String state) {
            long elapsedMillis = Math.max(1L, System.currentTimeMillis() - this.startedMillis);
            String reportPath = this.options.reportPath == null
                    ? this.storageRootReportFallback().toString()
                    : this.options.reportPath.toString();
            return "{"
                    + "\"schema\":\"voxy.serverlod.authored_builder_status.v1\","
                    + "\"builder_state\":\"" + escape(state) + "\","
                    + "\"source_mode\":\"minecraft_authored\","
                    + "\"server_lod_contract\":" + CONTRACT.toJson() + ","
                    + "\"paused_jobs\":" + pausedJobCount() + ","
                    + "\"dimension\":\"" + escape(this.dimension) + "\","
                    + "\"chunk_status_target\":\"" + chunkStatusTarget(this.options.status) + "\","
                    + "\"center_x\":" + this.centerX + ","
                    + "\"center_z\":" + this.centerZ + ","
                    + "\"radius_chunks\":" + this.radiusChunks + ","
                    + "\"progress_chunks\":" + this.cursor + ","
                    + "\"requested_chunks\":" + this.totalChunks + ","
                    + "\"generated_chunks\":" + this.chunksGenerated + ","
                    + "\"light_chunks_generated\":" + this.lightChunksGenerated + ","
                    + "\"full_chunks_generated\":" + this.fullChunksGenerated + ","
                    + "\"failed_chunks\":" + this.chunksFailed + ","
                    + "\"sections\":" + this.sectionsAccepted + ","
                    + "\"missing_light\":" + this.sectionsRejectedMissingLight + ","
                    + "\"tiles\":" + this.tilesPublished + ","
                    + "\"vlcp3_tiles\":" + this.tilesWrittenToVlcp3 + ","
                    + "\"store_tiles\":" + this.tilesWrittenToStore + ","
                    + "\"rejected_tiles\":" + this.tilesRejected + ","
                    + "\"cps\":" + String.format(Locale.ROOT, "%.3f", this.chunksGenerated * 1000.0D / elapsedMillis) + ","
                    + "\"publish_store\":" + this.options.publishStore + ","
                    + "\"cancelled\":" + this.cancelled + ","
                    + "\"vlcp3_output\":\"" + escape(this.options.vlcp3Path == null ? "" : this.options.vlcp3Path.toString()) + "\","
                    + "\"report_path\":\"" + escape(reportPath) + "\","
                    + "\"vlcp3_closed\":" + this.vlcp3Closed + ","
                    + "\"vlcp3_mounted\":" + this.vlcp3Mounted + ","
                    + "\"validation_passed\":" + this.validationPassed + ","
                    + "\"failure\":\"" + escape(this.failure) + "\""
                    + "}";
        }
    }

    private record Options(
            ChunkStatus status,
            boolean shapeCircle,
            boolean saveChunks,
            int chunksPerTick,
            Path storeRoot,
            Path vlcp3Path,
            Path reportPath,
            boolean publishStore,
            boolean deferVlcp3Write
    ) {
        private static Options parse(String rawOptions, ServerLevel level) {
            ChunkStatus status = ChunkStatus.LIGHT;
            boolean shapeCircle = false;
            boolean saveChunks = false;
            int chunksPerTick = DEFAULT_CHUNKS_PER_TICK;
            boolean deferVlcp3Write = Boolean.parseBoolean(System.getProperty("voxy.serverLodAuthoredVlcp3DeferWrite", "true"));
            Path storeRoot = null;
            Path vlcp3Path = null;
            Path reportPath = null;
            Boolean publishStore = null;
            if (rawOptions != null && !rawOptions.isBlank()) {
                for (var entry : ServerLodOptions.parse(rawOptions).entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                switch (key) {
                        case "status" -> status = parseStatus(value);
                        case "shape" -> shapeCircle = value.equalsIgnoreCase("circle");
                        case "savechunks" -> saveChunks = Boolean.parseBoolean(value);
                        case "pertick", "chunkspertick" -> chunksPerTick = Math.max(1, Integer.parseInt(value));
                        case "output" -> {
                            Path path = resolvePath(level, value);
                            if (path.getFileName().toString().endsWith(".vlcp3")) {
                                vlcp3Path = path;
                            } else {
                                storeRoot = path;
                            }
                        }
                        case "storeroot" -> storeRoot = resolvePath(level, value);
                        case "vlcp3" -> vlcp3Path = resolvePath(level, value);
                        case "report" -> reportPath = resolvePath(level, value);
                        case "publishstore", "publish_store" -> publishStore = Boolean.parseBoolean(value);
                        case "defervlcp3", "defer_vlcp3", "defer_vlcp3_write" -> deferVlcp3Write = Boolean.parseBoolean(value);
                        default -> throw new IllegalArgumentException("Unknown build-authored option '" + key + "'");
                    }
                }
            }
            boolean effectivePublishStore = publishStore != null
                    ? publishStore
                    : vlcp3Path == null;
            return new Options(status, shapeCircle, saveChunks, chunksPerTick, storeRoot, vlcp3Path, reportPath, effectivePublishStore, deferVlcp3Write);
        }

        private static ChunkStatus parseStatus(String value) {
            if (value == null || value.isBlank()) {
                return ChunkStatus.LIGHT;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "full" -> ChunkStatus.FULL;
                case "light" -> ChunkStatus.LIGHT;
                default -> throw new IllegalArgumentException("build-authored status must be light or full; got '" + value + "'");
            };
        }

        private static Path resolvePath(ServerLevel level, String value) {
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                return path.normalize();
            }
            return level.getServer().getWorldPath(LevelResource.ROOT).resolve(path).toAbsolutePath().normalize();
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException exception) {
            Logger.warn("Failed deleting temporary authored VLCP0003 store " + path + ": " + exception.getMessage());
        }
    }

    private static int pausedJobCount() {
        synchronized (ServerAuthoredLodBuilder.class) {
            return pausedJobs.size();
        }
    }

    private static String chunkStatusTarget(ChunkStatus status) {
        return status == ChunkStatus.FULL ? "full" : "light";
    }
}
