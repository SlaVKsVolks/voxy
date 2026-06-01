package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.debug.RenderStateDiagnostics;
import me.cortex.voxy.client.serverlod.ClientServerLodSync;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.VoxyLodCompilerImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import me.cortex.voxy.commonImpl.serverlod.ServerAuthoredLodBuilder;
import me.cortex.voxy.commonImpl.serverlod.ServerLodSyncManager;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {
    private static net.minecraft.client.multiplayer.ClientLevel requireLevel(CommandContext<CommandSourceStack> ctx) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            ctx.getSource().sendFailure(Component.translatable("You must be in a world to use this command"));
        }
        return level;
    }

    private static String normalizeUserPathInput(String input) {
        String normalized = input.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    public static void register(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(registerTree());
        }
    }

    public static LiteralArgumentBuilder<CommandSourceStack> registerTree() {
        var imports = LiteralArgumentBuilder.<CommandSourceStack>literal("import")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("world")
                        .then(net.minecraft.commands.Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("bobby")
                        .then(net.minecraft.commands.Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("raw")
                        .then(net.minecraft.commands.Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("voxy_lod_compiler")
                        .then(net.minecraft.commands.Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importVoxyLodCompiler)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("zip")
                        .then(net.minecraft.commands.Commands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(net.minecraft.commands.Commands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("current")
                        .executes(VoxyCommands::importCurrentWorldIn))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("distant_horizons")
                            .then(net.minecraft.commands.Commands.argument("sqlDbPath", StringArgumentType.string())
                                    .executes(VoxyCommands::importDistantHorizons)));
        }

        var debug = LiteralArgumentBuilder.<CommandSourceStack>literal("debug")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("verifyTLNChildMask")
                        .executes(ctx->verifyTLNs(ctx, false))
                        .then(net.minecraft.commands.Commands.argument("attemptRepair", BoolArgumentType.bool())
                                .executes(ctx->verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair"))))
                )
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("capture")
                        .executes(VoxyCommands::captureDiagnostics))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("deep")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("start")
                                .executes(ctx -> startDeepDiagnostics(ctx, "normal"))
                                .then(net.minecraft.commands.Commands.argument("profile", StringArgumentType.word())
                                        .executes(ctx -> startDeepDiagnostics(ctx, StringArgumentType.getString(ctx, "profile")))))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("stop")
                                .executes(VoxyCommands::stopDeepDiagnostics))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reset")
                                .executes(VoxyCommands::resetDeepDiagnostics))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                                .executes(VoxyCommands::deepDiagnosticsStatus))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("snapshot")
                                .executes(VoxyCommands::deepDiagnosticsSnapshot)));

        var surfacePregen = LiteralArgumentBuilder.<CommandSourceStack>literal("surfacepregen")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("start")
                        .then(net.minecraft.commands.Commands.argument("radius_chunks", IntegerArgumentType.integer(1))
                                .then(net.minecraft.commands.Commands.argument("full_radius_chunks", IntegerArgumentType.integer(0))
                                        .then(net.minecraft.commands.Commands.argument("below_surface_blocks", IntegerArgumentType.integer(0))
                                                .then(net.minecraft.commands.Commands.argument("above_surface_blocks", IntegerArgumentType.integer(0))
                                                        .then(net.minecraft.commands.Commands.argument("chunks_per_batch", IntegerArgumentType.integer(1, 128))
                                                                .executes(ctx -> startSurfacePregen(ctx, "light"))
                                                                .then(net.minecraft.commands.Commands.argument("source_status", StringArgumentType.word())
                                                                        .executes(ctx -> startSurfacePregen(ctx, StringArgumentType.getString(ctx, "source_status"))))))))))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                        .executes(VoxyCommands::surfacePregenStatus))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                        .executes(VoxyCommands::cancelSurfacePregen));

        var pregenSurface = LiteralArgumentBuilder.<CommandSourceStack>literal("surface")
                .then(net.minecraft.commands.Commands.argument("center_x", IntegerArgumentType.integer())
                        .then(net.minecraft.commands.Commands.argument("center_z", IntegerArgumentType.integer())
                                .then(net.minecraft.commands.Commands.argument("radius_chunks", IntegerArgumentType.integer(1))
                                        .executes(ctx -> startSurfacePregenAt(ctx, "circle"))
                                        .then(net.minecraft.commands.Commands.argument("shape", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("circle", "square"), builder))
                                                .executes(ctx -> startSurfacePregenAt(ctx, StringArgumentType.getString(ctx, "shape")))
                                                .then(net.minecraft.commands.Commands.argument("depth_blocks", IntegerArgumentType.integer(1, 384))
                                                        .executes(ctx -> startSurfacePregenAt(
                                                                ctx,
                                                                StringArgumentType.getString(ctx, "shape"),
                                                                IntegerArgumentType.getInteger(ctx, "depth_blocks"))))))))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("pause")
                        .executes(VoxyCommands::pauseSurfacePregen))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("resume")
                        .executes(VoxyCommands::resumeSurfacePregen))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                        .executes(VoxyCommands::cancelSurfacePregen))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                        .executes(VoxyCommands::surfacePregenStatus));

        var pregen = LiteralArgumentBuilder.<CommandSourceStack>literal("pregen")
                .then(pregenSurface);

        var serverLod = LiteralArgumentBuilder.<CommandSourceStack>literal("serverlod")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                        .executes(VoxyCommands::serverLodStatus))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("authored")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                                .executes(VoxyCommands::serverLodAuthoredStatus))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                                .executes(VoxyCommands::serverLodAuthoredCancel)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("sync")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("now")
                                .executes(VoxyCommands::serverLodSyncNow)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("validate")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("visible")
                                .executes(ctx -> serverLodValidateVisible(ctx, ""))
                                .then(net.minecraft.commands.Commands.argument("options", StringArgumentType.greedyString())
                                        .executes(ctx -> serverLodValidateVisible(ctx, StringArgumentType.getString(ctx, "options"))))))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("build-authored")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                                .executes(VoxyCommands::serverLodAuthoredStatus))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                                .executes(VoxyCommands::serverLodAuthoredCancel))
                        .then(net.minecraft.commands.Commands.argument("centerX", IntegerArgumentType.integer())
                                .then(net.minecraft.commands.Commands.argument("centerZ", IntegerArgumentType.integer())
                                        .then(net.minecraft.commands.Commands.argument("radiusChunks", IntegerArgumentType.integer(0))
                                                .executes(ctx -> serverLodBuildAuthored(ctx, ""))
                                                .then(net.minecraft.commands.Commands.argument("options", StringArgumentType.greedyString())
                                                        .executes(ctx -> serverLodBuildAuthored(ctx, StringArgumentType.getString(ctx, "options"))))))))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cache")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("clear")
                                .executes(VoxyCommands::serverLodCacheClear)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("bandwidth")
                        .then(net.minecraft.commands.Commands.argument("mbps", IntegerArgumentType.integer(1, 100000))
                                .executes(VoxyCommands::serverLodBandwidth)));

        return LiteralArgumentBuilder.<CommandSourceStack>literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(surfacePregen)
                .then(pregen)
                .then(serverLod)
                .then(debug);
    }

    private static int serverLodStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy server LoD client: " + ClientServerLodSync.statusLine()), false);
        return 1;
    }

    private static int serverLodAuthoredStatus(CommandContext<CommandSourceStack> ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() == null) {
            return forwardServerCommand(ctx, "voxy serverlod authored status", "");
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal("Voxy authored LoD builder status: " + ServerAuthoredLodBuilder.statusJson()),
                false
        );
        return 1;
    }

    private static int serverLodAuthoredCancel(CommandContext<CommandSourceStack> ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return forwardServerCommand(ctx, "voxy serverlod authored cancel", "");
        }
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
        int code = ServerAuthoredLodBuilder.cancelAll(source, "client_command");
        ctx.getSource().sendSuccess(
                () -> Component.literal("Voxy authored LoD builder cancel-or-idle: " + ServerAuthoredLodBuilder.statusLine()),
                false
        );
        return code;
    }

    private static int serverLodSyncNow(CommandContext<CommandSourceStack> ctx) {
        ClientServerLodSync.requestSyncNow();
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy server LoD sync requested."), false);
        return 1;
    }

    private static int serverLodCacheClear(CommandContext<CommandSourceStack> ctx) {
        int removed = ClientServerLodSync.clearCache();
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy server LoD cache cleared: " + removed + " files/directories removed."), false);
        return removed;
    }

    private static int serverLodBandwidth(CommandContext<CommandSourceStack> ctx) {
        int mbps = IntegerArgumentType.getInteger(ctx, "mbps");
        ClientServerLodSync.setBandwidthMbps(mbps);
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy server LoD client bandwidth set to " + mbps + " Mbps."), false);
        return 1;
    }

    private static int serverLodValidateVisible(CommandContext<CommandSourceStack> ctx, String options) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() == null) {
            return forwardServerCommand(ctx, "voxy serverlod validate visible", options);
        }

        String result = ServerLodSyncManager.validateVisible(4096);
        boolean passed = result.contains("passed=true") && !result.contains("passed=false");
        String reportPath = parseCommandOption(options, "report");
        if (!reportPath.isBlank()) {
            try {
                writeServerLodValidationReport(reportPath, result, passed);
            } catch (IOException exception) {
                ctx.getSource().sendFailure(Component.literal("Failed to write Voxy server LoD validation report: " + exception.getMessage()));
                return 0;
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy server LoD visible validation: " + result), false);
        return passed ? 1 : 0;
    }

    private static int serverLodBuildAuthored(CommandContext<CommandSourceStack> ctx, String options) {
        int centerX = IntegerArgumentType.getInteger(ctx, "centerX");
        int centerZ = IntegerArgumentType.getInteger(ctx, "centerZ");
        int radiusChunks = IntegerArgumentType.getInteger(ctx, "radiusChunks");
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return forwardServerCommand(ctx, "voxy serverlod build-authored " + centerX + " " + centerZ + " " + radiusChunks, options);
        }

        CommandSourceStack source = server.createCommandSourceStack().withPermission(4);
        int code = ServerAuthoredLodBuilder.start(source, centerX, centerZ, radiusChunks, options);
        if (code == 0) {
            ctx.getSource().sendFailure(Component.literal("Voxy server LoD authored build not started: " + ServerAuthoredLodBuilder.statusLine()));
        } else {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Voxy server LoD authored build dispatched: " + ServerAuthoredLodBuilder.statusLine()),
                    false
            );
        }
        return code;
    }

    private static int forwardServerCommand(CommandContext<CommandSourceStack> ctx, String baseCommand, String options) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            ctx.getSource().sendFailure(Component.literal("No server connection is available for Voxy server LoD command forwarding"));
            return 0;
        }
        String command = options == null || options.isBlank() ? baseCommand : baseCommand + " " + options;
        minecraft.player.connection.sendCommand(command);
        ctx.getSource().sendSuccess(() -> Component.literal("Forwarded Voxy server LoD command to server: /" + command), false);
        return 1;
    }

    private static String parseCommandOption(String options, String key) {
        if (options == null || options.isBlank()) {
            return "";
        }
        String prefix = key + "=";
        for (String token : options.split("\\s+")) {
            if (token.startsWith(prefix)) {
                return stripCommandQuotes(token.substring(prefix.length()));
            }
        }
        return "";
    }

    private static String stripCommandQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void writeServerLodValidationReport(String reportPath, String statusLine, boolean passed) throws IOException {
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

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();
        return 0;
    }

    private static int verifyTLNs(CommandContext<CommandSourceStack> ctx, boolean attemptRepair) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }

    private static int captureDiagnostics(CommandContext<CommandSourceStack> ctx) {
        RenderStateDiagnostics.captureNow("command");
        Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("voxy.diagnostics.capture.done"));
        return 0;
    }

    private static int startDeepDiagnostics(CommandContext<CommandSourceStack> ctx, String profile) {
        RenderCorrectnessDiagnostics.configureRuntimeProfile(profile);
        RenderCorrectnessDiagnostics.resetOutput();
        Path summary = RenderCorrectnessDiagnostics.writeSummary("start_" + RenderCorrectnessDiagnostics.activeProfile());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Voxy deep diagnostics started: "
                        + RenderCorrectnessDiagnostics.statusLine()
                        + " summary="
                        + summary
        ), false);
        return 0;
    }

    private static int stopDeepDiagnostics(CommandContext<CommandSourceStack> ctx) {
        Path summary = RenderCorrectnessDiagnostics.writeSummary("stop");
        RenderCorrectnessDiagnostics.configureRuntimeProfile("off");
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy deep diagnostics stopped. summary=" + summary), false);
        return 0;
    }

    private static int resetDeepDiagnostics(CommandContext<CommandSourceStack> ctx) {
        if (!RenderCorrectnessDiagnostics.isRuntimeEnabled()) {
            RenderCorrectnessDiagnostics.configureRuntimeProfile("normal");
        }
        RenderCorrectnessDiagnostics.resetOutput();
        Path summary = RenderCorrectnessDiagnostics.writeSummary("reset");
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy deep diagnostics reset. summary=" + summary), false);
        return 0;
    }

    private static int deepDiagnosticsStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy deep diagnostics: " + RenderCorrectnessDiagnostics.statusLine()), false);
        return 0;
    }

    private static int deepDiagnosticsSnapshot(CommandContext<CommandSourceStack> ctx) {
        RenderStateDiagnostics.captureNow("deep_diagnostics_snapshot");
        Path summary = RenderCorrectnessDiagnostics.writeSummary("snapshot");
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Voxy deep diagnostics snapshot: "
                        + RenderCorrectnessDiagnostics.statusLine()
                        + " summary="
                        + summary
        ), false);
        return 0;
    }

    private static int startSurfacePregen(CommandContext<CommandSourceStack> ctx, String sourceStatusName) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var level = requireLevel(ctx);
        if (level == null) {
            return 1;
        }
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            ctx.getSource().sendFailure(Component.literal("Voxy surface pregen currently requires singleplayer/integrated server access"));
            return 1;
        }
        int radius = IntegerArgumentType.getInteger(ctx, "radius_chunks");
        int fullRadius = IntegerArgumentType.getInteger(ctx, "full_radius_chunks");
        int below = IntegerArgumentType.getInteger(ctx, "below_surface_blocks");
        int above = IntegerArgumentType.getInteger(ctx, "above_surface_blocks");
        int chunksPerBatch = IntegerArgumentType.getInteger(ctx, "chunks_per_batch");
        net.minecraft.world.level.chunk.status.ChunkStatus sourceStatus;
        try {
            sourceStatus = VoxySurfacePregen.parseSourceStatus(sourceStatusName);
        } catch (IllegalArgumentException error) {
            ctx.getSource().sendFailure(Component.literal(error.getMessage()));
            return 1;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int centerChunkX = ((int)Math.floor(camera.x)) >> 4;
        int centerChunkZ = ((int)Math.floor(camera.z)) >> 4;
        boolean started = VoxySurfacePregen.start(centerChunkX, centerChunkZ, radius, fullRadius, below, above, chunksPerBatch, sourceStatus);
        if (!started) {
            ctx.getSource().sendFailure(Component.literal("Voxy surface pregen is already running or could not start"));
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Started Voxy surface pregen. " + VoxySurfacePregen.statusText()), false);
        return 0;
    }

    private static int surfacePregenStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("Voxy surface pregen: " + VoxySurfacePregen.statusText()), false);
        return 0;
    }

    private static int startSurfacePregenAt(CommandContext<CommandSourceStack> ctx, String shape) {
        return startSurfacePregenAt(ctx, shape, VoxySurfacePregen.DEFAULT_BELOW_SURFACE_BLOCKS);
    }

    private static int startSurfacePregenAt(CommandContext<CommandSourceStack> ctx, String shape, int depthBlocks) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (requireLevel(ctx) == null) {
            return 1;
        }
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            ctx.getSource().sendFailure(Component.literal("Voxy surface pregen currently requires singleplayer/integrated server access"));
            return 1;
        }
        String normalizedShape = shape == null ? "circle" : shape.toLowerCase(Locale.ROOT);
        if (!"circle".equals(normalizedShape) && !"square".equals(normalizedShape)) {
            ctx.getSource().sendFailure(Component.literal("shape must be circle or square"));
            return 1;
        }
        int centerX = IntegerArgumentType.getInteger(ctx, "center_x");
        int centerZ = IntegerArgumentType.getInteger(ctx, "center_z");
        int radius = IntegerArgumentType.getInteger(ctx, "radius_chunks");
        boolean square = "square".equals(normalizedShape);
        boolean started = VoxySurfacePregen.start(
                centerX,
                centerZ,
                radius,
                0,
                Math.max(1, depthBlocks),
                VoxySurfacePregen.DEFAULT_ABOVE_SURFACE_BLOCKS,
                4,
                square,
                net.minecraft.world.level.chunk.status.ChunkStatus.LIGHT
        );
        if (!started) {
            ctx.getSource().sendFailure(Component.literal("Voxy surface pregen is already running or could not start"));
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Started Voxy native surface pregen. " + VoxySurfacePregen.statusText()), false);
        return 0;
    }

    private static int pauseSurfacePregen(CommandContext<CommandSourceStack> ctx) {
        if (!VoxySurfacePregen.pause()) {
            ctx.getSource().sendFailure(Component.literal("No Voxy surface pregen job is running"));
            return 1;
        }
        return 0;
    }

    private static int resumeSurfacePregen(CommandContext<CommandSourceStack> ctx) {
        if (!VoxySurfacePregen.resume()) {
            ctx.getSource().sendFailure(Component.literal("No Voxy surface pregen job is running"));
            return 1;
        }
        return 0;
    }

    private static int cancelSurfacePregen(CommandContext<CommandSourceStack> ctx) {
        if (!VoxySurfacePregen.cancel()) {
            ctx.getSource().sendFailure(Component.literal("No Voxy surface pregen job is running"));
            return 1;
        }
        return 0;
    }


    private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (requireLevel(ctx) == null) {
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            ctx.getSource().sendFailure(Component.literal("Could not find Distant Horizons database path: " + dbFile.getAbsolutePath()));
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                ctx.getSource().sendFailure(Component.literal("Could not find DistantHorizons.sqlite in: " + dbFile.getParentFile().getAbsolutePath()));
                return 1;
            }
        }
        if (!dbFile.isFile() || !dbFile.canRead()) {
            ctx.getSource().sendFailure(Component.literal("Distant Horizons database is not a readable file: " + dbFile.getAbsolutePath()));
            return 1;
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null)return 1;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter))?0:1;
    }

    private static boolean fileBasedImporter(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            Logger.warn("Import path invalid or not a directory: ", directory);
            return false;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importVoxyLodCompiler(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var level = requireLevel(ctx);
        if (level == null) {
            return 1;
        }
        File file = new File(ctx.getArgument("path", String.class));
        if (!file.isFile() || !file.canRead()) {
            ctx.getSource().sendFailure(Component.literal("Voxy LoD compiler file is not readable: " + file.getAbsolutePath()));
            return 1;
        }
        var engine = WorldIdentifier.ofEngine(level);
        if (engine == null) {
            ctx.getSource().sendFailure(Component.literal("No active Voxy world engine"));
            return 1;
        }
        boolean started = instance.getImportManager().makeAndRunIfNone(engine, () -> new VoxyLodCompilerImporter(file, engine, level));
        if (started) {
            ctx.getSource().sendSuccess(() -> Component.literal("Started Voxy LoD compiler import: " + file.getAbsolutePath()), false);
            return 0;
        }
        ctx.getSource().sendFailure(Component.literal("A Voxy import is already running"));
        return 1;
    }

    private static int importBobby(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        Path root = dir.toAbsolutePath().normalize();
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = root.resolve(str.substring(0, idx)).normalize();
                if (!dir.startsWith(root)) {
                    return Suggestions.empty();
                }
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
            dir = root;
        }

        try (var worlds = Files.list(dir)) {
            for (var world : worlds.toList()) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {
            Logger.warn("Failed to build import suggestions from ", dir, e);
        }

        return sb.buildFuture();
    }


    private static int importCurrentWorldIn(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (requireLevel(ctx) == null) {
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendFailure(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if ((!regionPath.toFile().exists())||!regionPath.toFile().isDirectory()) {
            ctx.getSource().sendFailure(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(regionPath.toFile())?0:1;
    }

    private static int importWorld(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (requireLevel(ctx) == null) {
            return 1;
        }

        var name = normalizeUserPathInput(ctx.getArgument("world_name", String.class));
        var file = Minecraft.getInstance().gameDirectory.toPath().resolve("saves").resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) return 1;
            return fileBasedImporter(dimFile)?0:1;
            //We are in a world directory, so import the current dimension we are in
            /*
            for (var dim : new String[]{"overworld", "the_nether", "the_end"}) {//This is so annoying that you cant loop through all the dimensions
                var id = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(dim));
                var dimPath = DimensionType.getStorageFolder(id, file);
                dimPath = dimPath.resolve("region");
                var dimFile = dimPath.toFile();
                if (dimFile.isDirectory()) {//exists and is a directory
                    if (!fileBasedImporter(dimFile)) {
                        Logger.error("Failed to import dimension: " + id);
                    }
                }
            }*/
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<CommandSourceStack> ctx) {
        if (requireLevel(ctx) == null) {
            return 1;
        }
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        if (!zip.exists() || !zip.isFile() || !zip.canRead()) {
            ctx.getSource().sendFailure(Component.literal("Zip path is not a readable file: " + zip.getAbsolutePath()));
            return 1;
        }
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (IllegalArgumentException ignored) {
            // Optional argument.
        }
        innerDir = innerDir.replace('\\', '/');
        if (!innerDir.endsWith("/")) {
            innerDir += "/";
        }

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}
