package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;

import java.util.List;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (VoxyCommon.isAvailable()) {
                try {
                    List<ModOptions> options = ConfigManager.CONFIG != null ? ConfigManager.CONFIG.getModOptions() : List.of();
                    Object voxyPage = options.stream()
                            .filter(modOptions -> "voxy".equals(modOptions.configId()) && !modOptions.pages().isEmpty())
                            .map(modOptions -> modOptions.pages().get(0))
                            .findFirst()
                            .orElse(null);

                    if (voxyPage instanceof OptionPage castPage) {
                        return (VideoSettingsScreen) VideoSettingsScreen.createScreen(parent, castPage);
                    }

                    if (voxyPage != null) {
                        Logger.info("Voxy config page type mismatch, falling back to root Sodium screen: " + voxyPage.getClass().getName());
                    } else {
                        Logger.info("Voxy config page missing in Sodium config list, falling back to root Sodium screen.");
                    }

                    if (voxyPage == null) {
                        return (VideoSettingsScreen) VideoSettingsScreen.createScreen(parent);
                    }

                    try {
                        return (VideoSettingsScreen) VideoSettingsScreen.class
                                .getMethod("createScreen", net.minecraft.client.gui.screens.Screen.class, voxyPage.getClass())
                                .invoke(null, parent, voxyPage);
                    } catch (ReflectiveOperationException ignored) {
                        // Fall through to root screen.
                    }
                } catch (RuntimeException throwable) {
                    Logger.info("Voxy config screen fallback engaged after config lookup failure: " + throwable.getClass().getSimpleName());
                }
                return (VideoSettingsScreen) VideoSettingsScreen.createScreen(parent);
            } else {
                return null;
            }
        };
    }
}
