package me.cortex.voxy.commonImpl.serverlod;

import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.lang.reflect.Method;

public final class ServerLodNetworking {
    private ServerLodNetworking() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ServerLodConstants.PROTOCOL_VERSION).optional();
        registrar.playToServer(ServerLodPayloads.ClientHello.TYPE, ServerLodPayloads.ClientHello.STREAM_CODEC,
                (payload, context) -> ServerLodSyncManager.handleHello(payload, (ServerPlayer) context.player()));
        registrar.playToServer(ServerLodPayloads.TileRequest.TYPE, ServerLodPayloads.TileRequest.STREAM_CODEC,
                (payload, context) -> ServerLodSyncManager.handleTileRequest(payload, (ServerPlayer) context.player()));
        registrar.playToServer(ServerLodPayloads.TileAck.TYPE, ServerLodPayloads.TileAck.STREAM_CODEC,
                (payload, context) -> ServerLodSyncManager.handleAck(payload, (ServerPlayer) context.player()));
        registrar.playToServer(ServerLodPayloads.ClientCandidateUpload.TYPE, ServerLodPayloads.ClientCandidateUpload.STREAM_CODEC,
                (payload, context) -> ServerLodSyncManager.handleCandidateUpload(payload, (ServerPlayer) context.player()));

        registrar.playToClient(ServerLodPayloads.ServerManifest.TYPE, ServerLodPayloads.ServerManifest.STREAM_CODEC,
                (payload, context) -> invokeClient("handleManifest", ServerLodPayloads.ServerManifest.class, payload));
        registrar.playToClient(ServerLodPayloads.TileBatch.TYPE, ServerLodPayloads.TileBatch.STREAM_CODEC,
                (payload, context) -> invokeClient("handleTileBatch", ServerLodPayloads.TileBatch.class, payload));
        registrar.playToClient(ServerLodPayloads.ServerInvalidate.TYPE, ServerLodPayloads.ServerInvalidate.STREAM_CODEC,
                (payload, context) -> invokeClient("handleInvalidate", ServerLodPayloads.ServerInvalidate.class, payload));
    }

    private static void invokeClient(String methodName, Class<?> payloadType, Object payload) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        try {
            Class<?> type = Class.forName("me.cortex.voxy.client.serverlod.ClientServerLodSync");
            Method method = type.getMethod(methodName, payloadType);
            method.invoke(null, payload);
        } catch (ReflectiveOperationException e) {
            Logger.error("Failed to dispatch Voxy server LoD client payload " + methodName + ": " + e.getMessage());
        }
    }
}
