package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public class VoxyCommon {
    public static final String MOD_ID = "voxy";
    public static final String MOD_VERSION = resolveVersion();
    public static final boolean IS_DEDICATED_SERVER = FMLLoader.getDist() != null && FMLLoader.getDist().isDedicatedServer();
    public static final boolean IS_IN_MINECRAFT = NeoForgeModStatus.isLoaded(MOD_ID);
    private static boolean initialized;

    private static String resolveVersion() {
        var modList = ModList.get();
        if (modList == null) {
            return "<LOADING>";
        }
        return modList.getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("<UNKNOWN>");
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!IS_IN_MINECRAFT) {
            Logger.error("Running voxy without minecraft");
            return;
        }
        Serialization.init();
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        INSTANCE = FACTORY.create();
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
