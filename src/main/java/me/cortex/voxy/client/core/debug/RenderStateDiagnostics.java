package me.cortex.voxy.client.core.debug;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.RenderStateSnapshot;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight render-state snapshot logger for FOV/zoom instability diagnosis.
 *
 * Enable with:
 *   -Dvoxy.renderstateDiagnostics=true
 *
 * Optional:
 *   -Dvoxy.renderstateDiagnosticsDir=<path>
 *   -Dvoxy.renderstateDiagnosticsIntervalMs=150
 *   -Dvoxy.renderstateDiagnosticsAlways=false
 */
public final class RenderStateDiagnostics {
    private static final boolean ENABLED_PROPERTY = Boolean.parseBoolean(
            System.getProperty("voxy.renderstateDiagnostics", "false")
    );
    private static final long MIN_INTERVAL_MS = Long.getLong("voxy.renderstateDiagnosticsIntervalMs", 150L);
    private static final boolean ALWAYS_WRITE = Boolean.parseBoolean(
            System.getProperty("voxy.renderstateDiagnosticsAlways", "false")
    );
    private static final Path OUTPUT_DIR = Path.of(
            System.getProperty("voxy.renderstateDiagnosticsDir", "debug/voxy_renderstate")
    );
    private static final Path OUTPUT_FILE = OUTPUT_DIR.resolve("voxy_renderstate.ndjson");
    private static final long MAX_LINES = Long.getLong("voxy.renderstateDiagnosticsMaxLines", 20_000L);
    private static final long MAX_BYTES = Long.getLong("voxy.renderstateDiagnosticsMaxBytes", 64L * 1024L * 1024L);

    private static final AtomicBoolean initLogged = new AtomicBoolean(false);
    private static final AtomicBoolean ioFailureLogged = new AtomicBoolean(false);
    private static final AtomicBoolean limitLogged = new AtomicBoolean(false);
    private static final AtomicLong linesWritten = new AtomicLong(0L);
    private static final AtomicLong bytesWritten = new AtomicLong(-1L);

    private static volatile long lastWriteMs = 0L;
    private static volatile long lastStateHash = Long.MIN_VALUE;
    private static volatile long lastUniformSnapshotSequence = Long.MIN_VALUE;
    private static volatile long lastUniformWriteMs = 0L;
    private static volatile long sequence = 0L;

    private RenderStateDiagnostics() {
    }

    private static boolean isRenderStateEnabled() {
        return ENABLED_PROPERTY || VoxyConfig.CONFIG.renderStateDebug;
    }

    private static boolean isTraversalEnabled() {
        return ENABLED_PROPERTY || VoxyConfig.CONFIG.lodCullingDebug;
    }

    private static boolean isAnyEnabled() {
        return ENABLED_PROPERTY
                || VoxyConfig.CONFIG.renderStateDebug
                || VoxyConfig.CONFIG.uniformBridgeDebug
                || VoxyConfig.CONFIG.lodCullingDebug
                || VoxyConfig.CONFIG.depthCompositionDebug;
    }

    public static void captureNow(String trigger) {
        if (!isAnyEnabled()) {
            return;
        }
        long id = ++sequence;
        var mc = Minecraft.getInstance();
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        appendKv(sb, "ts", isoNow());
        sb.append(',');
        appendKv(sb, "type", "manual_capture");
        sb.append(',');
        appendKv(sb, "trigger", trigger);
        sb.append(',');
        appendKv(sb, "seq", id);
        if (mc.level != null) {
            sb.append(',');
            appendKv(sb, "dimension", mc.level.dimension().toString());
            sb.append(',');
            appendKv(sb, "game_time", mc.level.getLevelData().getGameTime());
        }
        if (mc.player != null) {
            sb.append(',');
            appendKv(sb, "camera_x", mc.player.getX());
            sb.append(',');
            appendKv(sb, "camera_y", mc.player.getY());
            sb.append(',');
            appendKv(sb, "camera_z", mc.player.getZ());
            sb.append(',');
            appendKv(sb, "camera_yaw", mc.player.getYRot());
            sb.append(',');
            appendKv(sb, "camera_pitch", mc.player.getXRot());
        }
        sb.append('}');
        writeLine(sb.toString());
    }

    public static void captureViewport(
            String stage,
            Viewport<?> viewport,
            RenderProperties properties,
            float configuredSectionRenderDistance
    ) {
        if (!isRenderStateEnabled() || viewport == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long hash = hashMatrix(viewport.projection)
                ^ (hashMatrix(viewport.modelView) * 31L)
                ^ (hashMatrix(viewport.MVP) * 17L)
                ^ (long) viewport.width
                ^ ((long) viewport.height << 32);

        boolean significantChange = hash != lastStateHash;
        if (!ALWAYS_WRITE && !significantChange && (now - lastWriteMs) < MIN_INTERVAL_MS) {
            return;
        }

        lastStateHash = hash;
        lastWriteMs = now;
        long id = ++sequence;

        StringBuilder sb = new StringBuilder(8192);
        sb.append('{');
        appendKv(sb, "ts", isoNow());
        sb.append(',');
        appendKv(sb, "type", "viewport");
        sb.append(',');
        appendKv(sb, "stage", stage);
        sb.append(',');
        appendKv(sb, "seq", id);
        sb.append(',');
        appendKv(sb, "significant_change", significantChange);
        sb.append(',');
        appendKv(sb, "frame_id", viewport.frameId);
        sb.append(',');
        appendKv(sb, "width", viewport.width);
        sb.append(',');
        appendKv(sb, "height", viewport.height);
        sb.append(',');
        appendKv(sb, "camera_x", viewport.cameraX);
        sb.append(',');
        appendKv(sb, "camera_y", viewport.cameraY);
        sb.append(',');
        appendKv(sb, "camera_z", viewport.cameraZ);
        sb.append(',');
        appendKv(sb, "section_x", viewport.section.x);
        sb.append(',');
        appendKv(sb, "section_y", viewport.section.y);
        sb.append(',');
        appendKv(sb, "section_z", viewport.section.z);
        sb.append(',');
        appendKv(sb, "inner_tx", viewport.innerTranslation.x);
        sb.append(',');
        appendKv(sb, "inner_ty", viewport.innerTranslation.y);
        sb.append(',');
        appendKv(sb, "inner_tz", viewport.innerTranslation.z);
        sb.append(',');
        appendKv(sb, "hiz_packed_levels", viewport.hiZBuffer.getPackedLevels());
        sb.append(',');
        appendKv(sb, "configured_section_render_distance", configuredSectionRenderDistance);
        sb.append(',');
        appendKv(sb, "vanilla_render_distance_blocks", getVanillaRenderDistanceBlocks());
        sb.append(',');
        appendKv(sb, "approx_vanilla_fov_y_deg", approxFovYDegrees(viewport.vanillaProjection));
        sb.append(',');
        appendKv(sb, "approx_voxy_fov_y_deg", approxFovYDegrees(viewport.projection));
        sb.append(',');
        appendKv(sb, "reverse_z", properties != null && properties.isReverseZ());
        sb.append(',');
        appendKv(sb, "zero_to_one_depth", properties != null && properties.isZero2One());
        sb.append(',');
        appendMatrix(sb, "vanilla_projection", viewport.vanillaProjection);
        sb.append(',');
        appendMatrix(sb, "voxy_projection", viewport.projection);
        sb.append(',');
        appendMatrix(sb, "model_view", viewport.modelView);
        sb.append(',');
        appendMatrix(sb, "view_projection", viewport.MVP);
        sb.append(',');
        appendFrustumPlanes(sb, viewport.frustumPlanes);
        sb.append('}');

        writeLine(sb.toString());
    }

    public static void captureTraversal(
            String stage,
            Viewport<?> viewport,
            int topNodeCount,
            int requestBudget,
            float screenSpaceDescendThreshold,
            float renderDistanceSq
    ) {
        if (!isTraversalEnabled() || viewport == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long hash = hashMatrix(viewport.MVP)
                ^ (long) topNodeCount * 131L
                ^ (long) requestBudget * 17L;

        boolean significantChange = hash != lastStateHash;
        if (!ALWAYS_WRITE && !significantChange && (now - lastWriteMs) < MIN_INTERVAL_MS) {
            return;
        }

        lastStateHash = hash;
        lastWriteMs = now;
        long id = ++sequence;

        StringBuilder sb = new StringBuilder(3072);
        sb.append('{');
        appendKv(sb, "ts", isoNow());
        sb.append(',');
        appendKv(sb, "type", "traversal");
        sb.append(',');
        appendKv(sb, "stage", stage);
        sb.append(',');
        appendKv(sb, "seq", id);
        sb.append(',');
        appendKv(sb, "significant_change", significantChange);
        sb.append(',');
        appendKv(sb, "frame_id", viewport.frameId);
        sb.append(',');
        appendKv(sb, "top_node_count", topNodeCount);
        sb.append(',');
        appendKv(sb, "request_budget", requestBudget);
        sb.append(',');
        appendKv(sb, "screen_space_descend_threshold", screenSpaceDescendThreshold);
        sb.append(',');
        appendKv(sb, "render_distance_sq", renderDistanceSq);
        sb.append(',');
        appendKv(sb, "hiz_packed_levels", viewport.hiZBuffer.getPackedLevels());
        sb.append(',');
        appendKv(sb, "approx_voxy_fov_y_deg", approxFovYDegrees(viewport.projection));
        sb.append('}');

        writeLine(sb.toString());
    }

    public static void captureUniformBridge(String stage, RenderStateSnapshot snapshot) {
        if (!(ENABLED_PROPERTY || VoxyConfig.CONFIG.uniformBridgeDebug) || snapshot == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long snapshotSequence = snapshot.sequence();
        if (!ALWAYS_WRITE && snapshotSequence == lastUniformSnapshotSequence && (now - lastUniformWriteMs) < MIN_INTERVAL_MS) {
            return;
        }
        lastUniformSnapshotSequence = snapshotSequence;
        lastUniformWriteMs = now;

        StringBuilder sb = new StringBuilder(2048);
        sb.append('{');
        appendKv(sb, "ts", isoNow());
        sb.append(',');
        appendKv(sb, "type", "uniform_bridge");
        sb.append(',');
        appendKv(sb, "stage", stage);
        sb.append(',');
        appendKv(sb, "seq", ++sequence);
        sb.append(',');
        appendKv(sb, "snapshot_sequence", snapshotSequence);
        sb.append(',');
        appendKv(sb, "frame_id", snapshot.frameId());
        sb.append(',');
        appendKv(sb, "width", snapshot.width());
        sb.append(',');
        appendKv(sb, "height", snapshot.height());
        sb.append(',');
        appendKv(sb, "camera_x", snapshot.cameraX());
        sb.append(',');
        appendKv(sb, "camera_y", snapshot.cameraY());
        sb.append(',');
        appendKv(sb, "camera_z", snapshot.cameraZ());
        sb.append(',');
        appendKv(sb, "configured_section_render_distance", snapshot.sectionRenderDistance());
        sb.append(',');
        appendKv(sb, "visual_terrain_distance_blocks", snapshot.visualTerrainDistanceBlocks());
        sb.append(',');
        appendKv(sb, "real_chunk_radius_chunks", snapshot.realChunkRadiusChunks());
        sb.append(',');
        appendKv(sb, "handoff_start_chunks", snapshot.handoffStartChunks());
        sb.append(',');
        appendKv(sb, "lod_end_chunks", snapshot.lodEndChunks());
        sb.append(',');
        appendKv(sb, "terrain_pass", snapshot.terrainPass());
        sb.append(',');
        appendKv(sb, "sodium_chunk_rendering_enabled", snapshot.sodiumChunkRenderingEnabled());
        sb.append(',');
        appendKv(sb, "iris_shaderpack_enabled", snapshot.irisShaderPackEnabled());
        sb.append(',');
        appendKv(sb, "depth_target_identity", snapshot.depthTargetIdentity());
        sb.append(',');
        appendKv(sb, "fog_start", snapshot.fogStart());
        sb.append(',');
        appendKv(sb, "fog_end", snapshot.fogEnd());
        sb.append(',');
        appendKv(sb, "approx_voxy_fov_y_deg", approxFovYDegrees(snapshot.projection()));
        sb.append('}');

        writeLine(sb.toString());
    }

    private static String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date());
    }

    private static long hashMatrix(Matrix4fc m) {
        long h = 1125899906842597L;
        h = h * 31 + Float.floatToIntBits(m.m00());
        h = h * 31 + Float.floatToIntBits(m.m01());
        h = h * 31 + Float.floatToIntBits(m.m02());
        h = h * 31 + Float.floatToIntBits(m.m03());
        h = h * 31 + Float.floatToIntBits(m.m10());
        h = h * 31 + Float.floatToIntBits(m.m11());
        h = h * 31 + Float.floatToIntBits(m.m12());
        h = h * 31 + Float.floatToIntBits(m.m13());
        h = h * 31 + Float.floatToIntBits(m.m20());
        h = h * 31 + Float.floatToIntBits(m.m21());
        h = h * 31 + Float.floatToIntBits(m.m22());
        h = h * 31 + Float.floatToIntBits(m.m23());
        h = h * 31 + Float.floatToIntBits(m.m30());
        h = h * 31 + Float.floatToIntBits(m.m31());
        h = h * 31 + Float.floatToIntBits(m.m32());
        h = h * 31 + Float.floatToIntBits(m.m33());
        return h;
    }

    private static double approxFovYDegrees(Matrix4fc projection) {
        double m11 = Math.abs(projection.m11());
        if (m11 < 1.0e-6) {
            return -1.0;
        }
        return Math.toDegrees(2.0 * Math.atan(1.0 / m11));
    }

    private static int getVanillaRenderDistanceBlocks() {
        try {
            var mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) {
                return -1;
            }
            return mc.options.getEffectiveRenderDistance() * 16;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void appendMatrix(StringBuilder sb, String key, Matrix4fc m) {
        sb.append('"').append(escape(key)).append('"').append(':').append('[')
                .append(m.m00()).append(',').append(m.m01()).append(',').append(m.m02()).append(',').append(m.m03()).append(',')
                .append(m.m10()).append(',').append(m.m11()).append(',').append(m.m12()).append(',').append(m.m13()).append(',')
                .append(m.m20()).append(',').append(m.m21()).append(',').append(m.m22()).append(',').append(m.m23()).append(',')
                .append(m.m30()).append(',').append(m.m31()).append(',').append(m.m32()).append(',').append(m.m33())
                .append(']');
    }

    private static void appendFrustumPlanes(StringBuilder sb, Vector4f[] planes) {
        sb.append("\"frustum_planes\":[");
        if (planes != null) {
            for (int i = 0; i < planes.length; i++) {
                if (i != 0) {
                    sb.append(',');
                }
                Vector4f p = planes[i];
                if (p == null) {
                    sb.append("null");
                } else {
                    sb.append('[').append(p.x).append(',').append(p.y).append(',').append(p.z).append(',').append(p.w).append(']');
                }
            }
        }
        sb.append(']');
    }

    private static void appendKv(StringBuilder sb, String key, String value) {
        sb.append('"').append(escape(key)).append('"').append(':')
                .append('"').append(escape(value)).append('"');
    }

    private static void appendKv(StringBuilder sb, String key, boolean value) {
        sb.append('"').append(escape(key)).append('"').append(':').append(value);
    }

    private static void appendKv(StringBuilder sb, String key, long value) {
        sb.append('"').append(escape(key)).append('"').append(':').append(value);
    }

    private static void appendKv(StringBuilder sb, String key, int value) {
        sb.append('"').append(escape(key)).append('"').append(':').append(value);
    }

    private static void appendKv(StringBuilder sb, String key, float value) {
        sb.append('"').append(escape(key)).append('"').append(':').append(value);
    }

    private static void appendKv(StringBuilder sb, String key, double value) {
        sb.append('"').append(escape(key)).append('"').append(':').append(value);
    }

    private static String escape(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static synchronized void writeLine(String line) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            long currentBytes = bytesWritten.get();
            if (currentBytes < 0L) {
                currentBytes = Files.exists(OUTPUT_FILE) ? Files.size(OUTPUT_FILE) : 0L;
                bytesWritten.set(currentBytes);
            }
            long currentLines = linesWritten.get();
            long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
            if ((MAX_LINES > 0L && currentLines >= MAX_LINES)
                    || (MAX_BYTES > 0L && currentBytes + lineBytes > MAX_BYTES)) {
                if (limitLogged.compareAndSet(false, true)) {
                    me.cortex.voxy.common.Logger.info(
                            "RenderStateDiagnostics output limit reached. lines=",
                            Long.toString(currentLines),
                            " bytes=",
                            Long.toString(currentBytes),
                            " file=",
                            OUTPUT_FILE.toAbsolutePath().toString()
                    );
                }
                return;
            }
            Files.writeString(
                    OUTPUT_FILE,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
            linesWritten.incrementAndGet();
            bytesWritten.addAndGet(lineBytes);
            if (initLogged.compareAndSet(false, true)) {
                me.cortex.voxy.common.Logger.info("RenderStateDiagnostics active. Output=", OUTPUT_FILE.toAbsolutePath().toString());
            }
        } catch (IOException ioe) {
            if (ioFailureLogged.compareAndSet(false, true)) {
                me.cortex.voxy.common.Logger.error("RenderStateDiagnostics write failure", ioe);
            }
        }
    }
}
