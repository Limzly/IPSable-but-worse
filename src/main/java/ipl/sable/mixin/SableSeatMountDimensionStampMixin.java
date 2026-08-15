package ipl.sable.mixin;

import ipl.sable.SableBridge;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;

/**
 * Stamp IP's dimension field onto the {@code ClientboundPlayerPositionPacket}
 * that Sable sends when a player mounts a seat on a sub-level.
 *
 * <p><b>The crash this fixes:</b> IP appends a dimension {@code ResourceKey} to
 * every {@code ClientboundPlayerPositionPacket} on write
 * ({@code MixinPlayerPositionLookS2CPacket.onWrite}). IP sets that field in its
 * own {@code teleport()} overwrite, but Sable's seat-mount path
 * ({@code ServerPlayerMixin.sable$adjustTeleportPacket}) builds and sends the
 * packet <em>directly</em>, bypassing {@code teleport()}. The field stays at its
 * default {@code null}, and on encode {@code buf.writeResourceKey(null)} throws an
 * {@code NPE} -> {@code EncoderException: Failed to encode packet
 * 'clientbound/minecraft:player_position'} -> the player is disconnected the
 * instant they sit on a Sable seat. (No portal needed to reproduce.)
 *
 * <p><b>Why we target the send chokepoint, not Sable's method:</b> Sable's
 * {@code sable$adjustTeleportPacket} is a mixin handler -- it only exists merged
 * into {@code ServerPlayer} at runtime, not as a standalone class, so mixin
 * refuses to target it ("Cannot add target ... because the target is a mixin").
 * {@code ServerCommonPacketListenerImpl.send(Packet)} is a real vanilla method
 * that every outbound packet -- including Sable's direct send -- passes through.
 *
 * <p><b>Kept surgical by condition, not location:</b> we act ONLY on the exact
 * seat-mount fingerprint -- a {@code ClientboundPlayerPositionPacket} whose IP
 * dimension field is still {@code null} (so IP's own teleport path, which already
 * stamps, is never touched) AND whose target player is riding a sub-level vehicle.
 * Anything else falls through unchanged: a different bypass path that sends an
 * unstamped position packet will keep crashing and thereby reveal itself, rather
 * than being silently masked by a broad fallback.
 *
 * <p><b>Which dimension:</b> NOT the player's current dimension. The packet's
 * coordinates are sub-level-LOCAL (Sable computes them via
 * {@code logicalPose().transformPositionInverse(player.position())}), so IP must
 * resolve them against the dimension the <em>sub-level</em> lives in. We derive it
 * from the player's vehicle via {@link SableBridge#subLevelDimensionOfVehicle}.
 * Today the sub-level dim and the player dim coincide (you can only mount a
 * same-dim seat), so this fixes the crash with the provably-correct value; it is
 * also correct by construction for the future "click a seat across a portal" /
 * "stay seated through a traversal" cases, where the seat's sub-level is in the
 * destination dim while the player is (still) source-side.
 *
 * <p>Runs on the server thread (Sable's {@code connection.send} is called from
 * {@code startRiding}), so reading {@code getPlayer().getVehicle()} is safe here.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class SableSeatMountDimensionStampMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), require = 0)
    private void ipl$stampSeatMountDimension(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundPlayerPositionPacket)) {
            return;
        }
        IEPlayerPositionLookS2CPacket duck = (IEPlayerPositionLookS2CPacket) packet;

        // IP's own teleport() path already stamped a non-null dimension -- leave it.
        // Only a bypass send (Sable's seat mount) reaches here with a null field.
        if (duck.ip_getPlayerDimension() != null) {
            return;
        }

        // send() lives on the common listener; the player only exists on the game
        // subclass. We need the player to find the seat's sub-level, so bail if this
        // is a login/config-phase listener (which never sends position packets anyway).
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl gameListener)) {
            return;
        }
        ServerPlayer player = gameListener.getPlayer();
        if (player == null) {
            return;
        }

        // The seat-mount fingerprint: rider on a sub-level vehicle. Returns null if
        // the player isn't riding a sub-level vehicle (or Sable is absent) -- in
        // which case we leave the field null and let the encode crash reveal the
        // (different) bypass path, per the targeted-fix philosophy.
        ResourceKey<Level> subLevelDim =
            SableBridge.subLevelDimensionOfVehicle(player.getVehicle());
        if (subLevelDim != null) {
            duck.ip_setPlayerDimension(subLevelDim);
        }
    }
}
