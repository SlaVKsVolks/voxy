package me.cortex.voxy.client.serverlod;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.serverlod.ServerLodConstants;
import me.cortex.voxy.commonImpl.serverlod.ServerLodDiagnostics;
import me.cortex.voxy.commonImpl.serverlod.ServerLodPayloads;
import me.cortex.voxy.commonImpl.serverlod.ServerLodTile;
import me.cortex.voxy.commonImpl.serverlod.ServerLodTileKey;
import me.cortex.voxy.commonImpl.serverlod.ServerLodTileStore;
import me.cortex.voxy.commonImpl.serverlod.Vlcp3BatchCodec;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientServerLodSync {
    private static ClientLodCache CACHE = new ClientLodCache(defaultCacheRoot());
    private static int bandwidthMbps = ServerLodConstants.DEFAULT_BANDWIDTH_MBPS;
    private static long lastHelloMillis;
    private static long nextSyncId = 1;
    private static volatile long activeSyncId;
    private static String lastManifestMessage = "not connected";
    private static int lastManifestTileCount;
    private static long importedTiles;
    private static long rejectedTiles;
    private static volatile int pendingSyncTicks;
    private static volatile String pendingSyncReason = "";
    private static volatile int pendingRendererReloadTicks;
    private static long lastRendererReloadImportedTiles;

    private ClientServerLodSync() {}

    public static void requestSyncNow() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            requestSyncWhenReady("client networking not ready");
            return;
        }
        lastHelloMillis = System.currentTimeMillis();
        long syncId = nextSyncId++;
        activeSyncId = syncId;
        PacketDistributor.sendToServer(new ServerLodPayloads.ClientHello(
                ServerLodConstants.PROTOCOL_VERSION,
                syncId,
                CACHE.manifestHash(),
                ServerLodConstants.DEFAULT_REQUEST_RADIUS,
                bandwidthMbps,
                CACHE.cachedHashes(ServerLodConstants.MAX_CLIENT_MANIFEST_HASHES)));
    }

    public static void requestSyncWhenReady(String reason) {
        pendingSyncReason = reason == null || reason.isBlank() ? "requested" : reason;
        pendingSyncTicks = Math.max(pendingSyncTicks, 80);
        lastManifestMessage = "sync pending: " + pendingSyncReason;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (pendingSyncTicks <= 0) {
            processPendingRendererReload();
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            pendingSyncTicks--;
            processPendingRendererReload();
            return;
        }
        pendingSyncTicks = 0;
        try {
            requestSyncNow();
        } catch (RuntimeException exception) {
            lastManifestMessage = "sync failed: " + exception.getClass().getName() + ": " + exception.getMessage();
            throw exception;
        } finally {
            processPendingRendererReload();
        }
    }

    public static void setBandwidthMbps(int value) {
        bandwidthMbps = Math.max(1, value);
    }

    public static int clearCache() {
        activeSyncId = nextSyncId++;
        pendingSyncTicks = 0;
        int deleted = CACHE.clear();
        CACHE = new ClientLodCache(defaultCacheRoot());
        return deleted;
    }

    public static void handleManifest(ServerLodPayloads.ServerManifest payload) {
        if (payload.clientSyncId() != activeSyncId) {
            lastManifestMessage = "ignored stale server LoD manifest sync_id=" + payload.clientSyncId()
                    + " active_sync_id=" + activeSyncId;
            return;
        }
        lastManifestMessage = payload.message();
        lastManifestTileCount = payload.visibleTiles().size();
        var missing = new ArrayList<ServerLodTileKey>();
        for (var metadata : payload.visibleTiles()) {
            if (CACHE.has(metadata)) {
                ServerLodDiagnostics.clientLodCacheHits.incrementAndGet();
            } else {
                ServerLodDiagnostics.clientLodCacheMisses.incrementAndGet();
                missing.add(metadata.key());
            }
        }
        if (!missing.isEmpty()) {
            PacketDistributor.sendToServer(new ServerLodPayloads.TileRequest(payload.clientSyncId(), missing, ServerLodConstants.MAX_TILE_BATCH_BYTES));
        }
    }

    public static void handleTileBatch(ServerLodPayloads.TileBatch payload) {
        if (payload.clientSyncId() != activeSyncId) {
            lastManifestMessage = "ignored stale server LoD tile batch sync_id=" + payload.clientSyncId()
                    + " active_sync_id=" + activeSyncId;
            return;
        }
        int imported = 0;
        int rejected = 0;
        try {
            long start = System.nanoTime();
            for (var tile : Vlcp3BatchCodec.decode(payload.compressedPayload(), payload.rawBytes())) {
                if (CACHE.store(tile)) {
                    imported++;
                } else {
                    rejected++;
                }
            }
            ServerLodDiagnostics.serverLodTileBatchImportNanos.addAndGet(System.nanoTime() - start);
            ServerLodDiagnostics.serverLodTileBatchesImported.incrementAndGet();
        } catch (Exception e) {
            rejected = payload.metadata().size();
            Logger.error("Failed to import Voxy server LoD tile batch " + payload.batchId() + ": " + e.getMessage());
        }
        importedTiles += imported;
        rejectedTiles += rejected;
        if (imported > 0) {
            requestRendererReloadAfterImport();
        }
        long ackStart = System.nanoTime();
        PacketDistributor.sendToServer(new ServerLodPayloads.TileAck(payload.clientSyncId(), payload.batchId(), imported, rejected, CACHE.manifestHashForAck()));
        ServerLodDiagnostics.serverLodTileAckNanos.addAndGet(System.nanoTime() - ackStart);
    }

    public static void handleInvalidate(ServerLodPayloads.ServerInvalidate payload) {
        int removed = 0;
        for (var key : payload.keys()) {
            if (CACHE.remove(key)) {
                removed++;
            }
        }
        ServerLodDiagnostics.serverLodInvalidationsReceived.addAndGet(removed);
        lastManifestMessage = "invalidated " + removed + " tiles: " + payload.reason();
    }

    public static String statusLine() {
        return "protocol=" + ServerLodConstants.PROTOCOL_VERSION
                + " cache=" + CACHE.root()
                + " active_sync_id=" + activeSyncId
                + " hashes=" + CACHE.cachedHashes(ServerLodConstants.MAX_CLIENT_MANIFEST_HASHES).size()
                + " last_manifest_tiles=" + lastManifestTileCount
                + " imported=" + importedTiles
                + " rejected=" + rejectedTiles
                + " bandwidth_mbps=" + bandwidthMbps
                + " last_hello_ms=" + lastHelloMillis
                + " pending_sync_ticks=" + pendingSyncTicks
                + " pending_renderer_reload_ticks=" + pendingRendererReloadTicks
                + " pending_reason=" + pendingSyncReason
                + " message=" + lastManifestMessage;
    }

    private static void requestRendererReloadAfterImport() {
        pendingRendererReloadTicks = Math.max(pendingRendererReloadTicks, 20);
    }

    private static void processPendingRendererReload() {
        if (pendingRendererReloadTicks <= 0) {
            return;
        }
        if (--pendingRendererReloadTicks > 0) {
            return;
        }
        if (importedTiles <= lastRendererReloadImportedTiles) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.levelRenderer == null || VoxyCommon.getInstance() == null) {
            pendingRendererReloadTicks = 20;
            return;
        }
        try {
            ((IGetVoxyRenderSystem) minecraft.levelRenderer).voxy$shutdownRenderer();
            VoxyCommon.shutdownInstance();
            System.gc();
            VoxyCommon.createInstance();
            minecraft.levelRenderer.allChanged();
            lastRendererReloadImportedTiles = importedTiles;
            Logger.info("Reloaded Voxy renderer after importing " + importedTiles + " synced server LoD tiles.");
        } catch (RuntimeException exception) {
            lastManifestMessage = "renderer reload after server LoD import failed: "
                    + exception.getClass().getName()
                    + ": "
                    + exception.getMessage();
            Logger.error(lastManifestMessage, exception);
            pendingRendererReloadTicks = 100;
        }
    }

    private static Path defaultCacheRoot() {
        try {
            return Minecraft.getInstance().gameDirectory.toPath().resolve("voxy_server_lod_cache");
        } catch (Throwable ignored) {
            return Path.of("voxy_server_lod_cache");
        }
    }

    public static Path cacheRoot() {
        return defaultCacheRoot();
    }

    private static final class ClientLodCache {
        private final Path root;
        private final ServerLodTileStore store;
        private final Set<String> cachedHashes = ConcurrentHashMap.newKeySet();
        private volatile boolean manifestHashDirty = true;
        private volatile String cachedManifestHash = "";

        private ClientLodCache(Path root) {
            this.root = root;
            this.store = new ServerLodTileStore(root, false);
            this.store.manifest(ServerLodConstants.MAX_CLIENT_MANIFEST_HASHES).stream()
                    .filter(this.store::has)
                    .map(metadata -> metadata.contentHash())
                    .forEach(this.cachedHashes::add);
            ServerLodDiagnostics.serverLodCacheIndexedTiles.set(this.store.indexedTileCount());
        }

        private Path root() {
            return this.root;
        }

        private boolean has(me.cortex.voxy.commonImpl.serverlod.ServerLodTileMetadata metadata) {
            return this.store.has(metadata);
        }

        private boolean store(ServerLodTile tile) {
            boolean stored = this.store.store(tile.metadata(), tile.compressedPayload()) == ServerLodTileStore.StoreResult.STORED;
            if (stored) {
                this.cachedHashes.add(tile.metadata().contentHash());
                this.manifestHashDirty = true;
                ServerLodDiagnostics.serverLodCacheIndexedTiles.set(this.store.indexedTileCount());
            }
            return stored;
        }

        private boolean remove(ServerLodTileKey key) {
            boolean removed = this.store.remove(key);
            if (removed) {
                this.cachedHashes.clear();
                this.store.manifest(ServerLodConstants.MAX_CLIENT_MANIFEST_HASHES).stream()
                        .filter(this.store::has)
                        .map(metadata -> metadata.contentHash())
                        .forEach(this.cachedHashes::add);
                this.manifestHashDirty = true;
                ServerLodDiagnostics.serverLodCacheIndexedTiles.set(this.store.localIndexedTileCount());
            }
            return removed;
        }

        private List<String> cachedHashes(int limit) {
            return this.cachedHashes.stream().sorted().limit(limit).toList();
        }

        private String manifestHash() {
            String hash = this.cachedManifestHash;
            if (!this.manifestHashDirty && !hash.isEmpty()) {
                ServerLodDiagnostics.serverLodCacheHashCachedUses.incrementAndGet();
                return hash;
            }
            synchronized (this) {
                if (!this.manifestHashDirty && !this.cachedManifestHash.isEmpty()) {
                    ServerLodDiagnostics.serverLodCacheHashCachedUses.incrementAndGet();
                    return this.cachedManifestHash;
                }
                this.cachedManifestHash = ServerLodConstants.sha256Hex(String.join("\n", cachedHashes(ServerLodConstants.MAX_CLIENT_MANIFEST_HASHES)));
                this.manifestHashDirty = false;
                ServerLodDiagnostics.serverLodCacheHashRebuilds.incrementAndGet();
                return this.cachedManifestHash;
            }
        }

        private String manifestHashForAck() {
            String hash = this.cachedManifestHash;
            if (!hash.isEmpty()) {
                ServerLodDiagnostics.serverLodCacheHashCachedUses.incrementAndGet();
                return hash;
            }
            return this.manifestHash();
        }

        private int clear() {
            return deleteChildren(this.root);
        }

        private int deleteChildren(Path path) {
            if (!Files.exists(path)) {
                return 0;
            }
            int deleted = 0;
            try (var stream = Files.list(path)) {
                for (var child : stream.toList()) {
                    if (Files.isDirectory(child)) {
                        deleted += deleteChildren(child);
                    }
                    if (Files.deleteIfExists(child)) {
                        deleted++;
                    }
                }
            } catch (IOException e) {
                Logger.error("Failed to clear Voxy server LoD cache: " + e.getMessage());
            }
            try {
                Files.createDirectories(this.root.resolve("blobs"));
                Files.createDirectories(this.root.resolve("index"));
            } catch (IOException ignored) {
            }
            return deleted;
        }
    }
}
