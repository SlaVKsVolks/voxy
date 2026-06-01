package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.VoxyMergedRenderDistance;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;
import java.util.function.Supplier;

@Mixin(value = SodiumConfigBuilder.class, remap = false)
public class MixinSodiumConfigBuilder {
    @WrapOperation(
            method = "buildGeneralPage",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;setRange(III)Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;",
                    ordinal = 0
            )
    )
    private IntegerOptionBuilder voxy$mergedRenderDistanceRange(
            IntegerOptionBuilder instance,
            int min,
            int max,
            int interval,
            Operation<IntegerOptionBuilder> original
    ) {
        if (!VoxyMergedRenderDistance.usesMergedSlider()) {
            return original.call(instance, min, max, interval);
        }
        return original.call(instance, 2, 512, 1);
    }

    @WrapOperation(
            method = "buildGeneralPage",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;setBinding(Ljava/util/function/Consumer;Ljava/util/function/Supplier;)Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;",
                    ordinal = 0
            )
    )
    private IntegerOptionBuilder voxy$mergedRenderDistanceBinding(
            IntegerOptionBuilder instance,
            Consumer<Integer> save,
            Supplier<Integer> load,
            Operation<IntegerOptionBuilder> original
    ) {
        if (!VoxyMergedRenderDistance.usesMergedSlider()) {
            return original.call(instance, save, load);
        }
        return original.call(
                instance,
                (Consumer<Integer>) VoxyMergedRenderDistance::setVisualFromSlider,
                (Supplier<Integer>) VoxyMergedRenderDistance::sliderValue
        );
    }
}
