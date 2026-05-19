package me.cortex.voxy.commonImpl;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

public final class NeoForgeModStatus {
    private NeoForgeModStatus() {
    }

    public static boolean isLoaded(String modId) {
        var modList = ModList.get();
        if (modList != null) {
            return modList.isLoaded(modId);
        }

        var loadingModList = LoadingModList.get();
        return loadingModList != null && loadingModList.getModFileById(modId) != null;
    }
}
