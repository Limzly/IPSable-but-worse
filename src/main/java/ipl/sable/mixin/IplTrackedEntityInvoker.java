package ipl.sable.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Access to {@code ChunkMap$TrackedEntity.updatePlayers} for the hosted entity viewer
 * sweep in {@link IplHostingEntityTrackingMixin} (the inner class is package-private, so
 * it is referenced through this duck).
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface IplTrackedEntityInvoker {

    @org.spongepowered.asm.mixin.gen.Accessor("entity")
    net.minecraft.world.entity.Entity ipl$getEntity();

    @Invoker("updatePlayers")
    void ipl$updatePlayers(List<ServerPlayer> players);
}
