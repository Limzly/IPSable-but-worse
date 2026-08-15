package ipl.sable.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Generic parent-entity lifecycle counterpart to the hosted plot chunk cache bridge. */
@Mixin(Entity.class)
public abstract class IplParentPlotEntityTickingMixin {

    @Inject(method = "setPosRaw(DDD)V", at = @At("TAIL"), require = 0)
    private void ipl$markParentPlotEntityTicking(double x, double y, double z, CallbackInfo ci) {
        ipl.sable.dim.IplParentPlotEntityTicking.update((Entity) (Object) this, x, z);
    }

    @Inject(method = "remove", at = @At("HEAD"), require = 0)
    private void ipl$clearParentPlotEntityTicking(
        Entity.RemovalReason reason, CallbackInfo ci
    ) {
        ipl.sable.dim.IplParentPlotEntityTicking.remove((Entity) (Object) this);
    }
}
