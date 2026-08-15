package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Make Veil's {@link PacketContext#level()} return the IP-redirected dimension when a
 * Sable packet is being dispatched under {@code ClientWorldLoader.withSwitchedWorld}.
 *
 * <p><b>What we tried first that failed:</b> {@code SableContainerClientRedirectMixin}
 * intercepted {@code SubLevelContainer.getContainer(Level)} at HEAD and substituted the
 * level argument with {@code Minecraft.level} whenever {@code isWorldSwitched} was true.
 * The diagnostic log from build {@code cde6ac8} proved that fires ~8000 times with
 * substitution actually changing the result -- and many of those substitutions were
 * <i>wrong</i>. Example from the log:
 * <pre>
 *   arg=minecraft:overworld mcLevel=minecraft:the_nether isWorldSwitched=true substituted=true -> minecraft:the_nether
 * </pre>
 * That's IP's portal-view <i>rendering</i> path (player in overworld looking through a
 * portal at the nether -> IP swaps {@code Minecraft.level} to nether for the duration of
 * that render). During that swap, any code asking for the <i>overworld's</i> container
 * (e.g. Sable's render hook for rendering the overworld airship through the portal) got
 * its argument silently rerouted to the nether container -- which doesn't have the
 * airship -> render hook short-circuits or sees nothing. Hence "airship visible but
 * doesn't move" was actually a compound failure: live movement packets weren't applying
 * (real bug) AND the portal-view rendering of the airship was hitting the wrong
 * container intermittently (collateral damage from the over-broad substitution).
 *
 * <p><b>This mixin instead:</b> intercepts only Veil's {@code PacketContext.level()},
 * which is the call path Sable's packet handlers use to determine which level's
 * container they should mutate. Other code (renderers, world iteration, explicit
 * per-dim lookups) calls {@code SubLevelContainer.getContainer(Level)} directly with the
 * level it actually wants and is unaffected.
 *
 * <p><b>Why this is the semantically correct change:</b> Veil's default
 * {@code PacketContext.level()} reads {@code player.level()} -- i.e., whichever dim the
 * client's player is <i>currently</i> in. For non-IP setups that always matches the dim
 * the packet originated from. Under IP's redirected dispatch, {@code Minecraft.level} is
 * swapped to the dim the packet originated from (the source dim) while
 * {@code player.level()} stays at the player's actual current dim. The packet
 * <i>belongs</i> to {@code Minecraft.level} during dispatch -- that's the whole point of
 * IP's swap -- so {@code Minecraft.level} is the right answer here.
 *
 * <p>Loaded client-side only; on a dedicated server, packet contexts are
 * {@code ServerPacketContext} which has its own {@code level()} override that doesn't
 * go through this default. And {@code Minecraft.getInstance()} would NPE on a server.
 */
@Pseudo
@Mixin(value = PacketContext.class, remap = false)
public interface VeilPacketContextLevelMixin {

    // NB: interface mixins cannot carry fields (Mixin's processor rejects any non-shadow
    // field with InvalidInterfaceMixinException) -- if logging is ever re-added here, put
    // the Logger in a plain holder class outside the mixin package and reference it.

    @ModifyReturnValue(method = "level", at = @At("RETURN"), remap = false, require = 1)
    default Level ipl$redirectLevelDuringIpSwap(Level original) {
        if (!ClientWorldLoader.getIsWorldSwitched()) {
            return original;
        }
        Level mcLevel = Minecraft.getInstance().level;
        if (mcLevel == null || mcLevel == original) {
            return original;
        }
        return mcLevel;
    }
}
