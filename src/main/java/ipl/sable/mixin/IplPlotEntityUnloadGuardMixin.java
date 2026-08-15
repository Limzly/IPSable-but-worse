package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ipl.sable.dim.IplParentPlotEntityTicking;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Parent-level entities parked at hosted plot coordinates live in a chunk the parent
 * dimension will never actually load — the plot's terrain is loaded in the hosting
 * dimension. Vanilla's {@code PersistentEntitySectionManager.processChunkUnload} treats
 * such a chunk as unloadable and, the moment its entity visibility lapses, serializes the
 * entity into parent region storage at ~20M coordinates and removes it
 * (UNLOADED_TO_CHUNK) — deleting live, physics-attached entities like the ship-end
 * plunger. Players survive that sweep because {@code shouldBeSaved()} returns false;
 * give plot-parked entities the same semantics. Persistence is not lost meaningfully:
 * an entity saved at raw plot coordinates in the parent dimension could never be
 * legitimately restored anyway.
 */
@Mixin(Entity.class)
public abstract class IplPlotEntityUnloadGuardMixin {

    @Unique
    private static final boolean IPL$ENABLED =
        !"false".equals(System.getProperty("ipl.sable.plotEntityUnloadGuard"));

    @ModifyReturnValue(method = "shouldBeSaved", at = @At("RETURN"))
    private boolean ipl$plotEntitiesAreTransient(boolean original) {
        if (!original || !IPL$ENABLED) return original;
        return !IplParentPlotEntityTicking.isPlotEntity((Entity) (Object) this);
    }
}
