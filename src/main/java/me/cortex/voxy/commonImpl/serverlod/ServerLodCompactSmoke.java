package me.cortex.voxy.commonImpl.serverlod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

public final class ServerLodCompactSmoke {
    private ServerLodCompactSmoke() {}

    public static void main(String[] args) throws Exception {
        Path compact = null;
        Path vlcp3 = null;
        Path storeRoot = null;
        Path report = null;
        int limit = 128;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--compact" -> compact = Path.of(required(args, ++i, "--compact"));
                case "--vlcp3" -> vlcp3 = Path.of(required(args, ++i, "--vlcp3"));
                case "--store" -> storeRoot = Path.of(required(args, ++i, "--store"));
                case "--report" -> report = Path.of(required(args, ++i, "--report"));
                case "--limit" -> limit = Integer.parseInt(required(args, ++i, "--limit"));
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        if (compact == null && vlcp3 == null) {
            throw new IllegalArgumentException("Missing --compact or --vlcp3");
        }
        if (storeRoot == null) {
            storeRoot = Files.createTempDirectory("voxy-server-lod-compact-smoke");
        }
        if (report == null) {
            report = storeRoot.resolve("server_lod_compact_smoke.json");
        }

        System.setProperty("voxy.offlineImportMinimalMapper", "true");
        if (compact != null) {
            System.setProperty("voxy.serverLodCompactVlcpPath", compact.toString());
        }
        if (vlcp3 != null) {
            System.setProperty("voxy.serverLodVlcp3Path", vlcp3.toString());
        }
        Instant started = Instant.now();
        var store = new ServerLodTileStore(storeRoot);
        var manifest = store.priorityManifest("minecraft:overworld", 0, 0, limit);
        int read = 0;
        long bytes = 0;
        for (var metadata : manifest) {
            var tile = store.read(metadata.key());
            if (tile.isPresent()) {
                read++;
                bytes += tile.get().compressedPayload().length;
            }
        }
        double seconds = Math.max(0.001D, Duration.between(started, Instant.now()).toMillis() / 1000.0D);
        String validation = store.validateVisible(limit);
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\n"
                + "  \"schema\": \"voxy.server_lod.compact_smoke.v1\",\n"
                + "  \"compact\": \"" + escape(compact == null ? "" : compact.toString()) + "\",\n"
                + "  \"vlcp3\": \"" + escape(vlcp3 == null ? "" : vlcp3.toString()) + "\",\n"
                + "  \"store\": \"" + escape(storeRoot.toString()) + "\",\n"
                + "  \"manifest_tiles\": " + manifest.size() + ",\n"
                + "  \"tiles_read\": " + read + ",\n"
                + "  \"validation\": \"" + escape(validation) + "\",\n"
                + "  \"tile_payload_bytes\": " + bytes + ",\n"
                + "  \"elapsed_seconds\": " + String.format(Locale.ROOT, "%.3f", seconds) + ",\n"
                + "  \"tiles_per_sec\": " + String.format(Locale.ROOT, "%.3f", read / seconds) + "\n"
                + "}\n");
        System.out.println("voxy-server-lod-compact-smoke: manifest=" + manifest.size()
                + " read=" + read
                + " bytes=" + bytes
                + " elapsed_seconds=" + String.format(Locale.ROOT, "%.3f", seconds)
                + " validation=\"" + validation + "\"");
    }

    private static String required(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + name);
        }
        return args[index];
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
