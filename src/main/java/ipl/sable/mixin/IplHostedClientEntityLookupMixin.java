package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Client entity-by-NETWORK-ID addressing across the plot-space boundary — see
 * {@link ipl.sable.client.IplClientEntityLookup} for the full rationale (hosted plunger
 * client copies discarded themselves because their spawn packet's owner id resolved
 * against the hosting client level, where the shooter does not exist).
 *
 * <p>Registered in the main list ({@code Level} is common); the fallthrough only runs
 * client-side, and the client-only helper class is loaded lazily on that branch — a
 * dedicated server never touches it.
 */
@Mixin(Level.class)
public abstract class IplHostedClientEntityLookupMixin {

    @ModifyReturnValue(method = "getEntity(I)Lnet/minecraft/world/entity/Entity;", at = @At("RETURN"))
    private Entity ipl$plotSpaceClientEntityAddressing(Entity original, int id) {
        Level self = (Level) (Object) this;
        if (original != null || !self.isClientSide()) {
            return original;
        }
        return ipl.sable.client.IplClientEntityLookup.resolveAcrossPlotSpace(self, id);
    }
}
