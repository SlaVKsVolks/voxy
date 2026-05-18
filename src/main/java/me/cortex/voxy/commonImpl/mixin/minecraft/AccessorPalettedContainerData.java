package me.cortex.voxy.commonImpl.mixin.minecraft;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PalettedContainer.Data.class)
public interface AccessorPalettedContainerData<T> {
    @Accessor("palette")
    Palette<T> voxy$getPalette();

    @Accessor("storage")
    BitStorage voxy$getStorage();
}
