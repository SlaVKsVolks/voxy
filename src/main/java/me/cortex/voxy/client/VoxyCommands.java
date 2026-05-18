package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {
    private static net.minecraft.client.multiplayer.ClientLevel requireLevel(CommandContext<FabricClientCommandSource> ctx) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            ctx.getSource().sendError(Component.translatable("You must be in a world to use this command"));
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

    public static LiteralArgumentBuilder<FabricClientCommandSource> register() {
        var imports = ClientCommandManager.literal("import")
                .then(ClientCommandManager.literal("world")
                        .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(ClientCommandManager.literal("bobby")
                        .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(ClientCommandManager.literal("raw")
                        .then(ClientCommandManager.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(ClientCommandManager.literal("zip")
                        .then(ClientCommandManager.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(ClientCommandManager.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(ClientCommandManager.literal("current")
                        .executes(VoxyCommands::importCurrentWorldIn))
                .then(ClientCommandManager.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(ClientCommandManager.literal("distant_horizons")
                            .then(ClientCommandManager.argument("sqlDbPath", StringArgumentType.string())
                                    .executes(VoxyCommands::importDistantHorizons)));
        }

        var debug = ClientCommandManager.literal("debug")
                .then(ClientCommandManager.literal("verifyTLNChildMask")
                        .executes(ctx->verifyTLNs(ctx, false))
                        .then(ClientCommandManager.argument("attemptRepair", BoolArgumentType.bool())
                                .executes(ctx->verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair"))))
                );

        return ClientCommandManager.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(ClientCommandManager.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(debug);
    }

    private static int reloadInstance(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
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

    private static int verifyTLNs(CommandContext<FabricClientCommandSource> ctx, boolean attemptRepair) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }


    private static int importDistantHorizons(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (requireLevel(ctx) == null) {
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            ctx.getSource().sendError(Component.literal("Could not find Distant Horizons database path: " + dbFile.getAbsolutePath()));
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                ctx.getSource().sendError(Component.literal("Could not find DistantHorizons.sqlite in: " + dbFile.getParentFile().getAbsolutePath()));
                return 1;
            }
        }
        if (!dbFile.isFile() || !dbFile.canRead()) {
            ctx.getSource().sendError(Component.literal("Distant Horizons database is not a readable file: " + dbFile.getAbsolutePath()));
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

    private static int importRaw(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
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


    private static int importCurrentWorldIn(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (requireLevel(ctx) == null) {
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendError(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if ((!regionPath.toFile().exists())||!regionPath.toFile().isDirectory()) {
            ctx.getSource().sendError(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(regionPath.toFile())?0:1;
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
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

    private static int importZip(CommandContext<FabricClientCommandSource> ctx) {
        if (requireLevel(ctx) == null) {
            return 1;
        }
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        if (!zip.exists() || !zip.isFile() || !zip.canRead()) {
            ctx.getSource().sendError(Component.literal("Zip path is not a readable file: " + zip.getAbsolutePath()));
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
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
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

    private static int cancelImport(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}
