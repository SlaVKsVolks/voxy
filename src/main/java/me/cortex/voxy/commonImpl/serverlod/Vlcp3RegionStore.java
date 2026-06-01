package me.cortex.voxy.commonImpl.serverlod;

import me.cortex.voxy.common.Logger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;

public final class Vlcp3RegionStore implements AutoCloseable {
    private static final byte[] MAGIC = new byte[]{'V', 'L', 'C', 'P', '0', '0', '0', '3'};
    private static final byte[] INDEX_MAGIC = new byte[]{'V', 'L', 'C', 'P', 'I', '0', '0', '3'};
    private static final int VERSION = 3;
    private static final int STORAGE_GENERATOR_VERSION = 5;
    private static final int SOURCE_MODE_AUTHORED = 1;

    private final String dimension;
    private final Path dataPath;
    private final Header header;
    private final RandomAccessFile dataFile;
    private final Map<String, Entry> entries = new HashMap<>();
    private final int[] perLevelTiles = new int[5];
    private final ArrayList<Entry> priorityEntries = new ArrayList<>();
    private int emptyAdvertisedTiles;
    private int syntheticAdvertisedTiles;
    private int missingLightTrustedTiles;
    private final Object ioLock = new Object();
    private final LinkedHashMap<BatchKey, byte[]> batchCache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BatchKey, byte[]> eldest) {
            return this.size() > 16;
        }
    };

    public Vlcp3RegionStore(Path dataPath, String dimension) {
        try {
            this.dimension = dimension;
            this.dataPath = dataPath.toAbsolutePath().normalize();
            this.dataFile = new RandomAccessFile(dataPath.toFile(), "r");
            this.header = validateDataHeader(this.dataFile, dataPath);
            Path indexPath = indexPath(dataPath);
            readIndex(indexPath);
            Logger.info("Mounted Voxy VLCP0003 region store: " + this.dataPath + " tiles=" + this.entries.size());
        } catch (IOException exception) {
            throw new RuntimeException("Failed to mount Voxy VLCP0003 region store: " + dataPath, exception);
        }
    }

    public Path dataPath() {
        return this.dataPath;
    }

    public int tileCount() {
        return this.entries.size();
    }

    public ValidationReport validateVisible(int limit) {
        int unreadable = 0;
        int checked = 0;
        for (var metadata : manifest(limit)) {
            checked++;
            if (read(metadata.key()).isEmpty()) {
                unreadable++;
            }
        }
        return new ValidationReport(
                this.dataPath,
                this.entries.size(),
                this.perLevelTiles.clone(),
                limit,
                checked,
                unreadable,
                this.emptyAdvertisedTiles,
                this.syntheticAdvertisedTiles,
                this.missingLightTrustedTiles,
                checked > 0 && unreadable == 0);
    }

    public List<ServerLodTileMetadata> manifest(int limit) {
        return this.priorityEntries.stream()
                .limit(limit)
                .map(entry -> entry.metadata)
                .toList();
    }

    public List<ServerLodTileMetadata> priorityManifest(String dimension, int playerBlockX, int playerBlockZ, int limit) {
        return this.priorityEntries.stream()
                .sorted(Comparator
                        .comparing((Entry entry) -> !entry.metadata.key().dimension().equals(dimension))
                        .thenComparing((Entry entry) -> entry.metadata.key().lodLevel(), Comparator.reverseOrder())
                        .thenComparingLong(entry -> distanceScore(entry.metadata.key(), playerBlockX, playerBlockZ)))
                .limit(limit)
                .map(entry -> entry.metadata)
                .toList();
    }

    public Optional<ServerLodTile> read(ServerLodTileKey key) {
        Entry entry = this.entries.get(key.stableId());
        if (entry == null) {
            return Optional.empty();
        }
        try {
            if (entry.compressedOffset < 0 || entry.compressedLength <= 0 || entry.batchRawLength <= 0) {
                Logger.warn("Ignoring invalid VLCP0003 batch extent for " + key.stableId());
                return Optional.empty();
            }
            if (entry.compressedOffset + (long) entry.compressedLength > this.dataFile.length()) {
                Logger.warn("Ignoring out-of-range VLCP0003 batch extent for " + key.stableId());
                return Optional.empty();
            }
            byte[] batch = readBatch(entry);
            if (entry.rawOffset < 0 || entry.rawLength < 0 || entry.rawOffset + (long) entry.rawLength > batch.length) {
                Logger.warn("Ignoring invalid VLCP0003 tile extent for " + key.stableId());
                return Optional.empty();
            }
            byte[] payload = new byte[entry.rawLength];
            System.arraycopy(batch, entry.rawOffset, payload, 0, entry.rawLength);
            String invalidReason = ServerLodTileIntegrity.validateStoredTile(entry.metadata, payload);
            if (!invalidReason.isEmpty()) {
                Logger.warn("Ignoring invalid VLCP0003 tile " + key.stableId() + ": " + invalidReason);
                return Optional.empty();
            }
            String payloadHash = ServerLodConstants.sha256Hex(payload);
            if (!payloadHash.equals(entry.metadata.contentHash())) {
                Logger.warn("Ignoring VLCP0003 tile with hash mismatch for " + key.stableId());
                return Optional.empty();
            }
            return Optional.of(new ServerLodTile(entry.metadata, payload));
        } catch (IOException exception) {
            Logger.warn("Failed reading VLCP0003 tile " + key.stableId() + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    private byte[] readBatch(Entry entry) throws IOException {
        BatchKey key = new BatchKey(entry.compressedOffset, entry.compressedLength, entry.batchRawLength);
        synchronized (this.ioLock) {
            byte[] cached = this.batchCache.get(key);
            if (cached != null) {
                return cached;
            }
            byte[] compressed = new byte[entry.compressedLength];
            this.dataFile.seek(entry.compressedOffset);
            this.dataFile.readFully(compressed);
            byte[] raw = decompress(compressed, entry.batchRawLength);
            this.batchCache.put(key, raw);
            return raw;
        }
    }

    private void readIndex(Path indexPath) throws IOException {
        if (!Files.isRegularFile(indexPath)) {
            Path tempIndexPath = tempIndexPath(indexPath);
            if (Files.isRegularFile(tempIndexPath)) {
                throw new IOException("incomplete VLCP0003 region: found temporary index entries at "
                        + tempIndexPath + " but missing finalized index sidecar " + indexPath);
            }
            throw new IOException("missing VLCP0003 index sidecar " + indexPath);
        }
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexPath), 1024 * 1024))) {
            byte[] magic = input.readNBytes(INDEX_MAGIC.length);
            if (!java.util.Arrays.equals(magic, INDEX_MAGIC)) {
                throw new IOException("invalid VLCP0003 index magic");
            }
            int version = readIntLE(input);
            if (version != VERSION) {
                throw new IOException("unsupported VLCP0003 index version " + version);
            }
            readIntLE(input); // flags
            long count = readLongLE(input);
            if (count < 0 || count > 100_000_000L) {
                throw new IOException("invalid VLCP0003 index tile count " + count);
            }
            for (long i = 0; i < count; i++) {
                Entry entry = readEntry(input);
                if (ServerLodTileIntegrity.isEmptyAdvertised(entry.metadata) || entry.rawLength <= 0) {
                    this.emptyAdvertisedTiles++;
                    continue;
                }
                if (ServerLodTileIntegrity.isSyntheticAdvertised(entry.metadata)) {
                    this.syntheticAdvertisedTiles++;
                    if (!ServerLodTileIntegrity.allowSyntheticPreviewManifest()) {
                        continue;
                    }
                }
                if (ServerLodTileIntegrity.hasMissingLightTrustedViolation(entry.metadata)) {
                    this.missingLightTrustedTiles++;
                    continue;
                }
                this.entries.put(entry.metadata.key().stableId(), entry);
            }
            for (Entry entry : this.entries.values()) {
                int level = entry.metadata.key().lodLevel();
                if (level >= 0 && level < this.perLevelTiles.length) {
                    this.perLevelTiles[level]++;
                }
                this.priorityEntries.add(entry);
            }
            this.priorityEntries.sort(Comparator
                    .comparing((Entry entry) -> entry.metadata.key().lodLevel(), Comparator.reverseOrder())
                    .thenComparing(entry -> entry.metadata.key().stableId()));
        } catch (EOFException exception) {
            throw new IOException("truncated VLCP0003 index sidecar " + indexPath, exception);
        }
    }

    private Entry readEntry(DataInputStream input) throws IOException {
        int level = readIntLE(input);
        int x = readIntLE(input);
        int y = readIntLE(input);
        int z = readIntLE(input);
        readIntLE(input); // coverage kind
        byte[] contentHashBytes = input.readNBytes(32);
        if (contentHashBytes.length != 32) {
            throw new EOFException("truncated VLCP0003 content hash");
        }
        readLongLE(input); // batch id, useful for diagnostics but not needed for lookup
        int rawOffset = readIntLE(input);
        int rawLength = readIntLE(input);
        int batchRawLength = readIntLE(input);
        long compressedOffset = readLongLE(input);
        int compressedLength = readIntLE(input);
        var key = new ServerLodTileKey(this.dimension, level, x, y, z, STORAGE_GENERATOR_VERSION);
        String contentHash = HexFormat.of().formatHex(contentHashBytes);
        boolean authored = this.header.sourceMode() == SOURCE_MODE_AUTHORED;
        var metadata = new ServerLodTileMetadata(
                key,
                1L,
                contentHash,
                authored ? ServerLodSourceKind.REAL_SERVER_CHUNK : ServerLodSourceKind.SERVER_SURFACE_PREVIEW,
                authored ? ServerLodConfidence.REAL : ServerLodConfidence.MEDIUM,
                authored ? ServerLodLightKind.REAL_SKY : ServerLodLightKind.SYNTHETIC_SURFACE,
                true,
                true,
                rawLength);
        return new Entry(metadata, rawOffset, rawLength, batchRawLength, compressedOffset, compressedLength);
    }

    private static Header validateDataHeader(RandomAccessFile file, Path dataPath) throws IOException {
        if (file.length() < 44L) {
            throw new IOException("truncated VLCP0003 data header in " + dataPath);
        }
        byte[] magic = new byte[MAGIC.length];
        file.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IOException("invalid VLCP0003 data magic in " + dataPath);
        }
        int version = readIntLE(file);
        if (version != VERSION) {
            throw new IOException("unsupported VLCP0003 data version " + version);
        }
        int centerX = readIntLE(file);
        int centerZ = readIntLE(file);
        int radiusChunks = readIntLE(file);
        int shape = file.readUnsignedByte();
        int encoder = file.readUnsignedByte();
        int profile = file.readUnsignedByte();
        int sourceMode = file.readUnsignedByte();
        readLongLE(file); // reserved
        long totalChunks = readLongLE(file);
        return new Header(centerX, centerZ, radiusChunks, shape, encoder, profile, sourceMode, totalChunks);
    }

    private static Path indexPath(Path dataPath) {
        String fileName = dataPath.getFileName().toString();
        Path parent = dataPath.getParent();
        String indexName = fileName.endsWith(".vlcp3")
                ? fileName.substring(0, fileName.length() - ".vlcp3".length()) + ".vlcp3i"
                : fileName + ".vlcp3i";
        return parent == null ? Path.of(indexName) : parent.resolve(indexName);
    }

    private static Path tempIndexPath(Path indexPath) {
        String tempName = indexPath.getFileName().toString() + ".entries.tmp";
        Path parent = indexPath.getParent();
        return parent == null ? Path.of(tempName) : parent.resolve(tempName);
    }

    private static byte[] decompress(byte[] compressed, int rawLength) throws IOException {
        return ServerLodZstd.decompress(compressed, rawLength, "VLCP0003 region batch");
    }

    private static int readIntLE(DataInputStream input) throws IOException {
        return input.readUnsignedByte()
                | (input.readUnsignedByte() << 8)
                | (input.readUnsignedByte() << 16)
                | (input.readUnsignedByte() << 24);
    }

    private static int readIntLE(RandomAccessFile file) throws IOException {
        return file.readUnsignedByte()
                | (file.readUnsignedByte() << 8)
                | (file.readUnsignedByte() << 16)
                | (file.readUnsignedByte() << 24);
    }

    private static long readLongLE(DataInputStream input) throws IOException {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= (long) input.readUnsignedByte() << (i * 8);
        }
        return value;
    }

    private static long readLongLE(RandomAccessFile file) throws IOException {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= (long) file.readUnsignedByte() << (i * 8);
        }
        return value;
    }

    private static long distanceScore(ServerLodTileKey key, int playerBlockX, int playerBlockZ) {
        int sectionSize = 32 << key.lodLevel();
        long centerX = (long) key.sectionX() * sectionSize + sectionSize / 2L;
        long centerZ = (long) key.sectionZ() * sectionSize + sectionSize / 2L;
        long dx = centerX - playerBlockX;
        long dz = centerZ - playerBlockZ;
        return dx * dx + dz * dz;
    }

    @Override
    public void close() {
        try {
            this.dataFile.close();
        } catch (IOException exception) {
            Logger.warn("Failed closing VLCP0003 region store", exception);
        }
    }

    private record Header(int centerX, int centerZ, int radiusChunks, int shape, int encoder, int profile, int sourceMode, long totalChunks) {}

    private record Entry(ServerLodTileMetadata metadata, int rawOffset, int rawLength, int batchRawLength, long compressedOffset, int compressedLength) {}

    private record BatchKey(long compressedOffset, int compressedLength, int batchRawLength) {}

    public record ValidationReport(
            Path mountedPath,
            int totalTiles,
            int[] perLevelTiles,
            int requestedLimit,
            int checkedTiles,
            int unreadableTiles,
            int emptyAdvertisedTiles,
            int syntheticAdvertisedTiles,
            int missingLightTrustedTiles,
            boolean firstPriorityReadable
    ) {
        public boolean passed() {
            return this.totalTiles > 0
                    && this.firstPriorityReadable
                    && this.unreadableTiles == 0
                    && this.emptyAdvertisedTiles == 0
                    && this.syntheticAdvertisedTiles == 0
                    && this.missingLightTrustedTiles == 0;
        }

        public String statusLine() {
            return "mounted_path=" + this.mountedPath
                    + " total_tiles=" + this.totalTiles
                    + " per_level=[" + this.perLevelTiles[0] + "," + this.perLevelTiles[1] + "," + this.perLevelTiles[2] + "," + this.perLevelTiles[3] + "," + this.perLevelTiles[4] + "]"
                    + " checked=" + this.checkedTiles
                    + " first_" + this.requestedLimit + "_priority_readable=" + this.firstPriorityReadable
                    + " unreadable=" + this.unreadableTiles
                    + " empty_advertised=" + this.emptyAdvertisedTiles
                    + " synthetic_advertised=" + this.syntheticAdvertisedTiles
                    + " missing_light_trusted=" + this.missingLightTrustedTiles
                    + " passed=" + this.passed();
        }
    }
}
