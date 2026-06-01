package me.cortex.voxy.commonImpl.serverlod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ServerLodConstants {
    public static final String PROTOCOL_VERSION = "server-lod-v1";
    public static final int DEFAULT_REQUEST_RADIUS = 512;
    public static final int DEFAULT_BANDWIDTH_MBPS = 64;
    public static final int MAX_TILE_BATCH_BYTES = 1 << 20;
    public static final int INITIAL_MANIFEST_TILE_LIMIT = 65_536;
    public static final int MAX_CLIENT_MANIFEST_HASHES = 131_072;

    private ServerLodConstants() {}

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }
}
