package ipl.sable.mixin;

import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side kicks project the entity's plot-space position through the sub-level's
 * CLIENT pose. If that pose hasn't synced yet (default: position 0, rotationPoint ≈ plot
 * COM), the projection maps a ~20M plot coordinate to ≈ the world origin — the entity
 * visually teleports to (0,0,0) while the server, whose pose is always live, keeps it in
 * place. A kick through an unsynced pose is never right on the client: skip it and let the
 * server's authoritative kick (if any) sync the position down a tick later.
 *
 * <p>Trigger discipline: a hosted ship genuinely posed at the exact world origin with no
 * rotation offset does not occur in practice, and even then the cost of skipping is one
 * tick of the entity staying put client-side.
 */
@Pseudo
@Mixin(value = EntitySubLevelUtil.class, remap = false)
public abstract class IplClientKickPoseGuardMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-kick-guard");

    @Unique
    private static final boolean IPL$ENABLED =
        !"false".equals(System.getProperty("ipl.sable.clientKickPoseGuard"));

    @Inject(method = "kickEntity", at = @At("HEAD"), remap = false, require = 0,
        cancellable = true)
    private static void ipl$guardUnsyncedClientKick(
        SubLevel subLevel, Entity entity, CallbackInfo ci
    ) {
        if (!IPL$ENABLED || !entity.level().isClientSide) return;

        var pose = subLevel.logicalPose();
        boolean poseUnsynced = pose.position().lengthSquared() < 1.0e-6;
        // Plot-space raw coordinates sit ~20M out; a world-frame entity never does.
        boolean entityInPlotSpace =
            entity.getX() * entity.getX() + entity.getZ() * entity.getZ() > 1.0e12;
        if (poseUnsynced && entityInPlotSpace) {
            IPL$LOG.warn("[IPL-KICK-GUARD] skipped client kick through unsynced pose: "
                + "entity={} id={} at=({}, {}, {}) sub={}",
                entity.getType().getDescriptionId(), entity.getId(),
                String.format("%.1f", entity.getX()), String.format("%.1f", entity.getY()),
                String.format("%.1f", entity.getZ()), subLevel.getUniqueId());
            ci.cancel();
        }
    }
}
