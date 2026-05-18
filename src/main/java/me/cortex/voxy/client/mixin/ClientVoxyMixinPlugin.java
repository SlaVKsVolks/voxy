package me.cortex.voxy.client.mixin;

import net.neoforged.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean irisInstalled;
    private static boolean sodiumInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = ModList.get().isLoaded("valkyrienskies");
        nvidiumInstalled = ModList.get().isLoaded("nvidium");
        irisInstalled = ModList.get().isLoaded("iris");
        sodiumInstalled = ModList.get().isLoaded("sodium");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".nvidium.")) {
            return nvidiumInstalled;
        }
        if (mixinClassName.contains(".iris.")) {
            return irisInstalled;
        }
        if (mixinClassName.contains(".sodium.")) {
            return sodiumInstalled;
        }
        return true;
    }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
