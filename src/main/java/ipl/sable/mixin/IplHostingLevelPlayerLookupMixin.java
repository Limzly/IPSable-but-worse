package ipl.sable.mixin;

import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

/**
 * Resolve player-by-UUID lookups on the HOSTING level server-wide.
 *
 * <p>No player is ever physically inside {@code ipl_sable:sublevels}, yet hosted-sub-level
 * code routinely does {@code subLevel.getLevel().getPlayerByUUID(...)} to find the
 * interacting player (e.g. Simulated's physics-staff drag session, which silently skips its
 * motor setup when the lookup is null — an inert gravity gun). Since the hosting dim has no
 * legitimate same-dim players, falling through to the server-wide player list can only fix
 * such cross-dim patterns, never shadow a real local lookup.
 *
 * <p>NOTE: {@code getPlayerByUUID} is an {@code EntityGetter} interface DEFAULT method —
 * neither {@code Level} nor {@code ServerLevel} declares it, so it cannot be injected into.
 * This mixin MERGES a real override into {@code ServerLevel} that delegates to the interface
 * default and only then applies the hosting-dim fallback.
 */
@Mixin(ServerLevel.class)
public abstract class IplHostingLevelPlayerLookupMixin implements EntityGetter {

    @Unique
    private static long ipl$lastLookupLogMs = 0;

    @Override
    @Nullable
    public Player getPlayerByUUID(UUID uuid) {
        Player original = EntityGetter.super.getPlayerByUUID(uuid);
        if (original != null) return original;

        ServerLevel self = (ServerLevel) (Object) this;
        if (IplDimAgnostic.isHostingLevel((Level) self)
            && self.getServer() != null) {
            Player resolved = self.getServer().getPlayerList().getPlayer(uuid);
            long now = System.currentTimeMillis();
            if (now - ipl$lastLookupLogMs > 2000) {
                ipl$lastLookupLogMs = now;
                org.slf4j.LoggerFactory.getLogger("ipl-hosted-gather").info(
                    "[IPL-PLAYER-LOOKUP] hosting-level getPlayerByUUID({}) -> {}",
                    uuid, resolved == null ? "null" : resolved.getGameProfile().getName());
            }
            return resolved;
        }
        return null;
    }
}
