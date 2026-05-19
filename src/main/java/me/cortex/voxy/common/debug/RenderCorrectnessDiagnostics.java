package me.cortex.voxy.common.debug;

import me.cortex.voxy.common.Logger;

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
import java.util.concurrent.atomic.LongAdder;

public final class RenderCorrectnessDiagnostics {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("voxy.renderCorrectnessDiagnostics", "false"));
    private static final Path OUTPUT_FILE = Path.of(
            System.getProperty("voxy.renderCorrectnessDiagnosticsFile", "testharness/voxy_render_correctness.ndjson")
    );

    private static final AtomicBoolean initLogged = new AtomicBoolean(false);
    private static final AtomicBoolean ioFailureLogged = new AtomicBoolean(false);

    public static final LongAdder uploadIngestTotal = new LongAdder();
    public static final LongAdder uploadIngestZeroTotal = new LongAdder();
    public static final LongAdder uploadIngestSkippedNoWorld = new LongAdder();
    public static final LongAdder uploadIngestSkippedChunkStatus = new LongAdder();
    public static final LongAdder chunkBoundAdd = new LongAdder();
    public static final LongAdder chunkBoundRemove = new LongAdder();
    public static final LongAdder depthGuardSkips = new LongAdder();
    public static final LongAdder projectionGuardSkips = new LongAdder();

    private RenderCorrectnessDiagnostics() {
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
        if (queued) {
            uploadIngestTotal.increment();
            if (sectionAir) {
                uploadIngestZeroTotal.increment();
            }
        }
        event("ingest", stage, x, y, z, sectionAir, queued, reason);
    }

    public static void chunkBound(String action, long pos) {
        if ("add".equals(action)) {
            chunkBoundAdd.increment();
        } else if ("remove".equals(action)) {
            chunkBoundRemove.increment();
        }
        event("chunk_bound", action, pos, "", "");
    }

    public static void depthGuard(String stage, String reason, int sourceFramebuffer, int targetFramebuffer, int width, int height) {
        depthGuardSkips.increment();
        event("depth_guard", stage, sourceFramebuffer, targetFramebuffer, width, height, reason);
    }

    public static void projectionGuard(String stage, String reason) {
        projectionGuardSkips.increment();
        event("projection_guard", stage, 0, 0, 0, 0, reason);
    }

    private static void event(String type, String stage, int x, int y, int z, boolean flag, boolean result, String reason) {
        if (!ENABLED) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"" + escape(type) + "\",\"stage\":\"" + escape(stage)
                + "\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                + ",\"flag\":" + flag + ",\"result\":" + result + ",\"reason\":\"" + escape(reason) + "\"}");
    }

    private static void event(String type, String stage, long value, String key, String reason) {
        if (!ENABLED) {
            return;
        }
        writeLine("{\"ts\":\"" + escape(isoNow()) + "\",\"type\":\"" + escape(type) + "\",\"stage\":\"" + escape(stage)
                + "\",\"value\":" + value + ",\"key\":\"" + escape(key) + "\",\"reason\":\"" + escape(reason) + "\"}");
    }

    private static void event(String type, String stage, int a, int b, int c, int d, String reason) {
        if (!ENABLED) {
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
        try {
            Path parent = OUTPUT_FILE.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    OUTPUT_FILE,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
            if (initLogged.compareAndSet(false, true)) {
                Logger.info("Render correctness diagnostics active. Output=", OUTPUT_FILE.toAbsolutePath().toString());
            }
        } catch (IOException ioe) {
            if (ioFailureLogged.compareAndSet(false, true)) {
                Logger.error("Render correctness diagnostics write failure", ioe);
            }
        }
    }
}
