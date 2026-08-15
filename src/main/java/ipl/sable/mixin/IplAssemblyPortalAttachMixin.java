package ipl.sable.mixin;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.transit.IplShipNetherPortal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Assembly captures contained portals: after {@code assembleBlocks} moves a glue box's
 * blocks into the new sub-level's plot, any IP portal entity whose origin sat inside the
 * assembled bounds is attached to the ship — its breakable frame shape translated to
 * plot coordinates and the cluster anchored ({@link IplShipNetherPortal#attachOnAssembly}).
 *
 * <p>All assembly paths (commands, glue gizmo, gametests) funnel through this one
 * static method, so the RETURN hook covers every way a frame can be swallowed.
 */
@Pseudo
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class IplAssemblyPortalAttachMixin {

    @Inject(method = "assembleBlocks", at = @At("RETURN"), remap = false, require = 0)
    private static void ipl$attachContainedPortals(
        ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks, BoundingBox3ic bounds,
        CallbackInfoReturnable<ServerSubLevel> cir
    ) {
        IplShipNetherPortal.queueAssemblyCapture(level, anchor, bounds, cir.getReturnValue());
    }
}
