package ipl.sable.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes exact LevelRenderer world for Veil's active stage callback. */
@Mixin(LevelRenderer.class)
public interface IplStaffPortalBeamPassMixin {

    @Accessor("level")
    ClientLevel ipl$getLevel();
}
