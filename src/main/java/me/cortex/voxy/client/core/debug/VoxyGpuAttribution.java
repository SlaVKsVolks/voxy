package me.cortex.voxy.client.core.debug;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import static org.lwjgl.opengl.GL11C.GL_COLOR;
import static org.lwjgl.opengl.GL11C.GL_DEPTH;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glReadPixels;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15C.GL_SAMPLES_PASSED;
import static org.lwjgl.opengl.GL15C.glBeginQuery;
import static org.lwjgl.opengl.GL15C.glDeleteQueries;
import static org.lwjgl.opengl.GL15C.glEndQuery;
import static org.lwjgl.opengl.GL15C.glGenQueries;
import static org.lwjgl.opengl.GL15C.glGetQueryObjecti;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glReadBuffer;
import static org.lwjgl.opengl.GL45C.glClearNamedFramebufferfv;
import static org.lwjgl.opengl.GL45C.glBlitNamedFramebuffer;

public final class VoxyGpuAttribution {
    private static final Object LOCK = new Object();
    private static CaptureRequest pending;
    private static CaptureResult latest = CaptureResult.idle();
    private static DiagnosticTarget diagnosticTarget;
    private static CaptureResult activeDiagnosticResult;
    private static DiagnosticTarget activeDiagnosticTarget;
    private static Query activeSectionQuery;

    private VoxyGpuAttribution() {
    }

    public static String requestCapture(String captureId, String outputDir, int roiX, int roiY, int roiWidth, int roiHeight, boolean diagnosticBuffer) {
        CaptureRequest request = new CaptureRequest(
                sanitize(captureId, "voxy_gpu_attribution"),
                outputDir == null || outputDir.isBlank()
                        ? Minecraft.getInstance().gameDirectory.toPath().resolve("testharness").resolve("voxy_gpu_attribution").toString()
                        : outputDir,
                Math.max(0, roiX),
                Math.max(0, roiY),
                Math.max(1, roiWidth),
                Math.max(1, roiHeight),
                diagnosticBuffer
        );
        synchronized (LOCK) {
            pending = request;
            latest = CaptureResult.queued(request);
        }
        return latestJson();
    }

    public static String latestJson() {
        synchronized (LOCK) {
            return latest.toJson();
        }
    }

    public static void recordNoop(Viewport<?> viewport, int geometrySections, int activeNodeRequests) {
        CaptureRequest request;
        synchronized (LOCK) {
            request = pending;
            pending = null;
        }
        if (request == null) {
            return;
        }
        CaptureResult result = CaptureResult.completed(request, "noop_skipped", viewport, geometrySections, activeNodeRequests);
        result.voxySamplesPassed = 0;
        result.voxyAnySamplesPassed = false;
        result.idBufferNonzeroPixels = 0;
        result.idBufferTotalPixels = request.roiWidth * request.roiHeight;
        result.mode = VoxyClient.getVisualAttributionMode();
        writeZeroIdBuffer(result);
        writeResult(result);
    }

    public static String recordOffControl(String captureId, String outputDir, int roiX, int roiY, int roiWidth, int roiHeight) {
        CaptureRequest request = new CaptureRequest(
                sanitize(captureId, "voxy_gpu_attribution_off"),
                outputDir == null || outputDir.isBlank()
                        ? Minecraft.getInstance().gameDirectory.toPath().resolve("testharness").resolve("voxy_gpu_attribution").toString()
                        : outputDir,
                Math.max(0, roiX),
                Math.max(0, roiY),
                Math.max(1, roiWidth),
                Math.max(1, roiHeight),
                false
        );
        CaptureResult result = new CaptureResult(request, "PASS_VOXY_GPU_ATTRIBUTION_CAPTURED");
        result.reason = "off_control";
        result.mode = "off";
        result.idBufferTotalPixels = request.roiWidth * request.roiHeight;
        result.idBufferNonzeroPixels = 0;
        writeZeroIdBuffer(result);
        writeResult(result);
        return latestJson();
    }

    public static String captureCurrentFinalForLatest() {
        CaptureResult result;
        synchronized (LOCK) {
            result = latest;
        }
        if (result == null || result.request == null || "IDLE".equals(result.status) || "QUEUED".equals(result.status)) {
            return latestJson();
        }
        captureMainFramebuffer(result);
        if ("noop".equals(result.mode) || "off".equals(result.mode)) {
            writeZeroIdBuffer(result);
        }
        writeResult(result);
        return latestJson();
    }

    public static CaptureRequest takePendingRequest() {
        synchronized (LOCK) {
            CaptureRequest request = pending;
            pending = null;
            return request;
        }
    }

    public static CaptureResult captureDiagnosticBuffer(
            CaptureRequest request,
            AbstractRenderPipeline pipeline,
            Viewport<?> viewport,
            int sourceFramebuffer,
            int sourceWidth,
            int sourceHeight,
            int geometrySections,
            int activeNodeRequests
    ) {
        CaptureResult result = CaptureResult.completed(request, "diagnostic_capture", viewport, geometrySections, activeNodeRequests);
        result.mode = VoxyClient.getVisualAttributionMode();
        if (!request.diagnosticBuffer) {
            return result;
        }
        if (sourceFramebuffer == 0 || sourceWidth <= 0 || sourceHeight <= 0 || viewport == null || viewport.width <= 0 || viewport.height <= 0) {
            result.error = "invalid_framebuffer_or_viewport";
            writeResult(result);
            return result;
        }
        try {
            DiagnosticTarget target = ensureDiagnosticTarget(viewport.width, viewport.height);
            clearTarget(target);
            glBlitNamedFramebuffer(
                    sourceFramebuffer,
                    target.framebuffer.id,
                    0,
                    0,
                    sourceWidth,
                    sourceHeight,
                    0,
                    0,
                    viewport.width,
                    viewport.height,
                    GL_DEPTH_BUFFER_BIT,
                    GL_NEAREST
            );

            beginDiagnosticPass(result, target);
            try {
                pipeline.runPipeline(viewport, target.framebuffer.id, viewport.width, viewport.height);
            } finally {
                endDiagnosticPass();
            }

            Path outputDir = Path.of(request.outputDir);
            Files.createDirectories(outputDir);
            if (result.idBufferPng.isBlank()) {
                readDiagnosticIdBuffer(result, target.framebuffer.id, viewport.width, viewport.height);
            }
            result.idBufferFormat = "RGBA8_SECTION_RENDERER_DIAGNOSTIC_COLOR";
        } catch (Throwable throwable) {
            result.error = throwable.getClass().getName() + ":" + throwable.getMessage();
            Logger.warn("Voxy GPU attribution diagnostic capture failed", throwable);
        } finally {
            endDiagnosticPass();
            glBindFramebuffer(GL_FRAMEBUFFER, sourceFramebuffer);
            glViewport(0, 0, sourceWidth, sourceHeight);
        }
        writeResult(result);
        return result;
    }

    public static void captureFinalFramebuffer(CaptureResult result, int framebuffer, int width, int height) {
        if (result == null || framebuffer == 0 || width <= 0 || height <= 0) {
            return;
        }
        try {
            Path outputDir = Path.of(result.request.outputDir);
            Files.createDirectories(outputDir);
            Path finalPng = outputDir.resolve(result.request.captureId + "_final_voxy_" + sanitize(result.mode, "mode") + ".png");
            ReadbackSummary finalSummary = readRgbaRoi(
                    framebuffer,
                    result.request.clampedX(width),
                    result.request.clampedY(height),
                    result.request.clampedWidth(width),
                    result.request.clampedHeight(height),
                    finalPng
            );
            result.finalColorPng = finalPng.toString();
            result.finalColorRaw = finalSummary.rawPath;
            result.finalColorTotalPixels = finalSummary.totalPixels;
            result.finalColorNonzeroPixels = finalSummary.nonzeroPixels;
            result.finalColorNonzeroRatio = finalSummary.nonzeroRatio();
            writeResult(result);
        } catch (Throwable throwable) {
            result.error = appendError(result.error, throwable.getClass().getName() + ":" + throwable.getMessage());
            Logger.warn("Voxy GPU attribution final framebuffer readback failed", throwable);
            writeResult(result);
        }
    }

    public static Query beginQuery() {
        return Query.start();
    }

    public static void finishNormalQuery(Query query, CaptureResult result) {
        if (query == null || result == null) {
            return;
        }
        query.stop(result);
        writeResult(result);
    }

    public static boolean isDiagnosticIdPassActive() {
        return activeDiagnosticResult != null && activeDiagnosticTarget != null;
    }

    public static int diagnosticFramebufferId() {
        return activeDiagnosticTarget == null ? 0 : activeDiagnosticTarget.framebuffer.id;
    }

    public static void beginSectionRendererDraw(int maxDrawCount, int geometrySections) {
        CaptureResult result = activeDiagnosticResult;
        if (result == null) {
            return;
        }
        result.voxySectionDrawCallCount++;
        result.voxySectionDrawnGeometryCount = geometrySections;
        result.voxySectionMaxDrawCount += Math.max(0, maxDrawCount);
        activeSectionQuery = Query.start();
    }

    public static void endSectionRendererDraw(Viewport<?> viewport) {
        CaptureResult result = activeDiagnosticResult;
        Query query = activeSectionQuery;
        activeSectionQuery = null;
        if (result == null) {
            return;
        }
        if (query != null) {
            query.stop(result);
        }
        DiagnosticTarget target = activeDiagnosticTarget;
        if (target != null && viewport != null && result.idBufferPng.isBlank()) {
            readDiagnosticIdBuffer(result, target.framebuffer.id, viewport.width, viewport.height);
        }
        writeResult(result);
    }

    private static void beginDiagnosticPass(CaptureResult result, DiagnosticTarget target) {
        activeDiagnosticResult = result;
        activeDiagnosticTarget = target;
        activeSectionQuery = null;
    }

    private static void endDiagnosticPass() {
        if (activeSectionQuery != null && activeDiagnosticResult != null) {
            activeSectionQuery.stop(activeDiagnosticResult);
        }
        activeSectionQuery = null;
        activeDiagnosticResult = null;
        activeDiagnosticTarget = null;
    }

    private static void writeResult(CaptureResult result) {
        synchronized (LOCK) {
            latest = result;
        }
        try {
            Path outputDir = Path.of(result.request.outputDir);
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve(result.request.captureId + "_voxy_gpu_attribution.json"), result.toJson());
        } catch (IOException ioe) {
            Logger.warn("Failed to write Voxy GPU attribution JSON", ioe);
        }
    }

    private static DiagnosticTarget ensureDiagnosticTarget(int width, int height) {
        if (diagnosticTarget == null || diagnosticTarget.width != width || diagnosticTarget.height != height) {
            if (diagnosticTarget != null) {
                diagnosticTarget.free();
            }
            diagnosticTarget = new DiagnosticTarget(width, height);
        }
        return diagnosticTarget;
    }

    private static void clearTarget(DiagnosticTarget target) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glClearNamedFramebufferfv(target.framebuffer.id, GL_COLOR, 0, stack.floats(0.0f, 0.0f, 0.0f, 0.0f));
            glClearNamedFramebufferfv(target.framebuffer.id, GL_DEPTH, 0, stack.floats(1.0f));
        }
    }

    private static void readDiagnosticIdBuffer(CaptureResult result, int framebuffer, int width, int height) {
        try {
            Path outputDir = Path.of(result.request.outputDir);
            Files.createDirectories(outputDir);
            Path idBufferPng = outputDir.resolve(result.request.captureId + "_idbuffer_voxy_" + sanitize(result.mode, "mode") + ".png");
            ReadbackSummary idSummary = readRgbaRoi(
                    framebuffer,
                    result.request.clampedX(width),
                    result.request.clampedY(height),
                    result.request.clampedWidth(width),
                    result.request.clampedHeight(height),
                    idBufferPng
            );
            result.idBufferPng = idBufferPng.toString();
            result.idBufferRaw = idSummary.rawPath;
            result.idBufferTotalPixels = idSummary.totalPixels;
            result.idBufferNonzeroPixels = idSummary.nonzeroPixels;
            result.idBufferNonzeroRatio = idSummary.nonzeroRatio();
            result.idBufferFormat = "RGBA8_SECTION_RENDERER_DIAGNOSTIC_COLOR";
        } catch (Throwable throwable) {
            result.error = appendError(result.error, throwable.getClass().getName() + ":" + throwable.getMessage());
        }
    }

    private static void writeZeroIdBuffer(CaptureResult result) {
        try {
            Path outputDir = Path.of(result.request.outputDir);
            Files.createDirectories(outputDir);
            int width = Math.max(1, result.request.roiWidth);
            int height = Math.max(1, result.request.roiHeight);
            Path idBufferPng = outputDir.resolve(result.request.captureId + "_idbuffer_voxy_" + sanitize(result.mode, "mode") + ".png");
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(image, "png", idBufferPng.toFile());
            Path raw = rawPathFor(idBufferPng);
            Files.write(raw, new byte[width * height * 4]);
            result.idBufferPng = idBufferPng.toString();
            result.idBufferRaw = raw.toString();
            result.idBufferFormat = "RGBA8_ZERO_CONTROL";
            result.idBufferTotalPixels = width * height;
            result.idBufferNonzeroPixels = 0;
            result.idBufferNonzeroRatio = 0.0D;
        } catch (IOException ioe) {
            result.error = appendError(result.error, ioe.getClass().getName() + ":" + ioe.getMessage());
        }
    }

    private static void captureMainFramebuffer(CaptureResult result) {
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget target = mc.getMainRenderTarget();
            int framebuffer = target.frameBufferId;
            int width = Math.max(1, target.width);
            int height = Math.max(1, target.height);
            result.viewportWidth = width;
            result.viewportHeight = height;
            Path outputDir = Path.of(result.request.outputDir);
            Files.createDirectories(outputDir);
            Path finalPng = outputDir.resolve(result.request.captureId + "_final_voxy_" + sanitize(result.mode, "mode") + ".png");
            ReadbackSummary finalSummary = readRgbaRoi(
                    framebuffer,
                    result.request.clampedX(width),
                    result.request.clampedY(height),
                    result.request.clampedWidth(width),
                    result.request.clampedHeight(height),
                    finalPng
            );
            result.finalColorPng = finalPng.toString();
            result.finalColorRaw = finalSummary.rawPath;
            result.finalColorTotalPixels = finalSummary.totalPixels;
            result.finalColorNonzeroPixels = finalSummary.nonzeroPixels;
            result.finalColorNonzeroRatio = finalSummary.nonzeroRatio();
        } catch (Throwable throwable) {
            result.error = appendError(result.error, throwable.getClass().getName() + ":" + throwable.getMessage());
        }
    }

    private static ReadbackSummary readRgbaRoi(int framebuffer, int x, int y, int width, int height, Path pngPath) throws IOException {
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        byte[] rawBytes = new byte[width * height * 4];
        buffer.get(0, rawBytes);
        Path rawPath = rawPathFor(pngPath);
        Files.write(rawPath, rawBytes);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int nonzero = 0;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int index = (row * width + col) * 4;
                int r = buffer.get(index) & 0xFF;
                int g = buffer.get(index + 1) & 0xFF;
                int b = buffer.get(index + 2) & 0xFF;
                int a = buffer.get(index + 3) & 0xFF;
                if (r != 0 || g != 0 || b != 0 || a != 0) {
                    nonzero++;
                }
                image.setRGB(col, height - 1 - row, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(image, "png", pngPath.toFile());
        return new ReadbackSummary(width * height, nonzero, rawPath.toString());
    }

    private static Path rawPathFor(Path pngPath) {
        return pngPath.resolveSibling(pngPath.getFileName().toString().replaceAll("\\.png$", "") + ".rgba");
    }

    private static String sanitize(String value, String fallback) {
        String raw = value == null || value.isBlank() ? fallback : value;
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String appendError(String oldError, String newError) {
        if (oldError == null || oldError.isBlank()) {
            return newError;
        }
        return oldError + "; " + newError;
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    public record CaptureRequest(
            String captureId,
            String outputDir,
            int roiX,
            int roiY,
            int roiWidth,
            int roiHeight,
            boolean diagnosticBuffer
    ) {
        int clampedX(int width) {
            return Math.max(0, Math.min(this.roiX, Math.max(0, width - 1)));
        }

        int clampedY(int height) {
            return Math.max(0, Math.min(this.roiY, Math.max(0, height - 1)));
        }

        int clampedWidth(int width) {
            return Math.max(1, Math.min(this.roiWidth, width - this.clampedX(width)));
        }

        int clampedHeight(int height) {
            return Math.max(1, Math.min(this.roiHeight, height - this.clampedY(height)));
        }
    }

    public static final class CaptureResult {
        private final CaptureRequest request;
        private final String status;
        private final String writtenAt;
        private String mode = "normal";
        private String reason = "";
        private int viewportWidth;
        private int viewportHeight;
        private long frameId;
        private int geometrySections;
        private int activeNodeRequests;
        private long voxySamplesPassed = -1;
        private boolean voxyAnySamplesPassed;
        private int voxySectionDrawCallCount;
        private int voxySectionDrawnGeometryCount;
        private int voxySectionMaxDrawCount;
        private String idBufferFormat = "";
        private String idBufferPng = "";
        private String idBufferRaw = "";
        private int idBufferTotalPixels;
        private int idBufferNonzeroPixels;
        private double idBufferNonzeroRatio;
        private String finalColorPng = "";
        private String finalColorRaw = "";
        private int finalColorTotalPixels;
        private int finalColorNonzeroPixels;
        private double finalColorNonzeroRatio;
        private String error = "";

        private CaptureResult(CaptureRequest request, String status) {
            this.request = request;
            this.status = status;
            this.writtenAt = Instant.now().toString();
        }

        static CaptureResult idle() {
            return new CaptureResult(new CaptureRequest("idle", "", 0, 0, 1, 1, false), "IDLE");
        }

        static CaptureResult queued(CaptureRequest request) {
            return new CaptureResult(request, "QUEUED");
        }

        static CaptureResult completed(CaptureRequest request, String reason, Viewport<?> viewport, int geometrySections, int activeNodeRequests) {
            CaptureResult result = new CaptureResult(request, "PASS_VOXY_GPU_ATTRIBUTION_CAPTURED");
            result.reason = reason;
            result.mode = VoxyClient.getVisualAttributionMode().toLowerCase(Locale.ROOT);
            result.viewportWidth = viewport == null ? 0 : viewport.width;
            result.viewportHeight = viewport == null ? 0 : viewport.height;
            result.frameId = viewport == null ? -1 : viewport.frameId;
            result.geometrySections = geometrySections;
            result.activeNodeRequests = activeNodeRequests;
            return result;
        }

        String toJson() {
            return "{"
                    + "\"schema\":\"voxy.gpu_attribution.v1\","
                    + "\"status\":" + json(this.status) + ","
                    + "\"written_at\":" + json(this.writtenAt) + ","
                    + "\"capture_id\":" + json(this.request.captureId) + ","
                    + "\"output_dir\":" + json(this.request.outputDir) + ","
                    + "\"mode\":" + json(this.mode) + ","
                    + "\"reason\":" + json(this.reason) + ","
                    + "\"roi\":{\"x\":" + this.request.roiX + ",\"y\":" + this.request.roiY
                    + ",\"width\":" + this.request.roiWidth + ",\"height\":" + this.request.roiHeight + "},"
                    + "\"viewport\":{\"width\":" + this.viewportWidth + ",\"height\":" + this.viewportHeight + ",\"frame_id\":" + this.frameId + "},"
                    + "\"geometry_sections\":" + this.geometrySections + ","
                    + "\"active_node_requests\":" + this.activeNodeRequests + ","
                    + "\"voxy_samples_passed\":" + this.voxySamplesPassed + ","
                    + "\"voxy_any_samples_passed\":" + this.voxyAnySamplesPassed + ","
                    + "\"voxy_section_draw_call_count\":" + this.voxySectionDrawCallCount + ","
                    + "\"voxy_section_drawn_geometry_count\":" + this.voxySectionDrawnGeometryCount + ","
                    + "\"voxy_section_max_draw_count\":" + this.voxySectionMaxDrawCount + ","
                    + "\"idbuffer_format\":" + json(this.idBufferFormat) + ","
                    + "\"idbuffer_png\":" + json(this.idBufferPng) + ","
                    + "\"idbuffer_raw\":" + json(this.idBufferRaw) + ","
                    + "\"idbuffer_total_pixels\":" + this.idBufferTotalPixels + ","
                    + "\"idbuffer_nonzero_pixels\":" + this.idBufferNonzeroPixels + ","
                    + "\"idbuffer_nonzero_ratio\":" + this.idBufferNonzeroRatio + ","
                    + "\"final_color_png\":" + json(this.finalColorPng) + ","
                    + "\"final_color_raw\":" + json(this.finalColorRaw) + ","
                    + "\"final_color_total_pixels\":" + this.finalColorTotalPixels + ","
                    + "\"final_color_nonzero_pixels\":" + this.finalColorNonzeroPixels + ","
                    + "\"final_color_nonzero_ratio\":" + this.finalColorNonzeroRatio + ","
                    + "\"error\":" + json(this.error)
                    + "}";
        }
    }

    public static final class Query {
        private final int samplesQuery;
        private boolean active;

        private Query(int samplesQuery) {
            this.samplesQuery = samplesQuery;
            this.active = true;
        }

        static Query start() {
            int query = glGenQueries();
            glBeginQuery(GL_SAMPLES_PASSED, query);
            return new Query(query);
        }

        void stop(CaptureResult result) {
            if (!this.active) {
                return;
            }
            this.active = false;
            try {
                glEndQuery(GL_SAMPLES_PASSED);
                long samples = Integer.toUnsignedLong(glGetQueryObjecti(this.samplesQuery, GL_QUERY_RESULT));
                result.voxySamplesPassed = Math.max(0, result.voxySamplesPassed) + samples;
                result.voxyAnySamplesPassed = result.voxyAnySamplesPassed || samples > 0;
            } finally {
                glDeleteQueries(this.samplesQuery);
            }
        }
    }

    private record ReadbackSummary(int totalPixels, int nonzeroPixels, String rawPath) {
        double nonzeroRatio() {
            return this.totalPixels <= 0 ? 0.0D : (double) this.nonzeroPixels / (double) this.totalPixels;
        }
    }

    private static final class DiagnosticTarget {
        private final int width;
        private final int height;
        private final GlFramebuffer framebuffer;
        private final GlTexture color;
        private final GlTexture depth;

        private DiagnosticTarget(int width, int height) {
            this.width = width;
            this.height = height;
            this.framebuffer = new GlFramebuffer().name("Voxy attribution diagnostic framebuffer");
            this.color = new GlTexture().store(GL_RGBA8, 1, width, height).name("Voxy attribution diagnostic color");
            this.depth = new GlTexture().store(GL_DEPTH_COMPONENT24, 1, width, height).name("Voxy attribution diagnostic depth");
            this.framebuffer
                    .bind(GL_COLOR_ATTACHMENT0, this.color)
                    .bind(GL_DEPTH_ATTACHMENT, this.depth)
                    .setDrawBuffers(GL_COLOR_ATTACHMENT0)
                    .verify();
        }

        private void free() {
            this.framebuffer.free();
            this.color.free();
            this.depth.free();
        }
    }
}
