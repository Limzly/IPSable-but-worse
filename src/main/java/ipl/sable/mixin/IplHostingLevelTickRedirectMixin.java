package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.function.BooleanSupplier;

/**
 * Run the entire {@code ipl_sable:sublevels} level tick under IP's packet force-redirect.
 *
 * <p>Sable ticks its {@code ServerSubLevelContainer} from inside {@code ServerLevel.tick}
 * (Sable's {@code ServerLevelMixin}), so this single wrap stamps EVERY packet the hosting
 * dimension emits during its tick — tracking full-syncs, bounds/movement updates, plot chunk
 * broadcasts via vanilla {@code ChunkHolder.broadcastChanges}, light packets, block-entity
 * data — with the hosting dim id. IP's client unwrap then dispatches them under the
 * sublevels {@code ClientLevel}, so they land in the client's sublevels-dim
 * {@code ClientSubLevelContainer} regardless of which dimension the player is in.
 *
 * <p>This is the dim-agnostic analogue of the legacy per-call wraps in
 * {@link SableCrossDimTrackingMixin} (which still cover the few send paths that run outside
 * the hosting level tick, e.g. removals triggered from other dims; nesting is a no-op).
 */
@Mixin(MinecraftServer.class)
public abstract class IplHostingLevelTickRedirectMixin {

    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"
        )
    )
    private void ipl$wrapHostingLevelTickInRedirect(
        ServerLevel level, BooleanSupplier hasTimeLeft, Operation<Void> original
    ) {
        if (IplDimAgnostic.isHostingLevel(level)) {
            PacketRedirection.withForceRedirect(level, () -> original.call(level, hasTimeLeft));
        } else {
            original.call(level, hasTimeLeft);
        }
    }
}
