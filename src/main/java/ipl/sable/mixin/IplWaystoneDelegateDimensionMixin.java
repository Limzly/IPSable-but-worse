package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ipl.sable.dim.IplHostedWaystoneDimension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hosted-ship waystones report the ship's VISIBLE dimension — see
 * {@link IplHostedWaystoneDimension}. Targets Waystones' delegate base class (string
 * target, no compile dep; absent without Waystones installed): the Sable-compat mod's
 * wrappers (visible source, tracked target) all extend it, and Waystones' range check
 * compares the player's dimension against exactly this method's return.
 */
@Pseudo
@Mixin(targets = "net.blay09.mods.waystones.api.WaystoneDelegate", remap = false)
public abstract class IplWaystoneDelegateDimensionMixin {

    @ModifyReturnValue(method = "getDimension", at = @At("RETURN"), remap = false, require = 0)
    private ResourceKey<Level> ipl$hostedWaystoneVisibleDimension(ResourceKey<Level> original) {
        return IplHostedWaystoneDimension.visibleDimension(this, original);
    }
}
