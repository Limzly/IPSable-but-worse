package ipl.sable.mixin;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Self-heal for plot-parked parent entities: if any caller downgrades entity-chunk
 * visibility for a plot-range chunk that still hosts live plot entities, restore
 * ENTITY_TICKING immediately — a lapse stop-tracks the entities (clients see them
 * disappear) and queues them for vanilla's chunk unload. Plot chunks are unmistakable
 * by coordinate magnitude. No-op recursion: the restore call re-enters with
 * ENTITY_TICKING and is skipped.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class IplPlotChunkTickingGuardMixin {

    @Inject(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V",
        at = @At("TAIL"), require = 0)
    private void ipl$reassertPlotChunkTicking(
        ChunkPos pos, FullChunkStatus status, CallbackInfo ci
    ) {
        if (status == FullChunkStatus.ENTITY_TICKING) return;
        if (Math.abs(pos.x) < 100_000 && Math.abs(pos.z) < 100_000) return;
        ipl.sable.dim.IplParentPlotEntityTicking.reassertIfActive(this, pos);
    }
}
