package ipl.sable.mixin.client;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes ClipContext's modes so a transformed copy can be constructed faithfully. */
@Mixin(ClipContext.class)
public interface IplClipContextAccessor {

    @Accessor("block")
    ClipContext.Block ipl$getBlock();

    @Accessor("fluid")
    ClipContext.Fluid ipl$getFluid();

    @Accessor("collisionContext")
    CollisionContext ipl$getCollisionContext();
}
