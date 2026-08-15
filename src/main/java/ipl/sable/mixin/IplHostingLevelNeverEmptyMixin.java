package ipl.sable.mixin;

import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * The hosting dimension never counts as "empty".
 *
 * <p>Vanilla skips {@code tickBlockEntities()} (and entity ticking) in a ServerLevel once
 * {@code emptyTime} exceeds 300 — i.e. 15 seconds with no players in the dimension. No
 * player is ever IN {@code ipl_sable:sublevels}, so every block entity on every hosted
 * ship froze after boot: the piston's moving block entity never finished its 2-tick move
 * (extend left a phantom {@code moving_piston} head, desyncing the whole cycle), and
 * furnaces/hoppers/spawners on ships would be equally dead. Scheduled ticks and block
 * events sit OUTSIDE the gate, which is why redstone logic worked while pistons stuck.
 *
 * <p>Hosted ships are observed from other dimensions by design; their level must tick as
 * long as the server does.
 */
@Mixin(ServerLevel.class)
public abstract class IplHostingLevelNeverEmptyMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void ipl$keepHostingLevelTicking(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (IplDimAgnostic.isHostingLevel(self)) {
            self.resetEmptyTime();
        }
    }
}
