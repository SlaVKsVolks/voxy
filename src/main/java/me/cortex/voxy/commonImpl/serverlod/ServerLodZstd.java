package me.cortex.voxy.commonImpl.serverlod;

import com.github.luben.zstd.Zstd;

import java.io.IOException;

final class ServerLodZstd {
    private ServerLodZstd() {}

    static byte[] compress(byte[] raw, int level, String context) throws IOException {
        try {
            return Zstd.compress(raw, level);
        } catch (RuntimeException exception) {
            throw new IOException(context + " zstd compression failed", exception);
        }
    }

    static byte[] decompress(byte[] compressed, int rawLength, String context) throws IOException {
        if (rawLength < 0) {
            throw new IOException(context + " invalid raw length " + rawLength);
        }
        try {
            byte[] out = Zstd.decompress(compressed, rawLength);
            if (out.length != rawLength) {
                throw new IOException(context + " zstd size mismatch: expected " + rawLength + " got " + out.length);
            }
            return out;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException(context + " zstd decompression failed", exception);
        }
    }
}
