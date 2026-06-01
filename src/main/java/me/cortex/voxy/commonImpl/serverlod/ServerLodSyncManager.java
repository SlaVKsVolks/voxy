package me.cortex.voxy.commonImpl.serverlod;

import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ServerLodSyncManager {
    private static final ConcurrentMap<String, ServerLodSession> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong BATCH_ID = new AtomicLong(1);
    private static final int AUTO_BUILD_MIN_VISIBLE_TILES = Math.max(
            0,
            Integer.getInteger("voxy.serverLodAutoBuildMinVisibleTiles", 512));
    private static volatile ServerLodTileStore store;
    private static volatile int bandwidthMbps = ServerLodConstants.DEFAULT_BANDWIDTH_MBPS;

    private ServerLodSyncManager() {}

    public static void setBandwidthMbps(int value) {
        bandwidthMbps = Math.max(1, value);
    }

    public static int bandwidthMbps() {
        return bandwidthMbps;
    }

    public static void mountVlcp3(Path path) {
        System.setProperty("voxy.serverLodVlcp3Path", path.toAbsolutePath().normalize().toString());
        synchronized (ServerLodSyncManager.class) {
            ServerLodTileStore candidate = new ServerLodTileStore(defaultStoreRoot());
            String validation = candidate.validateMountedVlcp3(4096);
            if (!validation.contains("passed=true") || validation.contains("passed=false")) {
                System.clearProperty("voxy.serverLodVlcp3Path");
                throw new IllegalStateException("Refusing to mount invalid VLCP0003 region: " + validation);
            }
            store = candidate;
        }
    }

    public static void mountStoreRoot(Path root) {
        synchronized (ServerLodSyncManager.class) {
            store = new ServerLodTileStore(root.toAbsolutePath().normalize());
        }
    }

    public static String statusLine() {
        var activeStore = getStore();
        return "protocol=" + ServerLodConstants.PROTOCOL_VERSION
                + " store=" + activeStore.root()
                + " indexed_tiles=" + activeStore.indexedTileCount()
                + " sessions=" + SESSIONS.size()
                + " bandwidth_mbps=" + bandwidthMbps
                + " " + ServerLodDiagnostics.statusLine();
    }

    public static String validateVisible(int limit) {
        return getStore().validateVisible(limit);
    }

    public static void handleHello(ServerLodPayloads.ClientHello payload, ServerPlayer player) {
        var session = new ServerLodSession(player.getUUID().toString(), payload.requestedRadius(), Math.min(payload.bandwidthMbps(), bandwidthMbps));
        SESSIONS.put(player.getUUID().toString(), session);
        if (!ServerLodConstants.PROTOCOL_VERSION.equals(payload.protocolVersion())) {
            PacketDistributor.sendToPlayer(player, new ServerLodPayloads.ServerManifest(
                    ServerLodConstants.PROTOCOL_VERSION,
                    payload.clientSyncId(),
                    System.currentTimeMillis(),
                    List.of(),
                    "incompatible client protocol " + payload.protocolVersion()));
            return;
        }
        session.currentSyncId = payload.clientSyncId();
        var visibleManifest = visibleManifest(player, ServerLodConstants.INITIAL_MANIFEST_TILE_LIMIT);
        if (visibleManifest.size() < AUTO_BUILD_MIN_VISIBLE_TILES) {
            ServerAuthoredLodBuilder.ensureCoverage(
                    player,
                    payload.requestedRadius(),
                    "visible manifest tiles=" + visibleManifest.size());
        }
        var manifest = filterCachedManifest(visibleManifest, payload.cachedHashes(), ServerLodConstants.INITIAL_MANIFEST_TILE_LIMIT);
        PacketDistributor.sendToPlayer(player, new ServerLodPayloads.ServerManifest(
                ServerLodConstants.PROTOCOL_VERSION,
                payload.clientSyncId(),
                System.currentTimeMillis(),
                manifest,
                "server LoD manifest: " + manifest.size() + " indexed tiles"));
    }

    private static List<ServerLodTileMetadata> priorityManifest(ServerPlayer player, List<String> clientCachedHashes, int limit) {
        return filterCachedManifest(visibleManifest(player, manifestCandidateLimit(limit, clientCachedHashes)), clientCachedHashes, limit);
    }

    private static int manifestCandidateLimit(int limit, List<String> clientCachedHashes) {
        int cachedCount = clientCachedHashes == null ? 0 : clientCachedHashes.size();
        return limit + Math.min(cachedCount, ServerLodConstants.MAX_CLIENT_MANIFEST_HASHES);
    }

    private static List<ServerLodTileMetadata> visibleManifest(ServerPlayer player, int limit) {
        String dimension = player.level().dimension().location().toString();
        int playerBlockX = player.getBlockX();
        int playerBlockZ = player.getBlockZ();
        return getStore().priorityManifest(dimension, playerBlockX, playerBlockZ, limit);
    }

    private static List<ServerLodTileMetadata> filterCachedManifest(
            List<ServerLodTileMetadata> visibleManifest,
            List<String> clientCachedHashes,
            int limit
    ) {
        var cached = new HashSet<>(clientCachedHashes == null ? List.<String>of() : clientCachedHashes);
        return visibleManifest.stream()
                .filter(metadata -> !cached.contains(metadata.contentHash()))
                .limit(limit)
                .toList();
    }

    public static void handleTileRequest(ServerLodPayloads.TileRequest payload, ServerPlayer player) {
        var session = SESSIONS.get(player.getUUID().toString());
        if (session == null || session.currentSyncId != payload.clientSyncId()) {
            return;
        }
        int maxBytes = Math.max(4096, Math.min(payload.maxBatchBytes(), ServerLodConstants.MAX_TILE_BATCH_BYTES));
        var pending = new ArrayList<ServerLodTile>();
        int pendingRawEstimate = 12;
        for (var key : payload.keys()) {
            var tile = getStore().read(key);
            if (tile.isEmpty()) {
                continue;
            }
            if (tile.get().compressedPayload().length > maxBytes) {
                continue;
            }
            pending.add(tile.get());
            pendingRawEstimate += tile.get().compressedPayload().length + 512;
            if (pending.size() >= 64 || pendingRawEstimate >= (maxBytes * 3 / 4)) {
                sendTileBatch(player, payload.clientSyncId(), pending, maxBytes);
                pending.clear();
                pendingRawEstimate = 12;
            }
        }
        sendTileBatch(player, payload.clientSyncId(), pending, maxBytes);
    }

    private static void sendTileBatch(ServerPlayer player, long clientSyncId, List<ServerLodTile> tiles, int maxBytes) {
        if (tiles.isEmpty()) {
            return;
        }
        try {
            var encoded = Vlcp3BatchCodec.encode(tiles, 1);
            if (encoded.compressedBytes() <= maxBytes) {
                PacketDistributor.sendToPlayer(player, new ServerLodPayloads.TileBatch(
                        clientSyncId,
                        BATCH_ID.getAndIncrement(),
                        encoded.rawBytes(),
                        tiles.stream().map(ServerLodTile::metadata).toList(),
                        encoded.compressedPayload()));
                ServerLodDiagnostics.serverLodTilesSent.addAndGet(tiles.size());
                ServerLodDiagnostics.lodSyncBytesSent.addAndGet(encoded.compressedBytes());
                return;
            }
            if (tiles.size() == 1) {
                return;
            }
            int mid = tiles.size() / 2;
            sendTileBatch(player, clientSyncId, tiles.subList(0, mid), maxBytes);
            sendTileBatch(player, clientSyncId, tiles.subList(mid, tiles.size()), maxBytes);
        } catch (Exception e) {
            Logger.error("Failed to encode Voxy server LoD tile batch: " + e.getMessage());
        }
    }

    public static void handleAck(ServerLodPayloads.TileAck payload, ServerPlayer player) {
        var session = SESSIONS.get(player.getUUID().toString());
        if (session != null && session.currentSyncId == payload.clientSyncId()) {
            session.ackedTiles += payload.importedTiles();
            session.rejectedTiles += payload.rejectedTiles();
            session.lastClientManifestHash = payload.cacheManifestHash();
        }
    }

    public static void broadcastInvalidation(List<ServerLodTileKey> keys, String reason) {
        if (keys.isEmpty()) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        var payload = new ServerLodPayloads.ServerInvalidate(keys, System.currentTimeMillis(), reason);
        for (var player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static void broadcastManifests(String reason) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (var session : SESSIONS.values()) {
            if (session.currentSyncId <= 0) {
                continue;
            }
            ServerPlayer player;
            try {
                player = server.getPlayerList().getPlayer(UUID.fromString(session.playerId));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (player == null) {
                continue;
            }
            var manifest = visibleManifest(player, ServerLodConstants.INITIAL_MANIFEST_TILE_LIMIT);
            PacketDistributor.sendToPlayer(player, new ServerLodPayloads.ServerManifest(
                    ServerLodConstants.PROTOCOL_VERSION,
                    session.currentSyncId,
                    System.currentTimeMillis(),
                    manifest,
                    "server LoD manifest: " + manifest.size() + " indexed tiles"
                            + (reason == null || reason.isBlank() ? "" : " (" + reason + ")")));
        }
    }

    public static void handleCandidateUpload(ServerLodPayloads.ClientCandidateUpload payload, ServerPlayer player) {
        if (payload.metadata().sourceKind() != ServerLodSourceKind.CLIENT_PROVISIONAL
                && payload.metadata().sourceKind() != ServerLodSourceKind.VALIDATED_CLIENT_UPLOAD) {
            ServerLodDiagnostics.provisionalUploadsRejected.incrementAndGet();
            return;
        }
        var result = getStore().store(payload.metadata(), payload.compressedPayload());
        if (result == ServerLodTileStore.StoreResult.STORED) {
            ServerLodDiagnostics.provisionalUploadsAccepted.incrementAndGet();
        } else {
            ServerLodDiagnostics.provisionalUploadsRejected.incrementAndGet();
        }
    }

    public static ServerLodTileStore getStore() {
        var active = store;
        if (active != null) {
            return active;
        }
        synchronized (ServerLodSyncManager.class) {
            if (store == null) {
                store = new ServerLodTileStore(defaultStoreRoot());
            }
            return store;
        }
    }

    private static Path defaultStoreRoot() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.getWorldPath(LevelResource.ROOT).resolve("voxy-server-lod");
        }
        return Path.of("voxy-server-lod");
    }

    private static final class ServerLodSession {
        private final String playerId;
        private final int requestedRadius;
        private final int bandwidthMbps;
        private long ackedTiles;
        private long rejectedTiles;
        private long currentSyncId;
        private String lastClientManifestHash = "";

        private ServerLodSession(String playerId, int requestedRadius, int bandwidthMbps) {
            this.playerId = playerId;
            this.requestedRadius = requestedRadius;
            this.bandwidthMbps = bandwidthMbps;
        }
    }
}
