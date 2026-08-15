package ipl.sable.mixin.client;

import ipl.sable.dim.IplClientWorldFrameContext;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.WritableLevelData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Supplier;

/**
 * CLIENT mirror of {@link ipl.sable.mixin.IplHostedWorldFrameRouterMixin}: route WORLD-FRAME
 * block reads on the hosting {@code ClientLevel} to the contextual PARENT level.
 *
 * <p>Hosted plot BEs tick on the hosting client level (IP remote-world ticking) and probe
 * world-frame positions through {@code this.level} — an Offroad wheel's visual suspension
 * {@code clip(...)} (rides {@code getBlockState}/{@code getFluidState} via
 * {@code BlockGetter.clip}'s default traversal), redstone {@code getSignal} neighbor reads,
 * ground-friction lookups. Hosted, those land in the void. With
 * {@link IplClientWorldFrameContext} armed around plot BE ticks, they read the terrain the
 * ship is actually rolling over.
 *
 * <p>Read-only surface by design — client code must not mutate the parent level; writes
 * arrive via packets on the parent's own path.
 */
@Mixin(value = ClientLevel.class, priority = 1200)
public abstract class IplHostedClientWorldFrameRouterMixin extends Level {

    protected IplHostedClientWorldFrameRouterMixin(
        WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess,
        Holder<DimensionType> dimensionTypeRegistration, Supplier<ProfilerFiller> profiler,
        boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates
    ) {
        super(levelData, dimension, registryAccess, dimensionTypeRegistration, profiler,
            isClientSide, isDebug, biomeZoomSeed, maxChainedNeighborUpdates);
    }

    @Unique
    @Nullable
    private ClientLevel ipl$clientWorldFrameTarget(BlockPos pos) {
        if (!IplDimAgnostic.isHostingLevel(this)) return null;
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(this);
        if (container != null && container.inBounds(pos)
            && container.getPlot(pos.getX() >> 4, pos.getZ() >> 4) != null) return null;
        ClientLevel parent = IplClientWorldFrameContext.current();
        return parent == null || parent == (Object) this ? null : parent;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        ClientLevel target = ipl$clientWorldFrameTarget(pos);
        return target != null ? target.getBlockState(pos) : super.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        ClientLevel target = ipl$clientWorldFrameTarget(pos);
        return target != null ? target.getFluidState(pos) : super.getFluidState(pos);
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        ClientLevel target = ipl$clientWorldFrameTarget(pos);
        return target != null ? target.getBlockEntity(pos) : super.getBlockEntity(pos);
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        ClientLevel target = ipl$clientWorldFrameTarget(pos);
        return target != null ? target.isLoaded(pos) : super.isLoaded(pos);
    }
}
