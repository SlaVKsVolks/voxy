package me.cortex.voxy;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(VoxyCommon.MOD_ID)
public final class NeoVoxyMod {
    public NeoVoxyMod() {
        VoxyCommon.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            VoxyClient.initNeoForge();
        }
    }
}
