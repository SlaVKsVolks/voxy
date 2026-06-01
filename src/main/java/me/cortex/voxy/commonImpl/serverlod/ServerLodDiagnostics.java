package me.cortex.voxy.commonImpl.serverlod;

import me.cortex.voxy.common.VoxyHandoffPolicy;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.atomic.AtomicLong;

public final class ServerLodDiagnostics {
    public static final AtomicLong serverLodTilesStored = new AtomicLong();
    public static final AtomicLong serverLodTilesSent = new AtomicLong();
    public static final AtomicLong clientLodCacheHits = new AtomicLong();
    public static final AtomicLong clientLodCacheMisses = new AtomicLong();
    public static final AtomicLong lodSyncBytesSent = new AtomicLong();
    public static final AtomicLong provisionalUploadsAccepted = new AtomicLong();
    public static final AtomicLong provisionalUploadsRejected = new AtomicLong();
    public static final AtomicLong realOverPreviewReplacements = new AtomicLong();
    public static final AtomicLong staleEpochRejected = new AtomicLong();
    public static final AtomicLong lowerConfidenceRejected = new AtomicLong();
    public static final AtomicLong firstLodVisibleMs = new AtomicLong(-1);
    public static final AtomicLong serverLodCacheRenderLoads = new AtomicLong();
    public static final AtomicLong serverLodCacheRenderMisses = new AtomicLong();
    public static final AtomicLong serverLodCacheRenderRejected = new AtomicLong();
    public static final AtomicLong boundaryRingRenderLoads = new AtomicLong();
    public static final AtomicLong boundaryRingRenderMisses = new AtomicLong();
    public static final AtomicLong boundaryRingParentFallbackSections = new AtomicLong();
    public static final AtomicLong boundaryRingRejectedSections = new AtomicLong();
    public static final AtomicLong serverLodCacheIndexedTiles = new AtomicLong();
    public static final AtomicLong serverLodCacheIteratedPositions = new AtomicLong();
    public static final AtomicLong serverLodCacheDecodeFailures = new AtomicLong();
    public static final AtomicLong serverLodCacheSynthesizedParents = new AtomicLong();
    public static final AtomicLong serverLodCacheHashRebuilds = new AtomicLong();
    public static final AtomicLong serverLodCacheHashCachedUses = new AtomicLong();
    public static final AtomicLong serverLodTileBatchImportNanos = new AtomicLong();
    public static final AtomicLong serverLodTileAckNanos = new AtomicLong();
    public static final AtomicLong serverLodTileBatchesImported = new AtomicLong();
    public static final AtomicLong serverLodTilesPublishedFromSections = new AtomicLong();
    public static final AtomicLong serverLodTilesRejectedFromSections = new AtomicLong();
    public static final AtomicLong serverLodInvalidationsReceived = new AtomicLong();
    public static final AtomicLong authoredChunksRequested = new AtomicLong();
    public static final AtomicLong authoredChunksGenerated = new AtomicLong();
    public static final AtomicLong authoredSectionsAccepted = new AtomicLong();
    public static final AtomicLong authoredSectionsRejectedMissingLight = new AtomicLong();
    public static final AtomicLong authoredTilesPublished = new AtomicLong();
    public static final AtomicLong authoredTilesRejected = new AtomicLong();

    private ServerLodDiagnostics() {}

    public static String statusLine() {
        return "stored=" + serverLodTilesStored.get()
                + " sent=" + serverLodTilesSent.get()
                + " cache_hits=" + clientLodCacheHits.get()
                + " cache_misses=" + clientLodCacheMisses.get()
                + " bytes_sent=" + lodSyncBytesSent.get()
                + " first_lod_visible_ms=" + firstLodVisibleMs.get()
                + " uploads_ok=" + provisionalUploadsAccepted.get()
                + " uploads_rejected=" + provisionalUploadsRejected.get()
                + " real_over_preview=" + realOverPreviewReplacements.get()
                + " render_distance_slider_mode=" + VoxyHandoffPolicy.renderDistanceSliderMode()
                + " visual_distance=" + VoxyHandoffPolicy.visualTerrainDistanceChunks()
                + " vanilla_real_distance=" + VoxyHandoffPolicy.realRenderDistanceChunks()
                + " lod_start=" + VoxyHandoffPolicy.handoffStartChunks()
                + " lod_end=" + VoxyHandoffPolicy.voxyLodEndChunks()
                + " render_loads=" + serverLodCacheRenderLoads.get()
                + " render_misses=" + serverLodCacheRenderMisses.get()
                + " render_rejected=" + serverLodCacheRenderRejected.get()
                + " synthesized_parents=" + serverLodCacheSynthesizedParents.get()
                + " boundary_loads=" + boundaryRingRenderLoads.get()
                + " boundary_misses=" + boundaryRingRenderMisses.get()
                + " boundary_parent_fallbacks=" + boundaryRingParentFallbackSections.get()
                + " boundary_rejected=" + boundaryRingRejectedSections.get()
                + " boundary_gap_verdict=" + VoxyHandoffPolicy.boundaryGapVerdict(
                        boundaryRingRenderLoads.get(),
                        boundaryRingParentFallbackSections.get(),
                        boundaryRingRenderMisses.get())
                + " cache_indexed=" + serverLodCacheIndexedTiles.get()
                + " cache_iterated=" + serverLodCacheIteratedPositions.get()
                + " cache_decode_failures=" + serverLodCacheDecodeFailures.get()
                + " manifest_hash_rebuilds=" + serverLodCacheHashRebuilds.get()
                + " manifest_hash_cached=" + serverLodCacheHashCachedUses.get()
                + " tile_batches_imported=" + serverLodTileBatchesImported.get()
                + " section_published=" + serverLodTilesPublishedFromSections.get()
                + " section_rejected=" + serverLodTilesRejectedFromSections.get()
                + " authored_chunks=" + authoredChunksGenerated.get() + "/" + authoredChunksRequested.get()
                + " authored_sections=" + authoredSectionsAccepted.get()
                + " authored_missing_light=" + authoredSectionsRejectedMissingLight.get()
                + " authored_tiles=" + authoredTilesPublished.get()
                + " authored_tiles_rejected=" + authoredTilesRejected.get()
                + " neoforge_contract_pass=" + ServerLodNeoForgeContract.snapshot().pass();
    }

    public static String json() {
        return "{"
                + "\"server_lod_tiles_stored\":" + serverLodTilesStored.get() + ","
                + "\"server_lod_tiles_sent\":" + serverLodTilesSent.get() + ","
                + "\"client_lod_cache_hits\":" + clientLodCacheHits.get() + ","
                + "\"client_lod_cache_misses\":" + clientLodCacheMisses.get() + ","
                + "\"lod_sync_bytes_sent\":" + lodSyncBytesSent.get() + ","
                + "\"first_lod_visible_ms\":" + firstLodVisibleMs.get() + ","
                + "\"provisional_uploads_accepted\":" + provisionalUploadsAccepted.get() + ","
                + "\"provisional_uploads_rejected\":" + provisionalUploadsRejected.get() + ","
                + "\"real_over_preview_replacements\":" + realOverPreviewReplacements.get() + ","
                + "\"render_distance_slider_mode\":\"" + VoxyHandoffPolicy.renderDistanceSliderMode() + "\","
                + "\"visual_terrain_distance_chunks\":" + VoxyHandoffPolicy.visualTerrainDistanceChunks() + ","
                + "\"vanilla_real_render_distance_chunks\":" + VoxyHandoffPolicy.realRenderDistanceChunks() + ","
                + "\"voxy_lod_start_chunks\":" + VoxyHandoffPolicy.handoffStartChunks() + ","
                + "\"voxy_lod_end_chunks\":" + VoxyHandoffPolicy.voxyLodEndChunks() + ","
                + "\"stale_epoch_rejected\":" + staleEpochRejected.get() + ","
                + "\"lower_confidence_rejected\":" + lowerConfidenceRejected.get() + ","
                + "\"server_lod_cache_render_loads\":" + serverLodCacheRenderLoads.get() + ","
                + "\"server_lod_cache_render_misses\":" + serverLodCacheRenderMisses.get() + ","
                + "\"server_lod_cache_render_rejected\":" + serverLodCacheRenderRejected.get() + ","
                + "\"server_lod_cache_synthesized_parents\":" + serverLodCacheSynthesizedParents.get() + ","
                + "\"boundary_ring_loaded_sections\":" + boundaryRingRenderLoads.get() + ","
                + "\"boundary_ring_missing_sections\":" + boundaryRingRenderMisses.get() + ","
                + "\"boundary_ring_parent_fallback_sections\":" + boundaryRingParentFallbackSections.get() + ","
                + "\"boundary_ring_rejected_sections\":" + boundaryRingRejectedSections.get() + ","
                + "\"terrain_coverage_verdict\":\"" + VoxyHandoffPolicy.boundaryGapVerdict(
                        boundaryRingRenderLoads.get(),
                        boundaryRingParentFallbackSections.get(),
                        boundaryRingRenderMisses.get()) + "\","
                + "\"boundary_gap_verdict\":\"" + VoxyHandoffPolicy.boundaryGapVerdict(
                        boundaryRingRenderLoads.get(),
                        boundaryRingParentFallbackSections.get(),
                        boundaryRingRenderMisses.get()) + "\","
                + "\"server_lod_cache_indexed_tiles\":" + serverLodCacheIndexedTiles.get() + ","
                + "\"server_lod_cache_iterated_positions\":" + serverLodCacheIteratedPositions.get() + ","
                + "\"server_lod_cache_decode_failures\":" + serverLodCacheDecodeFailures.get() + ","
                + "\"server_lod_cache_manifest_hash_rebuilds\":" + serverLodCacheHashRebuilds.get() + ","
                + "\"server_lod_cache_manifest_hash_cached_uses\":" + serverLodCacheHashCachedUses.get() + ","
                + "\"server_lod_tile_batch_import_nanos\":" + serverLodTileBatchImportNanos.get() + ","
                + "\"server_lod_tile_ack_nanos\":" + serverLodTileAckNanos.get() + ","
                + "\"server_lod_tile_batches_imported\":" + serverLodTileBatchesImported.get() + ","
                + "\"server_lod_tiles_published_from_sections\":" + serverLodTilesPublishedFromSections.get() + ","
                + "\"server_lod_tiles_rejected_from_sections\":" + serverLodTilesRejectedFromSections.get() + ","
                + "\"server_lod_invalidations_received\":" + serverLodInvalidationsReceived.get() + ","
                + "\"authored_chunks_requested\":" + authoredChunksRequested.get() + ","
                + "\"authored_chunks_generated\":" + authoredChunksGenerated.get() + ","
                + "\"authored_sections_accepted\":" + authoredSectionsAccepted.get() + ","
                + "\"authored_sections_rejected_missing_light\":" + authoredSectionsRejectedMissingLight.get() + ","
                + "\"authored_tiles_published\":" + authoredTilesPublished.get() + ","
                + "\"authored_tiles_rejected\":" + authoredTilesRejected.get() + ","
                + "\"server_lod_contract\":" + serverLodContractJson()
                + "}";
    }

    public static String serverLodContractJson() {
        return ServerLodNeoForgeContract.json();
    }

    public static void recordBoundaryLoad(WorldSection section, boolean loaded) {
        if (!VoxyHandoffPolicy.isBoundaryRingSection(section)) {
            return;
        }
        if (loaded) {
            boundaryRingRenderLoads.incrementAndGet();
            if (section.lvl > 0) {
                boundaryRingParentFallbackSections.incrementAndGet();
            }
        } else {
            boundaryRingRenderMisses.incrementAndGet();
        }
    }

    public static void recordBoundaryRejected(WorldSection section) {
        if (VoxyHandoffPolicy.isBoundaryRingSection(section)) {
            boundaryRingRejectedSections.incrementAndGet();
        }
    }
}
