package me.cortex.voxy.commonImpl.serverlod;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class Vlcp3RegionWriter implements Closeable {
    private static final byte[] MAGIC = new byte[]{'V', 'L', 'C', 'P', '0', '0', '0', '3'};
    private static final byte[] INDEX_MAGIC = new byte[]{'V', 'L', 'C', 'P', 'I', '0', '0', '3'};
    private static final int VERSION = 3;
    private static final int DEFAULT_BATCH_TILE_LIMIT = Integer.getInteger("voxy.serverLodAuthoredVlcp3BatchTiles", 64);

    private final Path dataPath;
    private final Path indexTempPath;
    private final CountingOutputStream output;
    private final DataOutputStream data;
    private final DataOutputStream indexTemp;
    private final List<ServerLodTile> pending = new ArrayList<>(DEFAULT_BATCH_TILE_LIMIT);
    private long nextBatchId;
    private long entryCount;
    private boolean closed;

    public Vlcp3RegionWriter(Path dataPath, int centerX, int centerZ, int radiusChunks, long totalChunks) throws IOException {
        this.dataPath = dataPath.toAbsolutePath().normalize();
        Path parent = this.dataPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.output = new CountingOutputStream(new BufferedOutputStream(Files.newOutputStream(
                this.dataPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ), 1024 * 1024));
        this.data = new DataOutputStream(this.output);
        this.data.write(MAGIC);
        writeIntLE(this.data, VERSION);
        writeIntLE(this.data, centerX);
        writeIntLE(this.data, centerZ);
        writeIntLE(this.data, radiusChunks);
        this.data.write(new byte[]{1, 2, 2, 1}); // square, hybrid, balanced, authored
        writeLongLE(this.data, 0L);
        writeLongLE(this.data, totalChunks);
        this.indexTempPath = indexTempPath(this.dataPath);
        this.indexTemp = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                this.indexTempPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ), 1024 * 1024));
    }

    public void write(ServerLodTile tile) throws IOException {
        if (this.closed) {
            throw new IOException("VLCP0003 writer is closed");
        }
        String invalidReason = ServerLodTileIntegrity.validateAuthoredVlcp3Tile(tile);
        if (!invalidReason.isEmpty()) {
            throw new IOException("Refusing invalid authored VLCP0003 tile: " + invalidReason);
        }
        this.pending.add(tile);
        if (this.pending.size() >= DEFAULT_BATCH_TILE_LIMIT) {
            flushBatch();
        }
    }

    public int tileCount() {
        long count = this.entryCount + this.pending.size();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public void close() throws IOException {
        if (this.closed) {
            return;
        }
        flushBatch();
        this.data.flush();
        this.output.close();
        this.indexTemp.flush();
        this.indexTemp.close();
        writeIndex();
        Files.deleteIfExists(this.indexTempPath);
        this.closed = true;
    }

    private void flushBatch() throws IOException {
        if (this.pending.isEmpty()) {
            return;
        }
        byte[] raw = concatenatePayloads(this.pending);
        byte[] compressed = ServerLodZstd.compress(raw, 1, "authored VLCP0003 region batch");
        long compressedOffset = this.output.position() + 12L;
        writeIntLE(this.data, raw.length);
        writeIntLE(this.data, compressed.length);
        writeIntLE(this.data, this.pending.size());
        this.data.write(compressed);

        int rawOffset = 0;
        long batchId = this.nextBatchId++;
        for (ServerLodTile tile : this.pending) {
            int rawLength = tile.compressedPayload().length;
            writeIndexRecord(this.indexTemp, tile.metadata(), batchId, rawOffset, rawLength, raw.length, compressedOffset, compressed.length);
            this.entryCount++;
            rawOffset += rawLength;
        }
        this.pending.clear();
    }

    private static byte[] concatenatePayloads(List<ServerLodTile> tiles) {
        int rawLength = 0;
        for (ServerLodTile tile : tiles) {
            rawLength += tile.compressedPayload().length;
        }
        byte[] raw = new byte[rawLength];
        int offset = 0;
        for (ServerLodTile tile : tiles) {
            byte[] payload = tile.compressedPayload();
            System.arraycopy(payload, 0, raw, offset, payload.length);
            offset += payload.length;
        }
        return raw;
    }

    private void writeIndex() throws IOException {
        Path indexPath = indexPath(this.dataPath);
        try (var index = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                indexPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ), 1024 * 1024))) {
            index.write(INDEX_MAGIC);
            writeIntLE(index, VERSION);
            writeIntLE(index, 0);
            writeLongLE(index, this.entryCount);
            try (var entries = Files.newInputStream(this.indexTempPath)) {
                entries.transferTo(index);
            }
        }
    }

    private static void writeIndexRecord(
            DataOutputStream index,
            ServerLodTileMetadata metadata,
            long batchId,
            int rawOffset,
            int rawLength,
            int batchRawLength,
            long compressedOffset,
            int compressedLength
    ) throws IOException {
        ServerLodTileKey key = metadata.key();
        writeIntLE(index, key.lodLevel());
        writeIntLE(index, key.sectionX());
        writeIntLE(index, key.sectionY());
        writeIntLE(index, key.sectionZ());
        writeIntLE(index, 1);
        index.write(hashBytes(metadata.contentHash()));
        writeLongLE(index, batchId);
        writeIntLE(index, rawOffset);
        writeIntLE(index, rawLength);
        writeIntLE(index, batchRawLength);
        writeLongLE(index, compressedOffset);
        writeIntLE(index, compressedLength);
    }

    private static byte[] hashBytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static Path indexPath(Path dataPath) {
        String fileName = dataPath.getFileName().toString();
        Path parent = dataPath.getParent();
        String indexName = fileName.endsWith(".vlcp3")
                ? fileName.substring(0, fileName.length() - ".vlcp3".length()) + ".vlcp3i"
                : fileName + ".vlcp3i";
        return parent == null ? Path.of(indexName) : parent.resolve(indexName);
    }

    private static Path indexTempPath(Path dataPath) {
        Path indexPath = indexPath(dataPath);
        String tempName = indexPath.getFileName().toString() + ".entries.tmp";
        Path parent = indexPath.getParent();
        return parent == null ? Path.of(tempName) : parent.resolve(tempName);
    }

    private static void writeIntLE(DataOutputStream out, int value) throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
        out.writeByte((value >>> 16) & 0xFF);
        out.writeByte((value >>> 24) & 0xFF);
    }

    private static void writeLongLE(DataOutputStream out, long value) throws IOException {
        for (int i = 0; i < 8; i++) {
            out.writeByte((int) ((value >>> (i * 8)) & 0xFF));
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long position;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            this.delegate.write(b);
            this.position++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            this.delegate.write(b, off, len);
            this.position += len;
        }

        @Override
        public void flush() throws IOException {
            this.delegate.flush();
        }

        @Override
        public void close() throws IOException {
            this.delegate.close();
        }

        private long position() {
            return this.position;
        }
    }
}
