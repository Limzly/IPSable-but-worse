package ipl.sable.mixin.client;

import ipl.sable.client.IplClientFlywheelReroute;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client hooks feeding {@link IplClientFlywheelReroute}: every block entity entering or
 * leaving a HOSTING-level plot chunk gets its Flywheel visual (re)registered with the
 * PARENT dimension's visualization manager instead of the never-rendered hosting one.
 *
 * <p>{@code addAndRegisterBlockEntity} is the single client-side add chokepoint (both the
 * chunk-packet BE path via {@code getBlockEntity(pos, IMMEDIATE)} and
 * {@code setBlockState}-created BEs); {@code removeBlockEntity} covers targeted removals and
 * {@code clearAllBlockEntities} covers chunk unload.
 */
@Mixin(LevelChunk.class)
public abstract class IplHostedFlywheelVisualRerouteMixin {

    @Inject(method = "addAndRegisterBlockEntity", at = @At("TAIL"))
    private void ipl$rerouteVisualOnAdd(BlockEntity blockEntity, CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (self.getLevel().isClientSide()) {
            IplClientFlywheelReroute.onBlockEntityAdded(self.getLevel(), blockEntity);
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void ipl$rerouteVisualOnRemove(BlockPos pos, CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (!self.getLevel().isClientSide()) return;
        BlockEntity blockEntity = self.getBlockEntities().get(pos);
        if (blockEntity != null) {
            IplClientFlywheelReroute.onBlockEntityRemoved(self.getLevel(), blockEntity);
        }
    }

    @Inject(method = "clearAllBlockEntities", at = @At("HEAD"))
    private void ipl$rerouteVisualsOnChunkClear(CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (!self.getLevel().isClientSide()) return;
        for (BlockEntity blockEntity : self.getBlockEntities().values()) {
            IplClientFlywheelReroute.onBlockEntityRemoved(self.getLevel(), blockEntity);
        }
    }
}
