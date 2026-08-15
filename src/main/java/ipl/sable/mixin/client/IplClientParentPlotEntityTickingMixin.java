package ipl.sable.mixin.client;

import ipl.sable.client.IplClientPlotEntityTicking;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client counterpart of {@code IplParentPlotEntityTickingMixin} — see
 * {@code IplClientPlotEntityTicking}. */
@Mixin(Entity.class)
public abstract class IplClientParentPlotEntityTickingMixin {

    @Inject(method = "setPosRaw(DDD)V", at = @At("TAIL"), require = 0)
    private void ipl$markClientPlotEntityTicking(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level() != null && self.level().isClientSide) {
            IplClientPlotEntityTicking.update(self, x, z);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), require = 0)
    private void ipl$clearClientPlotEntityTicking(
        Entity.RemovalReason reason, CallbackInfo ci
    ) {
        Entity self = (Entity) (Object) this;
        if (self.level() != null && self.level().isClientSide) {
            IplClientPlotEntityTicking.remove(self);
        }
    }
}
