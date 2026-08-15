package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Invalidates Sable's per-partial-tick pose cache after an atomic portal handoff. */
@Mixin(value = ClientSubLevel.class, remap = false)
public interface IplClientSubLevelRenderPoseAccessor {

    @Accessor("lastRenderPosePartialTick")
    void ipl$setLastRenderPosePartialTick(float partialTick);
}
