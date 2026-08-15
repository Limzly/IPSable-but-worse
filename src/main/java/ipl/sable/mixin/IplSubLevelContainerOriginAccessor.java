package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Plot-grid origin access for same-slot rehome (see {@code SableRehomeOps}). */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public interface IplSubLevelContainerOriginAccessor {

    @Accessor(value = "originX", remap = false)
    int ipl$originX();

    @Accessor(value = "originZ", remap = false)
    int ipl$originZ();
}
