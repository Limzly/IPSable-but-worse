package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.function.Consumer;

/**
 * Stamp the immediate chunk send in {@code ServerLevelPlot.addChunkHolder} with the hosting
 * dim when the plot lives in {@code ipl_sable:sublevels}.
 *
 * <p>Most hosted-plot packet emission happens during the hosting level's tick and is covered
 * by {@link IplHostingLevelTickRedirectMixin}. This call is the exception: plot EXPANSION
 * (building past the current plot edge) runs in C2S packet handling, between level ticks,
 * and immediately sends the new chunk to all trackers. Unstamped, that chunk packet would be
 * applied to whichever dimension each recipient currently displays.
 */
@Pseudo
@Mixin(value = ServerLevelPlot.class, remap = false)
public abstract class IplPlotChunkSendStampMixin {

    @WrapOperation(
        method = "addChunkHolder",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/plot/SubLevelPlayerChunkSender;sendChunk(Ljava/util/function/Consumer;Lnet/minecraft/world/level/lighting/LevelLightEngine;Lnet/minecraft/world/level/chunk/LevelChunk;)V"
        ),
        require = 0
    )
    private void ipl$stampHostedChunkSend(
        Consumer<Packet<? super ClientGamePacketListener>> listener,
        LevelLightEngine lightEngine,
        LevelChunk chunk,
        Operation<Void> original
    ) {
        Level level = chunk.getLevel();
        if (IplDimAgnostic.isHostingLevel(level)) {
            PacketRedirection.withForceRedirect(
                (ServerLevel) level, () -> original.call(listener, lightEngine, chunk));
        } else {
            original.call(listener, lightEngine, chunk);
        }
    }
}
