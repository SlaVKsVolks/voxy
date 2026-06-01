package me.cortex.voxy;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.serverlod.ServerAuthoredLodBuilder;
import me.cortex.voxy.commonImpl.serverlod.ServerLodCommands;
import me.cortex.voxy.commonImpl.serverlod.ServerLodNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(VoxyCommon.MOD_ID)
public final class NeoVoxyMod {
    public NeoVoxyMod(IEventBus modEventBus) {
        VoxyCommon.init();
        modEventBus.addListener(ServerLodNetworking::register);
        NeoForge.EVENT_BUS.addListener(ServerLodCommands::register);
        NeoForge.EVENT_BUS.addListener(ServerAuthoredLodBuilder::onServerTick);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            VoxyClient.initNeoForge();
        }
    }
}
