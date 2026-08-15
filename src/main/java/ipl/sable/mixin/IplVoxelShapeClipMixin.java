package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import ipl.sable.transit.IplStraddleCollisionClip;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;

/**
 * GLOBAL portal cut for sub-level collision boxes.
 *
 * <p>{@code sable$allBoxes()} is the single funnel every consumer of a sub-level's
 * collision geometry goes through -- Sable's own movement solver and step-up probe, its
 * fall helper, and ANY third-party mod that iterates a sub-level's boxes through the same
 * interface. Wrapping individual call sites one at a time was a losing game: each site
 * that was missed reappeared as a symptom (the player darkened inside blocks, forced into
 * a crouch or prone pose, or standing on geometry that should have been cut off behind
 * the portal plane).
 *
 * <p>Cutting at the source instead means the cut is universal by construction. It is
 * gated by {@link IplStraddleCollisionClip}, whose keep-planes are installed and cleared
 * per sub-level inside the intersection loop, so when no cut is installed this returns
 * the original iterator untouched and costs one thread-local read.
 *
 * <p>{@code @Pseudo} plus the module-wide {@code defaultRequire: 0}: the target method is
 * itself added by Sable's own mixin, so if Sable is absent or renames it, this mixin is
 * skipped instead of crashing the game.
 */
@Pseudo
@Mixin(value = VoxelShape.class, priority = 1500)
public abstract class IplVoxelShapeClipMixin {

    @SuppressWarnings("unchecked")
    @Inject(
        method = "sable$allBoxes",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private void ipl$clipAllBoxesAtPortalPlane(CallbackInfoReturnable<Object> cir) {
        Object returned = cir.getReturnValue();
        if (!(returned instanceof Iterator<?> iterator)) return;
        if (!IplStraddleCollisionClip.isActive()) return;
        cir.setReturnValue(
            IplStraddleCollisionClip.clip((Iterator<BoundingBox3dc>) iterator));
    }
}
