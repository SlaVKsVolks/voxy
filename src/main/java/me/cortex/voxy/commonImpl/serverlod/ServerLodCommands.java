package me.cortex.voxy.commonImpl.serverlod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ServerLodCommands {
    private ServerLodCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("voxy")
                .then(Commands.literal("serverlod")
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("contract")
                                .executes(ctx -> contract(ctx.getSource())))
                        .then(Commands.literal("authored")
                                .then(Commands.literal("status")
                                        .executes(ctx -> authoredStatus(ctx.getSource())))
                                .then(Commands.literal("cancel")
                                        .executes(ctx -> cancelAuthored(ctx.getSource()))))
                        .then(Commands.literal("bandwidth")
                                .then(Commands.argument("mbps", IntegerArgumentType.integer(1, 100000))
                                        .executes(ctx -> bandwidth(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "mbps")))))
                        .then(Commands.literal("sync")
                                .then(Commands.literal("now")
                                        .executes(ctx -> serverSyncNow(ctx.getSource()))))
                        .then(Commands.literal("mount")
                                .then(Commands.argument("path", StringArgumentType.greedyString())
                                        .executes(ctx -> mount(ctx.getSource(), StringArgumentType.getString(ctx, "path")))))
                        .then(Commands.literal("validate")
                                .then(Commands.literal("visible")
                                        .executes(ctx -> validateVisible(ctx.getSource(), ""))
                                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                                .executes(ctx -> validateVisible(ctx.getSource(), StringArgumentType.getString(ctx, "options"))))))
                        .then(Commands.literal("build-authored")
                                .then(Commands.literal("status")
                                        .executes(ctx -> authoredStatus(ctx.getSource())))
                                .then(Commands.literal("cancel")
                                        .executes(ctx -> cancelAuthored(ctx.getSource())))
                                .then(Commands.argument("centerX", IntegerArgumentType.integer())
                                        .then(Commands.argument("centerZ", IntegerArgumentType.integer())
                                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> buildAuthored(
                                                                ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "centerX"),
                                                                IntegerArgumentType.getInteger(ctx, "centerZ"),
                                                                IntegerArgumentType.getInteger(ctx, "radiusChunks"),
                                                                ""))
                                                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                                                .executes(ctx -> buildAuthored(
                                                                        ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "centerX"),
                                                                        IntegerArgumentType.getInteger(ctx, "centerZ"),
                                                                        IntegerArgumentType.getInteger(ctx, "radiusChunks"),
                                                                        StringArgumentType.getString(ctx, "options"))))))))
                        .then(Commands.literal("pregen")
                                .then(Commands.literal("surface")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                                .executes(ctx -> pregenSurface(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))))));
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Voxy server LoD: " + ServerLodSyncManager.statusLine() + " " + ServerAuthoredLodBuilder.statusLine()), false);
        return 1;
    }

    private static int contract(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Voxy server LoD NeoForge contract: " + ServerLodDiagnostics.serverLodContractJson()), false);
        return 1;
    }

    private static int authoredStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Voxy authored LoD builder status: " + ServerAuthoredLodBuilder.statusJson()), false);
        return 1;
    }

    private static int cancelAuthored(CommandSourceStack source) {
        return ServerAuthoredLodBuilder.cancelAll(source, "server_command");
    }

    private static int bandwidth(CommandSourceStack source, int mbps) {
        ServerLodSyncManager.setBandwidthMbps(mbps);
        source.sendSuccess(() -> Component.literal("Voxy server LoD bandwidth budget set to " + mbps + " Mbps"), true);
        return 1;
    }

    private static int serverSyncNow(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Voxy server LoD sync is client-manifest driven; clients should run /voxy serverlod sync now or reconnect."), false);
        return 1;
    }

    private static int mount(CommandSourceStack source, String path) {
        Path mounted = Path.of(ServerLodOptions.stripQuotes(path));
        ServerLodSyncManager.mountVlcp3(mounted);
        source.sendSuccess(() -> Component.literal("Mounted Voxy VLCP0003 server LoD region: " + mounted.toAbsolutePath().normalize()), true);
        return 1;
    }

    private static int validateVisible(CommandSourceStack source, String options) {
        String result = ServerLodSyncManager.validateVisible(4096);
        boolean passed = result.contains("passed=true") && !result.contains("passed=false");
        String reportPath = parseOption(options, "report");
        if (!reportPath.isBlank()) {
            try {
                writeValidationReport(reportPath, result, passed);
            } catch (IOException exception) {
                source.sendFailure(Component.literal("Failed to write Voxy server LoD validation report: " + exception.getMessage()));
                return 0;
            }
        }
        source.sendSuccess(() -> Component.literal("Voxy server LoD visible validation: " + result), false);
        return passed ? 1 : 0;
    }

    private static String parseOption(String options, String key) {
        if (options == null || options.isBlank()) {
            return "";
        }
        return ServerLodOptions.parse(options).getOrDefault(key.toLowerCase(), "");
    }

    private static void writeValidationReport(String reportPath, String statusLine, boolean passed) throws IOException {
        Path path = Path.of(reportPath).toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = "{\n"
                + "  \"schema\": \"voxy.server_lod.validate_visible.v1\",\n"
                + "  \"generated_at_ms\": " + System.currentTimeMillis() + ",\n"
                + "  \"passed\": " + passed + ",\n"
                + "  \"status_line\": \"" + escapeJson(statusLine) + "\"\n"
                + "}\n";
        Files.writeString(path, json);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int pregenSurface(CommandSourceStack source, int radius) {
        source.sendSuccess(() -> Component.literal("Voxy server LoD surface pregen command registered. Radius " + radius + " will use the server-side generator bridge in the next implementation slice."), false);
        return 1;
    }

    private static int buildAuthored(CommandSourceStack source, int centerX, int centerZ, int radiusChunks, String options) {
        return ServerAuthoredLodBuilder.start(source, centerX, centerZ, radiusChunks, options);
    }
}
