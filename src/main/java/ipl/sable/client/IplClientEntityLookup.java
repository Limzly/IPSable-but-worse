package ipl.sable.client;

import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.mixin.IplLevelEntityGetterInvoker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * CLIENT half of entity-by-id global addressing across the plot-space boundary.
 *
 * <p>The server half ({@code IplHostedEntityLookupMixin}) already resolves UUID references
 * (projectile owners, plunger pair links) between the hosting dimension and its parents.
 * The client resolves entities by NETWORK ID — and hit the same wall: a plunger stuck to a
 * hosted ship lives in the hosting {@code ClientLevel}, its spawn packet's owner id points
 * at the local player in the PARENT client level, so {@code recreateFromPacket}'s owner
 * lookup missed, {@code cachedOwner} stayed null, and the client copy of the plunger
 * {@code discard()}ed itself on its first tick (owner-null branch) — invisible even with a
 * perfectly healthy server entity. The pair-link lookup ({@code OTHER_PLUNGER_ID}) misses
 * the same way from the other side.
 *
 * <p>Fallthrough rule (mirror of the server): a miss on the hosting client level searches
 * the other client worlds; a miss on any other client level searches only the hosting
 * client level. Reads use the raw entity getters — non-recursive by construction.
 */
public final class IplClientEntityLookup {

    private IplClientEntityLookup() {}

    @Nullable
    public static Entity resolveAcrossPlotSpace(Level self, int id) {
        if (!(self instanceof ClientLevel selfClient)) return null;
        boolean selfHosting = IplDimAgnostic.isHostingLevel(selfClient);
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            if (world == selfClient) continue;
            if (!selfHosting && !IplDimAgnostic.isHostingLevel(world)) {
                continue; // only bridge ACROSS the plot-space boundary
            }
            Entity found = ((IplLevelEntityGetterInvoker) world).ipl$entityGetter().get(id);
            if (found != null && !found.isRemoved()) {
                return found;
            }
        }
        return null;
    }
}
