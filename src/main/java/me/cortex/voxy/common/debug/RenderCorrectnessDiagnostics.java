package me.cortex.voxy.common.debug;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyHandoffPolicy;
import me.cortex.voxy.commonImpl.serverlod.ServerLodDiagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class RenderCorrectnessDiagnostics {
    private static final boolean ENABLED_PROPERTY = Boolean.parseBoolean(System.getProperty("voxy.renderCorrectnessDiagnostics", "false"));
    private static final Path OUTPUT_FILE = Path.of(
            System.getProperty("voxy.renderCorrectnessDiagnosticsFile", "testharness/voxy_render_correctness.ndjson")
    );
    private static final Path PROBE_TARGET_FILE = Path.of(
            System.getProperty("voxy.renderCorrectnessProbeTargetFile", "testharness/voxy_gate_a_target_section.json")
    );
    private static final Path SUMMARY_FILE = Path.of(
            System.getProperty("voxy.renderCorrectnessSummaryFile", "testharness/voxy_deep_diagnostics_summary.json")
    );
    private static final boolean TRUNCATE_ON_START = Boolean.parseBoolean(
            System.getProperty("voxy.renderCorrectnessDiagnosticsTruncateOnStart", "true")
    );
    private static final long MAX_LINES = Long.getLong("voxy.renderCorrectnessDiagnosticsMaxLines", 1_000_000L);
    private static final long MAX_BYTES = Long.getLong("voxy.renderCorrectnessDiagnosticsMaxBytes", 512L * 1024L * 1024L);
    private static volatile boolean runtimeEnabled = ENABLED_PROPERTY;
    private static volatile String activeProfile = ENABLED_PROPERTY ? "launch_property" : "off";
    private static volatile boolean callTraceEnabled = Boolean.parseBoolean(
            System.getProperty("voxy.renderCorrectnessCallTrace", "false")
    );
    private static volatile boolean nodeTraceEnabled = Boolean.parseBoolean(
            System.getProperty("voxy.renderCorrectnessNodeTrace", "false")
    );
    private static volatile boolean renderFrameTraceEnabled = Boolean.parseBoolean(
            System.getProperty("voxy.renderCorrectnessRenderFrameTrace", Boolean.toString(ENABLED_PROPERTY))
    );
    private static volatile boolean ingestTraceEnabled = Boolean.parseBoolean(
            System.getProperty("voxy.renderCorrectnessIngestTrace", Boolean.toString(ENABLED_PROPERTY))
    );
    private static volatile boolean guardTraceEnabled = Boolean.parseBoolean(
            System.getProperty("voxy.renderCorrectnessGuardTrace", "true")
    );
    private static final int GEOMETRY_COVERAGE_SAMPLE_INTERVAL = Integer.getInteger(
            "voxy.renderCorrectnessGeometryCoverageSampleInterval",
            60
    );
    private static final boolean ASYNC_WRITES = Boolean.parseBoolean(
            System.getProperty("voxy.renderCorrectnessAsyncWrites", "true")
    );
    private static final int WRITE_QUEUE_CAPACITY = Integer.getInteger(
            "voxy.renderCorrectnessWriteQueueCapacity",
            16_384
    );
    private static final int WRITE_BATCH_SIZE = Integer.getInteger(
            "voxy.renderCorrectnessWriteBatchSize",
            256
    );
    private static final ArrayBlockingQueue<String> WRITE_QUEUE = new ArrayBlockingQueue<>(
            Math.max(256, WRITE_QUEUE_CAPACITY)
    );

    private static final AtomicBoolean initLogged = new AtomicBoolean(false);
    private static final AtomicBoolean ioFailureLogged = new AtomicBoolean(false);
    private static final AtomicBoolean limitLogged = new AtomicBoolean(false);
    private static final AtomicBoolean queueDropLogged = new AtomicBoolean(false);
    private static final AtomicBoolean writerStarted = new AtomicBoolean(false);
    private static final AtomicBoolean outputInitialized = new AtomicBoolean(false);
    private static final AtomicLong linesWritten = new AtomicLong(0L);
    private static final AtomicLong bytesWritten = new AtomicLong(-1L);
    private static final LongAdder droppedLines = new LongAdder();
    private static volatile ProbeTarget probeTarget;
    private static volatile long probeTargetLastModified = Long.MIN_VALUE;

    public static final LongAdder uploadIngestTotal = new LongAdder();
    public static final LongAdder uploadIngestZeroTotal = new LongAdder();
    public static final LongAdder uploadIngestSkippedNoWorld = new LongAdder();
    public static final LongAdder uploadIngestSkippedChunkStatus = new LongAdder();
    public static final LongAdder chunkBoundAdd = new LongAdder();
    public static final LongAdder chunkBoundRemove = new LongAdder();
    public static final LongAdder depthGuardSkips = new LongAdder();
    public static final LongAdder projectionGuardSkips = new LongAdder();
    public static final LongAdder probeMatchedIngestTotal = new LongAdder();
    public static final LongAdder probeMatchedZeroIngestTotal = new LongAdder();
    public static final LongAdder ingestProcessedTotal = new LongAdder();
    public static final LongAdder ingestFailedTotal = new LongAdder();
    public static final LongAdder nodeInvalidationTotal = new LongAdder();
    public static final LongAdder nodeGeometryChangeTotal = new LongAdder();
    public static final LongAdder nodeRequestStartTotal = new LongAdder();
    public static final LongAdder nodeRequestFinishTotal = new LongAdder();
    public static final LongAdder nodeRequestRescheduleTotal = new LongAdder();
    public static final LongAdder nodeZeroChildSkipTotal = new LongAdder();
    public static final LongAdder nodeStateFlickerTotal = new LongAdder();
    public static final LongAdder oldEpochPublishRejectedTotal = new LongAdder();
    public static final LongAdder oldEpochPublishAllowedTotal = new LongAdder();
    public static final LongAdder provisionalOverRealRejectedTotal = new LongAdder();
    public static final LongAdder provisionalOverRealAllowedTotal = new LongAdder();
    public static final LongAdder lowerConfidenceRejectedTotal = new LongAdder();
    public static final LongAdder lowerConfidenceAllowedTotal = new LongAdder();
    public static final LongAdder previewZeroClearRejectedTotal = new LongAdder();
    public static final LongAdder missingLightReadyRejectedTotal = new LongAdder();
    public static final LongAdder syntheticLightReadyRejectedTotal = new LongAdder();
    public static final LongAdder renderFrameTotal = new LongAdder();
    public static final LongAdder viewportReadyTotal = new LongAdder();
    public static final LongAdder renderOpaqueStartTotal = new LongAdder();
    public static final LongAdder renderOpaqueEndTotal = new LongAdder();
    public static final LongAdder surfaceColumnsTotal = new LongAdder();
    public static final LongAdder surfaceColumnsWithoutRetainedVoxel = new LongAdder();
    public static final LongAdder oceanFloorColumnsWithoutRetainedVoxel = new LongAdder();
    public static final LongAdder chunkBoundaryExposureMisses = new LongAdder();
    public static final LongAdder previewGapSections = new LongAdder();
    public static final LongAdder mipperAirOverSurfaceRejected = new LongAdder();
    public static final LongAdder mipperBuriedShellOverSurfaceRejected = new LongAdder();
    public static final LongAdder mipperBuriedSolidOverFoliageRejected = new LongAdder();
    public static final LongAdder oreLeakSurfaceRepresentatives = new LongAdder();
    public static final LongAdder mipperWaterFloorPreserved = new LongAdder();
    public static final LongAdder lateGeometryAcceptedActiveNode = new LongAdder();
    public static final LongAdder lateGeometryRejectedStaleEpoch = new LongAdder();
    public static final LongAdder lateGeometryDiscardedActiveNode = new LongAdder();
    public static final LongAdder geometryWatcherEpochMismatch = new LongAdder();
    public static final LongAdder parentRetainedUntilChildCommit = new LongAdder();
    public static final LongAdder atomicChildCommitTotal = new LongAdder();
    public static final LongAdder previewSectionsWritten = new LongAdder();
    public static final LongAdder realChunkSectionsWritten = new LongAdder();
    public static final LongAdder previewSupersededByReal = new LongAdder();
    public static final LongAdder previewOverRealRejected = new LongAdder();
    public static final LongAdder realChunkImportFailures = new LongAdder();
    private static final AtomicLong lastFrameId = new AtomicLong(-1L);
    private static final AtomicLong lastCameraHash = new AtomicLong(0L);
    private static final AtomicLong cameraStableFrameCount = new AtomicLong(0L);
    private static final AtomicLong lastTopLevelNodes = new AtomicLong(-1L);
    private static final AtomicLong lastActiveSections = new AtomicLong(-1L);
    private static final AtomicLong lastGeometrySections = new AtomicLong(-1L);
    private static final AtomicLong lastActiveRequests = new AtomicLong(-1L);
    private static final AtomicLong lastUsedGeometryBytes = new AtomicLong(-1L);
    private static volatile String lastPipeline = "";

    private RenderCorrectnessDiagnostics() {
    }

    public static void configureRuntimeProfile(String profile) {
        String normalized = profile == null ? "normal" : profile.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "off" -> {
                runtimeEnabled = false;
                activeProfile = "off";
                callTraceEnabled = false;
                nodeTraceEnabled = false;
                renderFrameTraceEnabled = false;
                ingestTraceEnabled = false;
                guardTraceEnabled = false;
            }
            case "deep" -> {
                runtimeEnabled = true;
                activeProfile = "deep";
                callTraceEnabled = true;
                nodeTraceEnabled = true;
                renderFrameTraceEnabled = true;
                ingestTraceEnabled = true;
                guardTraceEnabled = true;
            }
            case "node" -> {
                runtimeEnabled = true;
                activeProfile = "node";
                callTraceEnabled = false;
                nodeTraceEnabled = true;
                renderFrameTraceEnabled = true;
                ingestTraceEnabled = true;
                guardTraceEnabled = true;
            }
            case "ingest" -> {
                runtimeEnabled = true;
                activeProfile = "ingest";
                callTraceEnabled = false;
                nodeTraceEnabled = false;
                renderFrameTraceEnabled = true;
                ingestTraceEnabled = true;
                guardTraceEnabled = true;
            }
            default -> {
                runtimeEnabled = true;
                activeProfile = "normal";
                callTraceEnabled = false;
                nodeTraceEnabled = false;
                renderFrameTraceEnabled = true;
                ingestTraceEnabled = true;
                guardTraceEnabled = true;
            }
        }
        Logger.info("Voxy diagnostics profile=",
                activeProfile,
                " callTrace=",
                Boolean.toString(callTraceEnabled),
                " nodeTrace=",
                Boolean.toString(nodeTraceEnabled),
                " renderFrameTrace=",
                Boolean.toString(renderFrameTraceEnabled),
                " ingestTrace=",
                Boolean.toString(ingestTraceEnabled),
                " guardTrace=",
                Boolean.toString(guardTraceEnabled));
    }

    public static boolean isRuntimeEnabled() {
        return runtimeEnabled;
    }

    public static String activeProfile() {
        return activeProfile;
    }

    public static synchronized void resetOutput() {
        if (!runtimeEnabled) {
            return;
        }
        try {
            Path parent = OUTPUT_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(OUTPUT_FILE);
            linesWritten.set(0L);
            bytesWritten.set(0L);
            initLogged.set(false);
            ioFailureLogged.set(false);
            limitLogged.set(false);
            queueDropLogged.set(false);
            outputInitialized.set(true);
            WRITE_QUEUE.clear();
            droppedLines.reset();
            uploadIngestTotal.reset();
            uploadIngestZeroTotal.reset();
            uploadIngestSkippedNoWorld.reset();
            uploadIngestSkippedChunkStatus.reset();
            chunkBoundAdd.reset();
            chunkBoundRemove.reset();
            depthGuardSkips.reset();
            projectionGuardSkips.reset();
            probeMatchedIngestTotal.reset();
            probeMatchedZeroIngestTotal.reset();
            ingestProcessedTotal.reset();
            ingestFailedTotal.reset();
            nodeInvalidationTotal.reset();
            nodeGeometryChangeTotal.reset();
            nodeRequestStartTotal.reset();
            nodeRequestFinishTotal.reset();
            nodeRequestRescheduleTotal.reset();
            nodeZeroChildSkipTotal.reset();
            nodeStateFlickerTotal.reset();
            oldEpochPublishRejectedTotal.reset();
            oldEpochPublishAllowedTotal.reset();
            provisionalOverRealRejectedTotal.reset();
            provisionalOverRealAllowedTotal.reset();
            lowerConfidenceRejectedTotal.reset();
            lowerConfidenceAllowedTotal.reset();
            previewZeroClearRejectedTotal.reset();
            missingLightReadyRejectedTotal.reset();
            syntheticLightReadyRejectedTotal.reset();
            renderFrameTotal.reset();
            viewportReadyTotal.reset();
            renderOpaqueStartTotal.reset();
            renderOpaqueEndTotal.reset();
            surfaceColumnsTotal.reset();
            surfaceColumnsWithoutRetainedVoxel.reset();
            oceanFloorColumnsWithoutRetainedVoxel.reset();
            chunkBoundaryExposureMisses.reset();
            previewGapSections.reset();
            mipperAirOverSurfaceRejected.reset();
            mipperBuriedShellOverSurfaceRejected.reset();
            mipperBuriedSolidOverFoliageRejected.reset();
            oreLeakSurfaceRepresentatives.reset();
            mipperWaterFloorPreserved.reset();
            lateGeometryAcceptedActiveNode.reset();
            lateGeometryRejectedStaleEpoch.reset();
            lateGeometryDiscardedActiveNode.reset();
            geometryWatcherEpochMismatch.reset();
            parentRetainedUntilChildCommit.reset();
            atomicChildCommitTotal.reset();
            previewSectionsWritten.reset();
            realChunkSectionsWritten.reset();
            previewSupersededByReal.reset();
            previewOverRealRejected.reset();
            realChunkImportFailures.reset();
            lastFrameId.set(-1L);
            lastCameraHash.set(0L);
            cameraStableFrameCount.set(0L);
            lastTopLevelNodes.set(-1L);
            lastActiveSections.set(-1L);
            lastGeometrySections.set(-1L);
            lastActiveRequests.set(-1L);
            lastUsedGeometryBytes.set(-1L);
            lastPipeline = "";
            Logger.info("Render correctness diagnostics reset. Output=", OUTPUT_FILE.toAbsolutePath().toString());
        } catch (IOException ioe) {
            if (ioFailureLogged.compareAndSet(false, true)) {
                Logger.error("Render correctness diagnostics reset failure", ioe);
            }
        }
    }

    public static void ingest(
            String stage,
            int x,
            int y,
            int z,
            boolean sectionAir,
            boolean queued,
            String reason
    ) {
        if (queued && "sodium_upload".equals(stage)) {
            uploadIngestTotal.increment();
            if (sectionAir) {
                uploadIngestZeroTotal.increment();
            }
        }
        if (queued && isPreviewStage(stage)) {
            previewSectionsWritten.increment();
        }
        if (queued && isRealChunkStage(stage)) {
            realChunkSectionsWritten.increment();
        }
        if (!queued && isRealChunkFailure(stage, reason)) {
            realChunkImportFailures.increment();
        }
        if (!queued && ("synthetic_preview_skip".equals(stage) || "trusted_light_already_present".equals(reason))) {
            previewOverRealRejected.increment();
        }
        event("ingest", stage, x, y, z, sectionAir, queued, reason);
    }

    private static boolean isPreviewStage(String stage) {
        return stage != null && (stage.startsWith("lod_compiler_import")
                || stage.startsWith("surface_preview")
                || stage.startsWith("synthetic_preview"));
    }

    private static boolean isRealChunkStage(String stage) {
        return stage != null && ("chunk_load".equals(stage)
                || "raw_snapshot".equals(stage)
                || "sodium_upload".equals(stage)
                || "chunky_neoforge".equals(stage)
                || stage.startsWith("chunk_load_")
                || stage.startsWith("raw_snapshot_"));
    }

    private static boolean isRealChunkFailure(String stage, String reason) {
        if (stage == null) {
            return false;
        }
        if (stage.endsWith("_execute_failed") || stage.endsWith("_snapshot_failed")) {
            return true;
        }
        if ("chunky_neoforge".equals(stage)) {
            return "null_future".equals(reason)
                    || "missing_server_level".equals(reason)
                    || "missing_full_chunk".equals(reason);
        }
        // Sodium upload and raw/chunk snapshot paths emit many expected
        // lifecycle skips while chunks are loading or light data is pending.
        // Those are tracked by their own counters/log events and must not
        // fail the authored LoD gate as real import failures.
        return false;
    }

    public static void chunkBound(String action, long pos) {
        if ("add".equals(action)) {
            chunkBoundAdd.increment();
        } else if ("remove".equals(action)) {
            chunkBoundRemove.increment();
        }
        event("chunk_bound", action, pos, "", "");
    }

    public static void ingestLifecycle(String stage, int queueSize, long processed, long failed, long durationNanos, String reason) {
        if ("processed".equals(stage)) {
            ingestProcessedTotal.increment();
        } else if ("failed".equals(stage)) {
            ingestFailedTotal.increment();
        }
        if (!ingestTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"ingest_lifecycle\",\"stage\":\"" + escape(stage)
                + "\",\"queue_size\":" + queueSize
                + ",\"processed_total\":" + processed
                + ",\"failed_total\":" + failed
                + ",\"duration_nanos\":" + durationNanos
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static void depthGuard(String stage, String reason, int sourceFramebuffer, int targetFramebuffer, int width, int height) {
        depthGuardSkips.increment();
        event("depth_guard", stage, sourceFramebuffer, targetFramebuffer, width, height, reason);
    }

    public static void projectionGuard(String stage, String reason) {
        projectionGuardSkips.increment();
        event("projection_guard", stage, 0, 0, 0, 0, reason);
    }

    public static void nodeEvent(String stage, long pos, int nodeId, int before, int after, String reason) {
        if ("invalidate".equals(stage)) {
            nodeInvalidationTotal.increment();
        } else if ("geometry_change".equals(stage)) {
            nodeGeometryChangeTotal.increment();
        } else if ("request_start".equals(stage)) {
            nodeRequestStartTotal.increment();
        } else if ("request_finish".equals(stage)) {
            nodeRequestFinishTotal.increment();
        } else if ("request_reschedule".equals(stage)) {
            nodeRequestRescheduleTotal.increment();
        } else if ("zero_child_skip".equals(stage)) {
            nodeZeroChildSkipTotal.increment();
        } else if ("VOXY_NODE_STATE_FLICKER_CONFIRMED".equals(stage)) {
            nodeStateFlickerTotal.increment();
        }
        if (!nodeTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"node_transition\",\"stage\":\"" + escape(stage)
                + "\",\"pos\":" + pos
                + ",\"node_id\":" + nodeId
                + ",\"before\":" + before
                + ",\"after\":" + after
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static void geometryPublication(String stage, long pos, int nodeId, long incomingEpoch, long expectedEpoch, String reason) {
        switch (stage) {
            case "late_geometry_accepted_active_node" -> lateGeometryAcceptedActiveNode.increment();
            case "late_geometry_rejected_stale_epoch" -> lateGeometryRejectedStaleEpoch.increment();
            case "late_geometry_discarded_active_node" -> lateGeometryDiscardedActiveNode.increment();
            case "geometry_watcher_epoch_mismatch" -> geometryWatcherEpochMismatch.increment();
            case "parent_retained_until_child_commit" -> parentRetainedUntilChildCommit.increment();
            case "atomic_child_commit_total" -> atomicChildCommitTotal.increment();
            default -> {
            }
        }
        if (!nodeTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"geometry_publication\""
                + ",\"stage\":\"" + escape(stage) + "\""
                + ",\"pos\":" + pos
                + ",\"node_id\":" + nodeId
                + ",\"incoming_epoch\":" + incomingEpoch
                + ",\"expected_epoch\":" + expectedEpoch
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static void publicationGuard(
            String stage,
            long pos,
            long currentEpoch,
            long incomingEpoch,
            String currentSource,
            String incomingSource,
            String currentConfidence,
            String incomingConfidence,
            String reason
    ) {
        switch (stage) {
            case "old_epoch_rejected" -> oldEpochPublishRejectedTotal.increment();
            case "old_epoch_allowed" -> oldEpochPublishAllowedTotal.increment();
            case "provisional_over_real_rejected" -> provisionalOverRealRejectedTotal.increment();
            case "provisional_over_real_allowed" -> provisionalOverRealAllowedTotal.increment();
            case "lower_confidence_rejected" -> lowerConfidenceRejectedTotal.increment();
            case "lower_confidence_allowed" -> lowerConfidenceAllowedTotal.increment();
            case "preview_zero_clear_rejected" -> previewZeroClearRejectedTotal.increment();
            case "missing_light_ready_rejected" -> missingLightReadyRejectedTotal.increment();
            case "synthetic_light_ready_rejected" -> syntheticLightReadyRejectedTotal.increment();
            default -> {
            }
        }
        if (!ingestTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"publication_guard\""
                + ",\"stage\":\"" + escape(stage) + "\""
                + ",\"pos\":" + pos
                + ",\"current_epoch\":" + currentEpoch
                + ",\"incoming_epoch\":" + incomingEpoch
                + ",\"current_source\":\"" + escape(currentSource) + "\""
                + ",\"incoming_source\":\"" + escape(incomingSource) + "\""
                + ",\"current_confidence\":\"" + escape(currentConfidence) + "\""
                + ",\"incoming_confidence\":\"" + escape(incomingConfidence) + "\""
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static void call(String subsystem, String method, String stage, String reason) {
        if (!callTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"voxy_call\""
                + ",\"subsystem\":\"" + escape(subsystem) + "\""
                + ",\"method\":\"" + escape(method) + "\""
                + ",\"stage\":\"" + escape(stage) + "\""
                + ",\"thread\":\"" + escape(Thread.currentThread().getName()) + "\""
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static void renderFrame(
            String stage,
            long frameId,
            String pipeline,
            int width,
            int height,
            int topLevelNodes,
            int activeSections,
            int geometrySections,
            int activeRequests,
            long usedGeometryBytes,
            String reason
    ) {
        renderFrameTotal.increment();
        if ("setupViewport".equals(stage)) {
            viewportReadyTotal.increment();
        } else if ("renderOpaque_start".equals(stage)) {
            renderOpaqueStartTotal.increment();
        } else if ("renderOpaque_end".equals(stage)) {
            renderOpaqueEndTotal.increment();
        }
        lastFrameId.set(frameId);
        lastPipeline = pipeline == null ? "" : pipeline;
        lastTopLevelNodes.set(topLevelNodes);
        lastActiveSections.set(activeSections);
        lastGeometrySections.set(geometrySections);
        lastActiveRequests.set(activeRequests);
        lastUsedGeometryBytes.set(usedGeometryBytes);
        if (!renderFrameTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"voxy_render_frame\""
                + ",\"stage\":\"" + escape(stage) + "\""
                + ",\"frame_id\":" + frameId
                + ",\"pipeline\":\"" + escape(pipeline) + "\""
                + ",\"width\":" + width
                + ",\"height\":" + height
                + ",\"top_level_nodes\":" + topLevelNodes
                + ",\"active_sections\":" + activeSections
                + ",\"geometry_sections\":" + geometrySections
                + ",\"active_requests\":" + activeRequests
                + ",\"used_geometry_bytes\":" + usedGeometryBytes
                + ",\"thread\":\"" + escape(Thread.currentThread().getName()) + "\""
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static boolean shouldSampleGeometryCoverage(long frameId) {
        return runtimeEnabled
                && renderFrameTraceEnabled
                && GEOMETRY_COVERAGE_SAMPLE_INTERVAL > 0
                && frameId >= 0L
                && frameId % GEOMETRY_COVERAGE_SAMPLE_INTERVAL == 0L;
    }

    public static void geometryCoverage(
            long frameId,
            int activeEntries,
            int requestSingle,
            int requestChild,
            int leafWithGeometry,
            int leafEmptyGeometry,
            int leafNullGeometry,
            int leafInFlight,
            int innerWithGeometry,
            int innerEmptyGeometry,
            int innerNullGeometry,
            int innerInFlight,
            int topLevelRequests,
            int topLevelReady,
            int committedTopLevelNodeIds,
            String reason
    ) {
        if (!renderFrameTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"geometry_coverage\""
                + ",\"frame_id\":" + frameId
                + ",\"active_entries\":" + activeEntries
                + ",\"request_single\":" + requestSingle
                + ",\"request_child\":" + requestChild
                + ",\"leaf_with_geometry\":" + leafWithGeometry
                + ",\"leaf_empty_geometry\":" + leafEmptyGeometry
                + ",\"leaf_null_geometry\":" + leafNullGeometry
                + ",\"leaf_inflight\":" + leafInFlight
                + ",\"inner_with_geometry\":" + innerWithGeometry
                + ",\"inner_empty_geometry\":" + innerEmptyGeometry
                + ",\"inner_null_geometry\":" + innerNullGeometry
                + ",\"inner_inflight\":" + innerInFlight
                + ",\"top_level_requests\":" + topLevelRequests
                + ",\"top_level_ready\":" + topLevelReady
                + ",\"committed_top_level_node_ids\":" + committedTopLevelNodeIds
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static void surfacePreviewGap(
            String stage,
            int chunkX,
            int chunkZ,
            int totalSurfaceColumns,
            int missingSurfaceColumns,
            int missingOceanFloorColumns,
            int boundaryExposureMissColumns,
            int gapSections,
            String reason
    ) {
        surfaceColumnsTotal.add(Math.max(0, totalSurfaceColumns));
        surfaceColumnsWithoutRetainedVoxel.add(Math.max(0, missingSurfaceColumns));
        oceanFloorColumnsWithoutRetainedVoxel.add(Math.max(0, missingOceanFloorColumns));
        chunkBoundaryExposureMisses.add(Math.max(0, boundaryExposureMissColumns));
        previewGapSections.add(Math.max(0, gapSections));
        if (!ingestTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"surface_preview_gap\""
                + ",\"stage\":\"" + escape(stage) + "\""
                + ",\"chunk_x\":" + chunkX
                + ",\"chunk_z\":" + chunkZ
                + ",\"surface_columns_total\":" + totalSurfaceColumns
                + ",\"surface_columns_without_retained_voxel\":" + missingSurfaceColumns
                + ",\"ocean_floor_columns_without_retained_voxel\":" + missingOceanFloorColumns
                + ",\"chunk_boundary_exposure_misses\":" + boundaryExposureMissColumns
                + ",\"preview_gap_sections\":" + gapSections
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static void mipperRepresentative(String stage, String reason) {
        switch (stage) {
            case "air_over_surface_rejected" -> mipperAirOverSurfaceRejected.increment();
            case "buried_shell_over_surface_rejected" -> mipperBuriedShellOverSurfaceRejected.increment();
            case "buried_solid_over_foliage_rejected" -> mipperBuriedSolidOverFoliageRejected.increment();
            case "ore_leak_surface_representative" -> oreLeakSurfaceRepresentatives.increment();
            case "water_floor_preserved" -> mipperWaterFloorPreserved.increment();
            default -> {
            }
        }
        if (!ingestTraceEnabled || !runtimeEnabled) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"mipper_representative\""
                + ",\"stage\":\"" + escape(stage) + "\""
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    public static long lastFrameId() {
        return lastFrameId.get();
    }

    public static void updateCameraPose(long frameId, double cameraX, double cameraY, double cameraZ, int width, int height) {
        long hash = quantizedHash(cameraX, cameraY, cameraZ, width, height);
        long previous = lastCameraHash.getAndSet(hash);
        if (previous == hash) {
            cameraStableFrameCount.incrementAndGet();
        } else {
            cameraStableFrameCount.set(0L);
        }
        if (renderFrameTraceEnabled && runtimeEnabled) {
            writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"camera_pose\""
                    + ",\"frame_id\":" + frameId
                    + ",\"camera_hash\":" + hash
                    + ",\"stable_frames\":" + cameraStableFrameCount.get()
                    + ",\"camera_x\":" + cameraX
                    + ",\"camera_y\":" + cameraY
                    + ",\"camera_z\":" + cameraZ
                    + ",\"width\":" + width
                    + ",\"height\":" + height + "}");
        }
    }

    public static long lastCameraHash() {
        return lastCameraHash.get();
    }

    public static boolean cameraStableForAtLeast(long frames) {
        return cameraStableFrameCount.get() >= frames;
    }

    private static long quantizedHash(double cameraX, double cameraY, double cameraZ, int width, int height) {
        long x = Math.round(cameraX * 20.0);
        long y = Math.round(cameraY * 20.0);
        long z = Math.round(cameraZ * 20.0);
        long hash = 1469598103934665603L;
        hash = (hash ^ x) * 1099511628211L;
        hash = (hash ^ y) * 1099511628211L;
        hash = (hash ^ z) * 1099511628211L;
        hash = (hash ^ width) * 1099511628211L;
        hash = (hash ^ height) * 1099511628211L;
        return hash;
    }

    private static void event(String type, String stage, int x, int y, int z, boolean flag, boolean result, String reason) {
        if (shouldSkipEvent(type)) {
            return;
        }
        ProbeTarget target = probeTarget();
        boolean probeMatch = target != null && target.matches(x, y, z);
        if (probeMatch && "ingest".equals(type)) {
            probeMatchedIngestTotal.increment();
            if (flag) {
                probeMatchedZeroIngestTotal.increment();
            }
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"" + escape(type) + "\",\"stage\":\"" + escape(stage)
                + "\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                + ",\"flag\":" + flag + ",\"result\":" + result
                + ",\"probe_id\":\"" + escape(target == null ? "" : target.probeId()) + "\""
                + ",\"probe_section_match\":" + probeMatch
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    private static void event(String type, String stage, long value, String key, String reason) {
        if (shouldSkipEvent(type)) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"" + escape(type) + "\",\"stage\":\"" + escape(stage)
                + "\",\"value\":" + value + ",\"key\":\"" + escape(key) + "\",\"reason\":\"" + escape(reason) + "\"}");
    }

    private static void event(String type, String stage, int a, int b, int c, int d, String reason) {
        if (shouldSkipEvent(type)) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"" + escape(type) + "\",\"stage\":\"" + escape(stage)
                + "\",\"a\":" + a + ",\"b\":" + b + ",\"c\":" + c + ",\"d\":" + d
                + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    private static String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date());
    }

    private static String escape(String s) {
        return s == null ? "" : s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void writeLine(String line) {
        if (!runtimeEnabled) {
            return;
        }
        long lineBytes = line.getBytes(StandardCharsets.UTF_8).length
                + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
        if (!reserveLine(lineBytes)) {
            return;
        }
        if (ASYNC_WRITES) {
            startWriter();
            if (!WRITE_QUEUE.offer(line)) {
                droppedLines.increment();
                if (queueDropLogged.compareAndSet(false, true)) {
                    Logger.info("Render correctness diagnostics write queue full. Dropping lines. capacity=",
                            Integer.toString(WRITE_QUEUE.remainingCapacity() + WRITE_QUEUE.size()),
                            " file=",
                            OUTPUT_FILE.toAbsolutePath().toString());
                }
            }
            return;
        }
        appendLines(List.of(line));
    }

    private static boolean reserveLine(long lineBytes) {
        try {
            ensureOutputInitialized();
            long currentLines = linesWritten.get();
            long currentBytes = bytesWritten.get();
            if ((MAX_LINES > 0L && currentLines >= MAX_LINES)
                    || (MAX_BYTES > 0L && currentBytes + lineBytes > MAX_BYTES)) {
                if (limitLogged.compareAndSet(false, true)) {
                    Logger.info("Render correctness diagnostics output limit reached. lines=",
                            Long.toString(currentLines),
                            " bytes=",
                            Long.toString(currentBytes),
                            " dropped=",
                            Long.toString(droppedLines.sum()),
                            " file=",
                            OUTPUT_FILE.toAbsolutePath().toString());
                }
                return false;
            }
            linesWritten.incrementAndGet();
            bytesWritten.addAndGet(lineBytes);
            return true;
        } catch (IOException ioe) {
            if (ioFailureLogged.compareAndSet(false, true)) {
                Logger.error("Render correctness diagnostics reserve failure", ioe);
            }
            return false;
        }
    }

    private static synchronized void ensureOutputInitialized() throws IOException {
        if (outputInitialized.get()) {
            return;
        }
        Path parent = OUTPUT_FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (TRUNCATE_ON_START) {
            Files.deleteIfExists(OUTPUT_FILE);
        }
        long currentBytes = Files.exists(OUTPUT_FILE) ? Files.size(OUTPUT_FILE) : 0L;
        bytesWritten.set(currentBytes);
        outputInitialized.set(true);
        if (initLogged.compareAndSet(false, true)) {
            Logger.info("Render correctness diagnostics active. Output=",
                    OUTPUT_FILE.toAbsolutePath().toString(),
                    " callTrace=",
                    Boolean.toString(callTraceEnabled),
                    " nodeTrace=",
                    Boolean.toString(nodeTraceEnabled),
                    " renderFrameTrace=",
                    Boolean.toString(renderFrameTraceEnabled),
                    " ingestTrace=",
                    Boolean.toString(ingestTraceEnabled));
        }
    }

    private static void startWriter() {
        if (!writerStarted.compareAndSet(false, true)) {
            return;
        }
        Thread writer = new Thread(RenderCorrectnessDiagnostics::writerLoop, "Voxy Render Diagnostics Writer");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writerLoop() {
        ArrayList<String> batch = new ArrayList<>(Math.max(1, WRITE_BATCH_SIZE));
        while (true) {
            try {
                String first = WRITE_QUEUE.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                WRITE_QUEUE.drainTo(batch, Math.max(0, WRITE_BATCH_SIZE - 1));
                appendLines(batch);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static synchronized void appendLines(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        try {
            ensureOutputInitialized();
            StringBuilder builder = new StringBuilder(lines.size() * 160);
            String lineSeparator = System.lineSeparator();
            for (String line : lines) {
                builder.append(line).append(lineSeparator);
            }
            Files.writeString(
                    OUTPUT_FILE,
                    builder.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ioe) {
            if (ioFailureLogged.compareAndSet(false, true)) {
                Logger.error("Render correctness diagnostics write failure", ioe);
            }
        }
    }

    private static boolean shouldSkipEvent(String type) {
        if (!runtimeEnabled) {
            return true;
        }
        if ("ingest".equals(type) || "ingest_lifecycle".equals(type) || "chunk_bound".equals(type)) {
            return !ingestTraceEnabled || !runtimeEnabled;
        }
        if ("depth_guard".equals(type) || "projection_guard".equals(type)) {
            return !guardTraceEnabled || !runtimeEnabled;
        }
        return false;
    }

    public static Path writeSummary(String trigger) {
        String json = summaryJson(trigger);
        try {
            Path parent = SUMMARY_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    SUMMARY_FILE,
                    json + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException ioe) {
            if (ioFailureLogged.compareAndSet(false, true)) {
                Logger.error("Render correctness diagnostics summary write failure", ioe);
            }
        }
        return SUMMARY_FILE.toAbsolutePath();
    }

    public static String statusLine() {
        return "profile=" + activeProfile
                + " frames=" + renderFrameTotal.sum()
                + " active_sections=" + lastActiveSections.get()
                + " geometry_sections=" + lastGeometrySections.get()
                + " zero_child_skips=" + nodeZeroChildSkipTotal.sum()
                + " ingest_processed=" + ingestProcessedTotal.sum()
                + " ingest_failed=" + ingestFailedTotal.sum()
                + " depth_guards=" + depthGuardSkips.sum()
                + " projection_guards=" + projectionGuardSkips.sum()
                + " verdict=" + classify();
    }

    public static String classify() {
        if (!runtimeEnabled && renderFrameTotal.sum() == 0L) {
            return "VOXY_DIAGNOSTICS_DISABLED";
        }
        if (projectionGuardSkips.sum() > 0L) {
            return "VOXY_PROJECTION_STATE_GUARD_HIT";
        }
        if (depthGuardSkips.sum() > 0L) {
            return "VOXY_DEPTH_FBO_GUARD_HIT";
        }
        if (renderOpaqueStartTotal.sum() > 0L && renderOpaqueEndTotal.sum() == 0L) {
            return "VOXY_RENDER_FRAME_INCOMPLETE";
        }
        if (ingestFailedTotal.sum() > 0L) {
            return "VOXY_INGEST_FAILURES_PRESENT";
        }
        if (nodeStateFlickerTotal.sum() > 0L) {
            return "VOXY_NODE_STATE_FLICKER_CONFIRMED";
        }
        if (lateGeometryDiscardedActiveNode.sum() > 0L) {
            return "VOXY_LATE_GEOMETRY_DISCARDED_ACTIVE_NODE";
        }
        if (geometryWatcherEpochMismatch.sum() > 0L) {
            return "VOXY_GEOMETRY_WATCHER_EPOCH_MISMATCH";
        }
        if (surfaceColumnsWithoutRetainedVoxel.sum() > 0L
                || oceanFloorColumnsWithoutRetainedVoxel.sum() > 0L
                || previewGapSections.sum() > 0L) {
            return "VOXY_SURFACE_PREVIEW_GAPS_PRESENT";
        }
        if (oreLeakSurfaceRepresentatives.sum() > 0L) {
            return "VOXY_ORE_LEAK_SURFACE_REPRESENTATIVES_PRESENT";
        }
        if (oldEpochPublishAllowedTotal.sum() > 0L
                || provisionalOverRealAllowedTotal.sum() > 0L
                || lowerConfidenceAllowedTotal.sum() > 0L) {
            return "VOXY_PUBLICATION_GUARD_ALLOWANCE_PRESENT";
        }
        if (ingestProcessedTotal.sum() == 0L && uploadIngestTotal.sum() > 0L) {
            return "VOXY_INGEST_QUEUE_NOT_DRAINING";
        }
        if (lastActiveSections.get() > 0L && lastGeometrySections.get() <= 0L) {
            return "VOXY_ACTIVE_SECTIONS_WITHOUT_GEOMETRY";
        }
        if (nodeZeroChildSkipTotal.sum() > 0L) {
            return "VOXY_CHILD_NODE_COMPLETENESS_PRESSURE";
        }
        if (nodeRequestRescheduleTotal.sum() > Math.max(200L, nodeRequestStartTotal.sum() / 2L)) {
            return "VOXY_NODE_REQUEST_CHURN";
        }
        if (renderFrameTotal.sum() > 0L && lastGeometrySections.get() > 0L) {
            return "VOXY_RENDERING_WITH_GEOMETRY";
        }
        return "VOXY_DIAGNOSTICS_INCONCLUSIVE";
    }

    private static String summaryJson(String trigger) {
        return "{"
                + "\"ts\":\"" + escape(isoNow()) + "\""
                + ",\"trigger\":\"" + escape(trigger) + "\""
                + ",\"profile\":\"" + escape(activeProfile) + "\""
                + ",\"enabled\":" + runtimeEnabled
                + ",\"classification\":\"" + escape(classify()) + "\""
                + ",\"output_file\":\"" + escape(OUTPUT_FILE.toAbsolutePath().toString()) + "\""
                + ",\"summary_file\":\"" + escape(SUMMARY_FILE.toAbsolutePath().toString()) + "\""
                + ",\"lines_written\":" + linesWritten.get()
                + ",\"dropped_lines\":" + droppedLines.sum()
                + ",\"upload_ingest_total\":" + uploadIngestTotal.sum()
                + ",\"upload_ingest_zero_total\":" + uploadIngestZeroTotal.sum()
                + ",\"upload_ingest_skipped_no_world\":" + uploadIngestSkippedNoWorld.sum()
                + ",\"upload_ingest_skipped_chunk_status\":" + uploadIngestSkippedChunkStatus.sum()
                + ",\"chunk_bound_add\":" + chunkBoundAdd.sum()
                + ",\"chunk_bound_remove\":" + chunkBoundRemove.sum()
                + ",\"ingest_processed_total\":" + ingestProcessedTotal.sum()
                + ",\"ingest_failed_total\":" + ingestFailedTotal.sum()
                + ",\"probe_matched_ingest_total\":" + probeMatchedIngestTotal.sum()
                + ",\"probe_matched_zero_ingest_total\":" + probeMatchedZeroIngestTotal.sum()
                + ",\"node_invalidation_total\":" + nodeInvalidationTotal.sum()
                + ",\"node_geometry_change_total\":" + nodeGeometryChangeTotal.sum()
                + ",\"node_request_start_total\":" + nodeRequestStartTotal.sum()
                + ",\"node_request_finish_total\":" + nodeRequestFinishTotal.sum()
                + ",\"node_request_reschedule_total\":" + nodeRequestRescheduleTotal.sum()
                + ",\"node_zero_child_skip_total\":" + nodeZeroChildSkipTotal.sum()
                + ",\"node_state_flicker_total\":" + nodeStateFlickerTotal.sum()
                + ",\"old_epoch_publish_rejected_total\":" + oldEpochPublishRejectedTotal.sum()
                + ",\"old_epoch_publish_allowed_total\":" + oldEpochPublishAllowedTotal.sum()
                + ",\"provisional_over_real_rejected_total\":" + provisionalOverRealRejectedTotal.sum()
                + ",\"provisional_over_real_allowed_total\":" + provisionalOverRealAllowedTotal.sum()
                + ",\"lower_confidence_rejected_total\":" + lowerConfidenceRejectedTotal.sum()
                + ",\"lower_confidence_allowed_total\":" + lowerConfidenceAllowedTotal.sum()
                + ",\"preview_zero_clear_rejected_total\":" + previewZeroClearRejectedTotal.sum()
                + ",\"missing_light_ready_rejected_total\":" + missingLightReadyRejectedTotal.sum()
                + ",\"synthetic_light_ready_rejected_total\":" + syntheticLightReadyRejectedTotal.sum()
                + ",\"depth_guard_skips\":" + depthGuardSkips.sum()
                + ",\"projection_guard_skips\":" + projectionGuardSkips.sum()
                + ",\"render_frame_total\":" + renderFrameTotal.sum()
                + ",\"viewport_ready_total\":" + viewportReadyTotal.sum()
                + ",\"render_opaque_start_total\":" + renderOpaqueStartTotal.sum()
                + ",\"render_opaque_end_total\":" + renderOpaqueEndTotal.sum()
                + ",\"render_distance_slider_mode\":\"" + VoxyHandoffPolicy.renderDistanceSliderMode() + "\""
                + ",\"visual_terrain_distance_chunks\":" + VoxyHandoffPolicy.visualTerrainDistanceChunks()
                + ",\"vanilla_real_render_distance_chunks\":" + VoxyHandoffPolicy.realRenderDistanceChunks()
                + ",\"vanilla_render_distance_chunks\":" + VoxyHandoffPolicy.vanillaRadiusChunks()
                + ",\"voxy_lod_start_chunks\":" + VoxyHandoffPolicy.handoffStartChunks()
                + ",\"voxy_handoff_start_chunks\":" + VoxyHandoffPolicy.handoffStartChunks()
                + ",\"voxy_lod_end_chunks\":" + VoxyHandoffPolicy.voxyLodEndChunks()
                + ",\"handoff_overlap_chunks\":" + VoxyHandoffPolicy.overlapChunks()
                + ",\"boundary_ring_loaded_sections\":" + ServerLodDiagnostics.boundaryRingRenderLoads.get()
                + ",\"boundary_ring_missing_sections\":" + ServerLodDiagnostics.boundaryRingRenderMisses.get()
                + ",\"boundary_ring_parent_fallback_sections\":" + ServerLodDiagnostics.boundaryRingParentFallbackSections.get()
                + ",\"boundary_ring_rejected_sections\":" + ServerLodDiagnostics.boundaryRingRejectedSections.get()
                + ",\"terrain_coverage_verdict\":\"" + VoxyHandoffPolicy.boundaryGapVerdict(
                        ServerLodDiagnostics.boundaryRingRenderLoads.get(),
                        ServerLodDiagnostics.boundaryRingParentFallbackSections.get(),
                        ServerLodDiagnostics.boundaryRingRenderMisses.get()) + "\""
                + ",\"boundary_gap_verdict\":\"" + VoxyHandoffPolicy.boundaryGapVerdict(
                        ServerLodDiagnostics.boundaryRingRenderLoads.get(),
                        ServerLodDiagnostics.boundaryRingParentFallbackSections.get(),
                        ServerLodDiagnostics.boundaryRingRenderMisses.get()) + "\""
                + ",\"surface_columns_total\":" + surfaceColumnsTotal.sum()
                + ",\"surface_columns_without_retained_voxel\":" + surfaceColumnsWithoutRetainedVoxel.sum()
                + ",\"ocean_floor_columns_without_retained_voxel\":" + oceanFloorColumnsWithoutRetainedVoxel.sum()
                + ",\"chunk_boundary_exposure_misses\":" + chunkBoundaryExposureMisses.sum()
                + ",\"preview_gap_sections\":" + previewGapSections.sum()
                + ",\"mipper_air_over_surface_rejected\":" + mipperAirOverSurfaceRejected.sum()
                + ",\"mipper_buried_shell_over_surface_rejected\":" + mipperBuriedShellOverSurfaceRejected.sum()
                + ",\"mipper_buried_solid_over_foliage_rejected\":" + mipperBuriedSolidOverFoliageRejected.sum()
                + ",\"ore_leak_surface_representatives\":" + oreLeakSurfaceRepresentatives.sum()
                + ",\"mipper_water_floor_preserved\":" + mipperWaterFloorPreserved.sum()
                + ",\"late_geometry_accepted_active_node\":" + lateGeometryAcceptedActiveNode.sum()
                + ",\"late_geometry_rejected_stale_epoch\":" + lateGeometryRejectedStaleEpoch.sum()
                + ",\"late_geometry_discarded_active_node\":" + lateGeometryDiscardedActiveNode.sum()
                + ",\"geometry_watcher_epoch_mismatch\":" + geometryWatcherEpochMismatch.sum()
                + ",\"parent_retained_until_child_commit\":" + parentRetainedUntilChildCommit.sum()
                + ",\"atomic_child_commit_total\":" + atomicChildCommitTotal.sum()
                + ",\"preview_sections_written\":" + previewSectionsWritten.sum()
                + ",\"real_chunk_sections_written\":" + realChunkSectionsWritten.sum()
                + ",\"preview_superseded_by_real\":" + previewSupersededByReal.sum()
                + ",\"preview_over_real_rejected\":" + previewOverRealRejected.sum()
                + ",\"real_chunk_import_failures\":" + realChunkImportFailures.sum()
                + ",\"last_frame_id\":" + lastFrameId.get()
                + ",\"last_camera_hash\":" + lastCameraHash.get()
                + ",\"camera_stable_frames\":" + cameraStableFrameCount.get()
                + ",\"last_pipeline\":\"" + escape(lastPipeline) + "\""
                + ",\"last_top_level_nodes\":" + lastTopLevelNodes.get()
                + ",\"last_active_sections\":" + lastActiveSections.get()
                + ",\"last_geometry_sections\":" + lastGeometrySections.get()
                + ",\"last_active_requests\":" + lastActiveRequests.get()
                + ",\"last_used_geometry_bytes\":" + lastUsedGeometryBytes.get()
                + "}";
    }

    private static ProbeTarget probeTarget() {
        try {
            if (!Files.exists(PROBE_TARGET_FILE)) {
                probeTarget = null;
                probeTargetLastModified = Long.MIN_VALUE;
                return null;
            }
            long modified = Files.getLastModifiedTime(PROBE_TARGET_FILE).toMillis();
            ProbeTarget current = probeTarget;
            if (current != null && modified == probeTargetLastModified) {
                return current;
            }
            String json = Files.readString(PROBE_TARGET_FILE, StandardCharsets.UTF_8);
            ProbeTarget parsed = new ProbeTarget(
                    extractString(json, "probe_id"),
                    extractInt(json, "section_x"),
                    extractInt(json, "section_y"),
                    extractInt(json, "section_z")
            );
            probeTarget = parsed;
            probeTargetLastModified = modified;
            return parsed;
        } catch (Exception ignored) {
            return probeTarget;
        }
    }

    private static String extractString(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return "";
        }
        int colon = json.indexOf(':', keyIndex + needle.length());
        int firstQuote = colon < 0 ? -1 : json.indexOf('"', colon + 1);
        int secondQuote = firstQuote < 0 ? -1 : json.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return "";
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static int extractInt(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return 0;
        }
        int colon = json.indexOf(':', keyIndex + needle.length());
        if (colon < 0) {
            return 0;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c != '-' && (c < '0' || c > '9')) {
                break;
            }
            end++;
        }
        if (end <= start) {
            return 0;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    private record ProbeTarget(String probeId, int sectionX, int sectionY, int sectionZ) {
        boolean matches(int x, int y, int z) {
            return x == this.sectionX && y == this.sectionY && z == this.sectionZ;
        }
    }
}
