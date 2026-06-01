package me.cortex.voxy.commonImpl.serverlod;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.CompactVlcpSectionStorage;
import me.cortex.voxy.common.world.WorldEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ServerLodTileStore implements AutoCloseable {
    private final Path root;
    private final Path blobDir;
    private final Path indexDir;
    private final Vlcp3RegionStore vlcp3RegionStore;
    private final CompactVlcpSectionStorage compactStorage;
    private final WorldEngine compactWorld;
    private final String compactDimension;
    private final ConcurrentMap<String, ServerLodTileMetadata> index = new ConcurrentHashMap<>();

    public ServerLodTileStore(Path root) {
        this(root, true);
    }

    public ServerLodTileStore(Path root, boolean mountExternalSources) {
        this.root = root;
        this.blobDir = root.resolve("blobs");
        this.indexDir = root.resolve("index");
        try {
            Files.createDirectories(this.blobDir);
            Files.createDirectories(this.indexDir);
            this.loadIndex();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Voxy server LoD store at " + root, e);
        }
        Path vlcp3Path = mountExternalSources ? vlcp3Path(root) : null;
        if (vlcp3Path != null && Files.isRegularFile(vlcp3Path)) {
            this.vlcp3RegionStore = new Vlcp3RegionStore(vlcp3Path, System.getProperty("voxy.serverLodVlcp3Dimension", "minecraft:overworld"));
        } else {
            this.vlcp3RegionStore = null;
        }
        Path compactPath = mountExternalSources ? compactPath(root) : null;
        if (compactPath != null && Files.isRegularFile(compactPath)) {
            this.compactStorage = new CompactVlcpSectionStorage(compactPath);
            this.compactWorld = new WorldEngine(this.compactStorage);
            this.compactDimension = System.getProperty("voxy.serverLodCompactDimension", "minecraft:overworld");
            Logger.info("Mounted Voxy server compact VLCP source: " + compactPath);
        } else {
            this.compactStorage = null;
            this.compactWorld = null;
            this.compactDimension = "minecraft:overworld";
        }
    }

    public Path root() {
        return this.root;
    }

    public int indexedTileCount() {
        return this.index.size() + (this.vlcp3RegionStore == null ? 0 : this.vlcp3RegionStore.tileCount());
    }

    public boolean has(ServerLodTileMetadata metadata) {
        var current = this.index.get(metadata.key().stableId());
        if (current == null || !current.contentHash().equals(metadata.contentHash()) || !canAdvertise(current)) {
            return false;
        }
        var blob = this.blobDir.resolve(current.contentHash() + ".vlctile");
        try {
            return Files.isRegularFile(blob) && Files.size(blob) > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    public String validateVisible(int limit) {
        this.refreshIndex();
        int checked = 0;
        int unreadable = 0;
        int emptyAdvertised = 0;
        int syntheticAdvertised = 0;
        int missingLightTrusted = 0;
        int[] perLevelTiles = new int[5];
        for (var metadata : priorityManifest(System.getProperty("voxy.serverLodVlcp3Dimension", "minecraft:overworld"), 0, 0, limit)) {
            checked++;
            int level = metadata.key().lodLevel();
            if (level >= 0 && level < perLevelTiles.length) {
                perLevelTiles[level]++;
            }
            if (ServerLodTileIntegrity.isEmptyAdvertised(metadata)) {
                emptyAdvertised++;
            }
            if (ServerLodTileIntegrity.isSyntheticAdvertised(metadata)) {
                syntheticAdvertised++;
            }
            if (ServerLodTileIntegrity.hasMissingLightTrustedViolation(metadata)) {
                missingLightTrusted++;
            }
            if (read(metadata.key()).isEmpty()) {
                unreadable++;
            }
        }
        var vlcp3Report = this.vlcp3RegionStore == null ? null : this.vlcp3RegionStore.validateVisible(limit);
        boolean firstPriorityReadable = checked > 0 && unreadable == 0;
        boolean passed = firstPriorityReadable
                && emptyAdvertised == 0
                && syntheticAdvertised == 0
                && missingLightTrusted == 0
                && (vlcp3Report == null || vlcp3Report.passed());
        String vlcp3 = vlcp3Report == null ? "vlcp3=not_mounted" : "vlcp3={" + vlcp3Report.statusLine() + "}";
        return "mounted_path=" + this.root.toAbsolutePath().normalize()
                + " " + vlcp3
                + " priority_tiles_checked=" + checked
                + " first_" + limit + "_priority_readable=" + firstPriorityReadable
                + " per_level=[" + perLevelTiles[0] + "," + perLevelTiles[1] + "," + perLevelTiles[2] + "," + perLevelTiles[3] + "," + perLevelTiles[4] + "]"
                + " unreadable=" + unreadable
                + " empty_advertised=" + emptyAdvertised
                + " synthetic_advertised=" + syntheticAdvertised
                + " missing_light_trusted=" + missingLightTrusted
                + " passed=" + passed;
    }

    public String validateMountedVlcp3(int limit) {
        if (this.vlcp3RegionStore == null) {
            return "vlcp3=not_mounted passed=false";
        }
        return this.vlcp3RegionStore.validateVisible(limit).statusLine();
    }

    public List<ServerLodTileMetadata> manifest(int limit) {
        this.refreshIndex();
        var out = new ArrayList<ServerLodTileMetadata>(Math.min(Math.max(0, limit), this.index.size() + 4096));
        this.index.values().stream()
                .filter(ServerLodTileStore::canAdvertise)
                .sorted(Comparator.comparing(metadata -> metadata.key().stableId()))
                .limit(limit)
                .forEach(out::add);
        if (out.size() < limit && this.vlcp3RegionStore != null) {
            out.addAll(this.vlcp3RegionStore.manifest(limit - out.size()));
        }
        if (out.size() < limit) {
            appendCompactManifest(out, limit - out.size(), null, 0, 0);
        }
        return out;
    }

    public List<ServerLodTileMetadata> priorityManifest(String dimension, int playerBlockX, int playerBlockZ, int limit) {
        this.refreshIndex();
        var out = new ArrayList<ServerLodTileMetadata>(Math.min(Math.max(0, limit), this.index.size() + 4096));
        this.index.values().stream()
                .filter(ServerLodTileStore::canAdvertise)
                .sorted(Comparator
                        .comparing((ServerLodTileMetadata metadata) -> !metadata.key().dimension().equals(dimension))
                        .thenComparing((ServerLodTileMetadata metadata) -> metadata.key().lodLevel(), Comparator.reverseOrder())
                        .thenComparingLong(metadata -> distanceScore(metadata.key(), playerBlockX, playerBlockZ)))
                .limit(limit)
                .forEach(out::add);
        if (out.size() < limit && this.vlcp3RegionStore != null) {
            this.vlcp3RegionStore.priorityManifest(dimension, playerBlockX, playerBlockZ, limit - out.size()).stream()
                    .forEach(out::add);
        }
        if (out.size() < limit) {
            appendCompactManifest(out, limit - out.size(), dimension, playerBlockX, playerBlockZ);
        }
        return out;
    }

    public int maxAdvertisedChunkReach(String dimension, int playerBlockX, int playerBlockZ) {
        this.refreshIndex();
        int playerChunkX = Math.floorDiv(playerBlockX, 16);
        int playerChunkZ = Math.floorDiv(playerBlockZ, 16);
        int maxReach = 0;
        for (ServerLodTileMetadata metadata : this.index.values()) {
            if (!canAdvertise(metadata) || !metadata.key().dimension().equals(dimension)) {
                continue;
            }
            int sectionSizeChunks = 2 << metadata.key().lodLevel();
            int minChunkX = metadata.key().sectionX() * sectionSizeChunks;
            int maxChunkX = minChunkX + sectionSizeChunks - 1;
            int minChunkZ = metadata.key().sectionZ() * sectionSizeChunks;
            int maxChunkZ = minChunkZ + sectionSizeChunks - 1;
            int reach = Math.max(
                    Math.max(Math.abs(minChunkX - playerChunkX), Math.abs(maxChunkX - playerChunkX)),
                    Math.max(Math.abs(minChunkZ - playerChunkZ), Math.abs(maxChunkZ - playerChunkZ)));
            maxReach = Math.max(maxReach, reach);
        }
        return maxReach;
    }

    public Optional<ServerLodTile> read(ServerLodTileKey key) {
        var metadata = this.index.get(key.stableId());
        if (metadata == null) {
            if (this.vlcp3RegionStore != null) {
                var tile = this.vlcp3RegionStore.read(key);
                if (tile.isPresent()) {
                    return tile;
                }
            }
            // The render path can issue many legitimate misses. The cache index
            // is loaded at construction and updated on store(), so a miss must
            // not rescan the whole cache and block Voxy worker threads.
        }
        if (metadata == null) {
            return readCompact(key);
        }
        if (!canAdvertise(metadata)) {
            return Optional.empty();
        }
        var blob = this.blobDir.resolve(metadata.contentHash() + ".vlctile");
        if (!Files.isRegularFile(blob)) {
            return Optional.empty();
        }
        try {
            byte[] payload = Files.readAllBytes(blob);
            String invalidReason = ServerLodTileIntegrity.validateStoredTile(metadata, payload);
            if (!invalidReason.isEmpty()) {
                Logger.warn("Ignoring invalid Voxy server LoD tile " + key.stableId() + ": " + invalidReason);
                return Optional.empty();
            }
            String hash = ServerLodConstants.sha256Hex(payload);
            if (!hash.equals(metadata.contentHash())) {
                Logger.warn("Ignoring Voxy server LoD tile " + key.stableId() + ": content hash mismatch");
                return Optional.empty();
            }
            return Optional.of(new ServerLodTile(metadata, payload));
        } catch (IOException e) {
            Logger.error("Failed to read Voxy server LoD tile " + key.stableId() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public boolean remove(ServerLodTileKey key) {
        this.index.remove(key.stableId());
        boolean removed = false;
        try {
            removed |= Files.deleteIfExists(this.indexPath(key));
        } catch (IOException e) {
            Logger.warn("Failed to remove Voxy server LoD tile " + key.stableId() + ": " + e.getMessage());
        }
        return removed;
    }

    public int localIndexedTileCount() {
        return this.index.size();
    }

    public synchronized void refreshIndex() {
        try {
            this.loadIndex();
        } catch (IOException e) {
            Logger.warn("Failed refreshing Voxy server LoD index at " + this.indexDir + ": " + e.getMessage());
        }
    }

    private Optional<ServerLodTile> readCompact(ServerLodTileKey key) {
        if (this.compactWorld == null || !key.dimension().equals(this.compactDimension) || key.lodLevel() > WorldEngine.MAX_LOD_LAYER) {
            return Optional.empty();
        }
        var section = this.compactWorld.acquireIfExists(key.lodLevel(), key.sectionX(), key.sectionY(), key.sectionZ());
        if (section == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ServerLodSectionTileCodec.createTile(this.compactDimension, section, this.compactStorage.getIdMappingsData()));
        } finally {
            section.release();
        }
    }

    public StoreResult store(ServerLodTileMetadata metadata, byte[] compressedPayload) {
        String invalidReason = ServerLodTileIntegrity.validateStoredTile(metadata, compressedPayload);
        if (!invalidReason.isEmpty()) {
            ServerLodDiagnostics.provisionalUploadsRejected.incrementAndGet();
            return ServerLodTileIntegrity.isEmptyAdvertised(metadata) || ServerLodTileIntegrity.isEmptyPayload(compressedPayload)
                    ? StoreResult.REJECTED_EMPTY_TILE
                    : StoreResult.REJECTED_UNTRUSTED_LIGHT;
        }
        var current = this.index.get(metadata.key().stableId());
        if (current != null && !metadata.canReplace(current)) {
            if (metadata.epoch() < current.epoch()) {
                ServerLodDiagnostics.staleEpochRejected.incrementAndGet();
                return StoreResult.REJECTED_STALE_EPOCH;
            }
            ServerLodDiagnostics.lowerConfidenceRejected.incrementAndGet();
            return StoreResult.REJECTED_LOWER_AUTHORITY;
        }
        if (metadata.lightKind() == ServerLodLightKind.MISSING && metadata.sourceKind().isReal()) {
            ServerLodDiagnostics.lowerConfidenceRejected.incrementAndGet();
            return StoreResult.REJECTED_MISSING_LIGHT;
        }
        var hash = ServerLodConstants.sha256Hex(compressedPayload);
        if (!hash.equals(metadata.contentHash())) {
            ServerLodDiagnostics.provisionalUploadsRejected.incrementAndGet();
            return StoreResult.REJECTED_HASH_MISMATCH;
        }
        try {
            Files.createDirectories(this.blobDir);
            Files.createDirectories(this.indexDir);
            var blob = this.blobDir.resolve(hash + ".vlctile");
            if (!Files.exists(blob)) {
                Files.write(blob, compressedPayload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            Files.writeString(this.indexPath(metadata.key()), encode(metadata), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            this.index.put(metadata.key().stableId(), metadata);
            ServerLodDiagnostics.serverLodTilesStored.incrementAndGet();
            if (current != null && metadata.sourceKind().isReal() && !current.sourceKind().isReal()) {
                ServerLodDiagnostics.realOverPreviewReplacements.incrementAndGet();
                ServerLodSyncManager.broadcastInvalidation(List.of(metadata.key()), "real tile replaced provisional tile");
            }
            return StoreResult.STORED;
        } catch (IOException e) {
            Logger.error("Failed to store Voxy server LoD tile " + metadata.key().stableId() + ": " + e.getMessage());
            return StoreResult.REJECTED_IO_ERROR;
        }
    }

    @Override
    public void close() {
        if (this.vlcp3RegionStore != null) {
            this.vlcp3RegionStore.close();
        }
        if (this.compactWorld != null) {
            try {
                this.compactWorld.free();
            } catch (RuntimeException exception) {
                Logger.warn("Failed to close compact Voxy world for server LoD store " + this.root + ": " + exception.getMessage());
            }
        } else if (this.compactStorage != null) {
            this.compactStorage.close();
        }
    }

    public int exportVlcp3(Path output, int centerX, int centerZ, int radiusChunks, long totalChunks) throws IOException {
        this.refreshIndex();
        int written = 0;
        try (var writer = new Vlcp3RegionWriter(output, centerX, centerZ, radiusChunks, totalChunks)) {
            for (var metadata : this.index.values().stream()
                    .filter(ServerLodTileStore::canAdvertise)
                    .sorted(Comparator
                            .comparing((ServerLodTileMetadata value) -> value.key().lodLevel())
                            .thenComparing(value -> value.key().stableId()))
                    .toList()) {
                var tile = this.read(metadata.key());
                if (tile.isEmpty()) {
                    continue;
                }
                writer.write(tile.get());
                written++;
            }
        }
        return written;
    }

    private void loadIndex() throws IOException {
        if (!Files.isDirectory(this.indexDir)) {
            return;
        }
        try (var stream = Files.list(this.indexDir)) {
            for (var path : stream.filter(Files::isRegularFile).toList()) {
                try {
                    var metadata = decode(Files.readString(path, StandardCharsets.UTF_8));
                    this.index.put(metadata.key().stableId(), metadata);
                } catch (RuntimeException e) {
                    Logger.warn("Ignoring invalid Voxy server LoD index file " + path + ": " + e.getMessage());
                }
            }
        }
    }

    private void appendCompactManifest(List<ServerLodTileMetadata> out, int limit, String dimension, int playerBlockX, int playerBlockZ) {
        if (!ServerLodTileIntegrity.allowSyntheticPreviewManifest()) {
            return;
        }
        if (this.compactStorage == null || limit <= 0) {
            return;
        }
        int candidateLimit = Math.max(limit * 128, 16_384);
        var candidates = new ArrayList<ServerLodTileMetadata>(Math.min(candidateLimit, 65_536));
        for (int level = WorldEngine.MAX_LOD_LAYER; level >= 0 && candidates.size() < candidateLimit; level--) {
            this.compactStorage.iteratePositions(level, pos -> {
                if (candidates.size() >= candidateLimit) {
                    return;
                }
                if (WorldEngine.getY(pos) < 0) {
                    return;
                }
                var key = new ServerLodTileKey(
                        this.compactDimension,
                        WorldEngine.getLevel(pos),
                        WorldEngine.getX(pos),
                        WorldEngine.getY(pos),
                        WorldEngine.getZ(pos),
                        2);
                candidates.add(compactMetadata(key));
            });
        }
        candidates.stream()
                .sorted(Comparator
                        .comparing((ServerLodTileMetadata metadata) -> dimension != null && !metadata.key().dimension().equals(dimension))
                        .thenComparing((ServerLodTileMetadata metadata) -> metadata.key().lodLevel(), Comparator.reverseOrder())
                        .thenComparing((ServerLodTileMetadata metadata) -> metadata.key().sectionY(), Comparator.reverseOrder())
                        .thenComparingLong(metadata -> dimension == null ? 0L : distanceScore(metadata.key(), playerBlockX, playerBlockZ)))
                .limit(limit)
                .forEach(out::add);
    }

    private static ServerLodTileMetadata compactMetadata(ServerLodTileKey key) {
        return new ServerLodTileMetadata(
                key,
                1L,
                "compact:" + key.safeFileName(),
                ServerLodSourceKind.SERVER_SURFACE_PREVIEW,
                ServerLodConfidence.MEDIUM,
                ServerLodLightKind.SYNTHETIC_SURFACE,
                true,
                true,
                0);
    }

    private static boolean canAdvertise(ServerLodTileMetadata metadata) {
        return ServerLodTileIntegrity.canAdvertise(metadata);
    }

    private static long distanceScore(ServerLodTileKey key, int playerBlockX, int playerBlockZ) {
        int sectionSize = 32 << key.lodLevel();
        long centerX = (long) key.sectionX() * sectionSize + sectionSize / 2L;
        long centerZ = (long) key.sectionZ() * sectionSize + sectionSize / 2L;
        long dx = centerX - playerBlockX;
        long dz = centerZ - playerBlockZ;
        return dx * dx + dz * dz;
    }

    private static Path compactPath(Path root) {
        String override = System.getProperty("voxy.serverLodCompactVlcpPath", "").trim();
        if (!override.isEmpty()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        Path defaultPath = root.resolve("surface_lods.vlcp");
        return Files.exists(defaultPath) ? defaultPath : null;
    }

    private static Path vlcp3Path(Path root) {
        String override = System.getProperty("voxy.serverLodVlcp3Path", "").trim();
        if (!override.isEmpty()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        Path defaultPath = root.resolve("surface_lods.vlcp3");
        return Files.exists(defaultPath) ? defaultPath : null;
    }

    private Path indexPath(ServerLodTileKey key) {
        return this.indexDir.resolve(key.safeFileName() + ".txt");
    }

    private static String encode(ServerLodTileMetadata metadata) {
        var key = metadata.key();
        return String.join("\t",
                key.dimension(),
                Integer.toString(key.lodLevel()),
                Integer.toString(key.sectionX()),
                Integer.toString(key.sectionY()),
                Integer.toString(key.sectionZ()),
                Integer.toString(key.generatorVersion()),
                Long.toString(metadata.epoch()),
                metadata.contentHash(),
                metadata.sourceKind().name(),
                metadata.confidence().name(),
                metadata.lightKind().name(),
                Boolean.toString(metadata.parentComplete()),
                Boolean.toString(metadata.childComplete()),
                Integer.toString(metadata.compressedBytes()));
    }

    private static ServerLodTileMetadata decode(String line) {
        var parts = line.strip().split("\t");
        if (parts.length != 14) {
            throw new IllegalArgumentException("expected 14 fields, got " + parts.length);
        }
        var key = new ServerLodTileKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
        return new ServerLodTileMetadata(
                key,
                Long.parseLong(parts[6]),
                parts[7],
                ServerLodSourceKind.valueOf(parts[8]),
                ServerLodConfidence.valueOf(parts[9]),
                ServerLodLightKind.valueOf(parts[10]),
                Boolean.parseBoolean(parts[11]),
                Boolean.parseBoolean(parts[12]),
                Integer.parseInt(parts[13]));
    }

    public enum StoreResult {
        STORED,
        REJECTED_STALE_EPOCH,
        REJECTED_LOWER_AUTHORITY,
        REJECTED_MISSING_LIGHT,
        REJECTED_EMPTY_TILE,
        REJECTED_UNTRUSTED_LIGHT,
        REJECTED_HASH_MISMATCH,
        REJECTED_IO_ERROR
    }
}
