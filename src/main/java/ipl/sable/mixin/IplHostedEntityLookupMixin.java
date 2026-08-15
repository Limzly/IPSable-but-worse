package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

/**
 * ENTITY-BY-UUID GLOBAL ADDRESSING for plot space — the entity twin of the sub-level UUID
 * bridge.
 *
 * <p>Hosted plot storage can still be referenced across parent charts by UUID. Mods resolve
 * cross-references through {@code ServerLevel.getEntity(uuid)} on their OWN level:
 * <ul>
 *   <li>Simulated's launched plunger calls {@code getOwner()} every tick and
 *       {@code discard()}s itself when the owner is unresolvable.</li>
 *   <li>The plunger PAIR link resolves the partner the same way — a ground-side plunger
 *       whose partner stuck to a ship lost the pair (and discarded itself through the
 *       "pair recorded but unresolvable" branch), and vice versa.</li>
 * </ul>
 *
 * <p>Rule: a lookup MISS on the hosting level falls through to every parent level (code
 * running "in" the hosting dimension is hosted-ship code following a cross-level
 * reference); a miss on any other level additionally checks the hosting level (plot-space
 * residents are addressable from the dimensions their ships occupy — same doctrine as the
 * plot-grid block address space). Entities that were never migrated into plot space are
 * unaffected: they can't be in the hosting level, so parent-level lookups only ever
 * surface ship residents. Non-recursive: the fallthrough reads through the raw
 * {@code getEntities()} getter.
 */
@Mixin(ServerLevel.class)
public abstract class IplHostedEntityLookupMixin {

    @ModifyReturnValue(
        method = "getEntity(Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;",
        at = @At("RETURN")
    )
    private Entity ipl$plotSpaceEntityAddressing(Entity original, UUID uuid) {
        if (original != null) return original;

        ServerLevel self = (ServerLevel) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) return null;

        if (IplDimAgnostic.isHostingLevel(self)) {
            for (ServerLevel other : server.getAllLevels()) {
                if (other == self) continue;
                Entity found = ((IplServerEntityGetterInvoker) other).ipl$entityGetter().get(uuid);
                if (found != null && !found.isRemoved()) {
                    return found;
                }
            }
            return null;
        }

        ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(server);
        if (hosting == null || hosting == self) return null;
        Entity found = ((IplServerEntityGetterInvoker) hosting).ipl$entityGetter().get(uuid);
        return found != null && !found.isRemoved() ? found : null;
    }
}
