package ipl.sable.mixin;

import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Route WORLD-FRAME block access on the hosting level to the contextual PARENT level.
 *
 * <p>Mirror image of {@link IplPlotDeferredLogicMixin}: that one routes plot-frame positions
 * reached from parent dimensions INTO the hosting level; this one routes parent-frame world
 * positions reached from hosted BE code OUT to the parent dimension. Sable's Create compat
 * (drill/saw mining boxes, deployer activation, harvester crop visits) transforms plot
 * positions through {@code logicalPose()} into parent-frame world coordinates and then
 * reads, breaks, places, and animates them via the BE's own level — which, hosted, is the
 * empty {@code ipl_sable:sublevels}. With the {@link IplWorldFrameContext} set around plot
 * BE ticks, every such access lands in the dimension the ship actually occupies.
 *
 * <p>Two mechanisms, per spec §20.0(8): methods ServerLevel inherits from {@code Level}
 * (getBlockState/getFluidState/getBlockEntity/setBlock/destroyBlock/isLoaded) are MERGED as
 * real overrides delegating to super when not routing; methods ServerLevel itself defines
 * (addFreshEntity/destroyBlockProgress/levelEvent/playSeededSound/gameEvent) get HEAD
 * injects. Routed composite ops (e.g. {@code destroyBlock}) run entirely parent-side, so
 * drops, events and neighbor updates are native. Drop items created against the hosting
 * level are re-leveled before insertion ({@link IplEntityLevelInvoker}).
 *
 * <p>Chunk lookups ({@code getChunk}) and entity queries ({@code getEntities*}) are routed
 * too, gated on chunk-grid / AABB coordinates: third-party content (Simulated's assembler
 * disassembling into the world, deployers interacting with parent-world mobs, ground
 * checks) reaches the dimension the ship actually occupies with no per-mod patches.
 */
@Mixin(value = ServerLevel.class, priority = 1200)
public abstract class IplHostedWorldFrameRouterMixin extends Level {

    @Unique
    private static long ipl$lastRouteLogMs = 0;

    protected IplHostedWorldFrameRouterMixin(
        WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess,
        Holder<DimensionType> dimensionTypeRegistration, Supplier<ProfilerFiller> profiler,
        boolean isClientSide, boolean isDebug, long biomeZoomSeed, int maxChainedNeighborUpdates
    ) {
        super(levelData, dimension, registryAccess, dimensionTypeRegistration, profiler,
            isClientSide, isDebug, biomeZoomSeed, maxChainedNeighborUpdates);
    }

    @Unique
    @Nullable
    private ServerLevel ipl$worldFrameTarget(double x, double z) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!IplDimAgnostic.isHostingLevel(self)) return null;
        // Plot positions are in the 20M grid. World coordinates may legitimately reach
        // Minecraft's ~30M border, so the old 1M test misclassified valid parent terrain as
        // plot data and made generic area checks (assembler ground scan) read hosting void.
        // A contextual world-frame call is already explicit; only reject coordinates that
        // actually belong to a live hosted plot.
        if (ipl$isHostedPlotPosition(self, x, z)) return null;
        ServerLevel parent = IplWorldFrameContext.current();
        if (parent == null || parent == self) return null;

        long now = System.currentTimeMillis();
        if (now - ipl$lastRouteLogMs > 2000) {
            ipl$lastRouteLogMs = now;
            org.slf4j.LoggerFactory.getLogger("ipl-hosted-gather").info(
                "[IPL-WFR] routing world-frame access ({}, {}) -> {}",
                (int) x, (int) z, parent.dimension().location());
        }
        return parent;
    }

    @Unique
    @Nullable
    private ServerLevel ipl$worldFrameTarget(BlockPos pos) {
        return ipl$worldFrameTarget(pos.getX(), pos.getZ());
    }

    @Unique
    private static boolean ipl$isHostedPlotPosition(ServerLevel hosting, double x, double z) {
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(hosting);
        return container != null && container.inBounds(
            net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(x)),
            net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(z))
        );
    }

    // ------------------------------------------------------------------
    // Level-inherited methods: merged overrides.
    // ------------------------------------------------------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        return target != null ? target.getBlockState(pos) : super.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        return target != null ? target.getFluidState(pos) : super.getFluidState(pos);
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        return target != null ? target.getBlockEntity(pos) : super.getBlockEntity(pos);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        return target != null
            ? target.setBlock(pos, state, flags, recursionLeft)
            : super.setBlock(pos, state, flags, recursionLeft);
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        return target != null
            ? target.destroyBlock(pos, dropBlock, entity, recursionLeft)
            : super.destroyBlock(pos, dropBlock, entity, recursionLeft);
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        return target != null ? target.isLoaded(pos) : super.isLoaded(pos);
    }

    /** Chunk-grid variant of the world-frame gate: plot chunks live at |chunk| >= 62,500. */
    @Unique
    @Nullable
    private ServerLevel ipl$worldFrameChunkTarget(int chunkX, int chunkZ) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (!IplDimAgnostic.isHostingLevel(self)) return null;
        if (ipl$isHostedPlotPosition(self, (double) chunkX * 16.0, (double) chunkZ * 16.0)) return null;
        ServerLevel parent = IplWorldFrameContext.current();
        return parent == self ? null : parent;
    }

    @Override
    public net.minecraft.world.level.chunk.LevelChunk getChunk(int chunkX, int chunkZ) {
        ServerLevel target = ipl$worldFrameChunkTarget(chunkX, chunkZ);
        return target != null ? target.getChunk(chunkX, chunkZ) : super.getChunk(chunkX, chunkZ);
    }

    @Override
    public net.minecraft.world.level.chunk.ChunkAccess getChunk(
        int chunkX, int chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean requireChunk
    ) {
        ServerLevel target = ipl$worldFrameChunkTarget(chunkX, chunkZ);
        return target != null
            ? target.getChunk(chunkX, chunkZ, status, requireChunk)
            : super.getChunk(chunkX, chunkZ, status, requireChunk);
    }

    /** AABB variant of the world-frame gate (entity queries), keyed on the box center. */
    @Unique
    @Nullable
    private ServerLevel ipl$worldFrameTarget(net.minecraft.world.phys.AABB area) {
        return ipl$worldFrameTarget(
            (area.minX + area.maxX) * 0.5, (area.minZ + area.maxZ) * 0.5);
    }

    @Override
    public java.util.List<Entity> getEntities(
        @Nullable Entity entity, net.minecraft.world.phys.AABB area,
        java.util.function.Predicate<? super Entity> predicate
    ) {
        ServerLevel target = ipl$worldFrameTarget(area);
        return target != null
            ? target.getEntities(entity, area, predicate)
            : super.getEntities(entity, area, predicate);
    }

    @Override
    public <T extends Entity> java.util.List<T> getEntities(
        net.minecraft.world.level.entity.EntityTypeTest<Entity, T> typeTest,
        net.minecraft.world.phys.AABB area, java.util.function.Predicate<? super T> predicate
    ) {
        ServerLevel target = ipl$worldFrameTarget(area);
        return target != null
            ? target.getEntities(typeTest, area, predicate)
            : super.getEntities(typeTest, area, predicate);
    }

    // Scalar world bounds belong to the same explicit terrain frame as routed chunks. Chunk
    // section reads remain safe because callers receive a parent LevelChunk whose own section
    // accessor owns its height profile; returning hosting bounds here made generic external
    // operations reject valid Nether/modded-dimension terrain before they ever read a chunk.
    @Override
    public int getMinBuildHeight() {
        ServerLevel self = (ServerLevel) (Object) this;
        ServerLevel parent = IplWorldFrameContext.current();
        return IplDimAgnostic.isHostingLevel(self) && parent != null && parent != self
            ? parent.getMinBuildHeight() : super.getMinBuildHeight();
    }

    @Override
    public int getMaxBuildHeight() {
        ServerLevel self = (ServerLevel) (Object) this;
        ServerLevel parent = IplWorldFrameContext.current();
        return IplDimAgnostic.isHostingLevel(self) && parent != null && parent != self
            ? parent.getMaxBuildHeight() : super.getMaxBuildHeight();
    }

    @Override
    public int getSectionsCount() {
        ServerLevel self = (ServerLevel) (Object) this;
        ServerLevel parent = IplWorldFrameContext.current();
        return IplDimAgnostic.isHostingLevel(self) && parent != null && parent != self
            ? parent.getSectionsCount() : super.getSectionsCount();
    }

    // ------------------------------------------------------------------
    // ServerLevel-defined methods: HEAD injects.
    // ------------------------------------------------------------------

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void ipl$routeAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel target = ipl$worldFrameTarget(entity.getX(), entity.getZ());
        if (target != null) {
            // Drop items / XP orbs are constructed against the hosting level just before
            // insertion; re-home them so the entity's level field matches its storage.
            if (entity.level() == (Object) this) {
                ((IplEntityLevelInvoker) entity).ipl$invokeSetLevel(target);
            }
            cir.setReturnValue(target.addFreshEntity(entity));
        }
    }

    /**
     * Disassembly's entity return path: {@code SimAssemblyHelper.disassembleSubLevel} moves
     * plot-resident entities (glue, item frames, seats) back to world coordinates via
     * {@code entity.levelCallback.onRemove(CHANGED_DIMENSION)} + {@code addDuringTeleport}
     * on the BE's own level — hosted, the void. Route it like {@code addFreshEntity}, so
     * the entities re-enter the dimension the ship actually occupied.
     */
    @Inject(method = "addDuringTeleport", at = @At("HEAD"), cancellable = true)
    private void ipl$routeAddDuringTeleport(Entity entity, CallbackInfo ci) {
        ServerLevel target = ipl$worldFrameTarget(entity.getX(), entity.getZ());
        if (target != null) {
            if (entity.level() == (Object) this) {
                ((IplEntityLevelInvoker) entity).ipl$invokeSetLevel(target);
            }
            target.addDuringTeleport(entity);
            ci.cancel();
        }
    }

    @Inject(method = "addWithUUID", at = @At("HEAD"), cancellable = true)
    private void ipl$routeAddWithUUID(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel target = ipl$worldFrameTarget(entity.getX(), entity.getZ());
        if (target != null) {
            if (entity.level() == (Object) this) {
                ((IplEntityLevelInvoker) entity).ipl$invokeSetLevel(target);
            }
            cir.setReturnValue(target.addWithUUID(entity));
        }
    }

    /** POI bookkeeping for routed world-frame block moves (disassembly placements). */
    @Inject(
        method = "onBlockStateChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routeOnBlockStateChange(
        BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci
    ) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        if (target != null) {
            target.onBlockStateChange(pos, oldState, newState);
            ci.cancel();
        }
    }

    /**
     * CLIENT block sync for routed world-frame writes. Sable's {@code moveBlocks}
     * (assembly/disassembly) bypasses {@code Level.setBlock}: it writes through the
     * accelerator's {@code chunk.setBlockState} — which the accelerator override lands
     * in the PARENT chunk, so the server state is right — and then notifies clients
     * manually via {@code level.sendBlockUpdated} on the BE's OWN level. Hosted, that
     * is this dimension, whose chunk map has no holder at world coordinates: the
     * update packet silently vanished and clients kept seeing air where the server
     * had restored the ship's blocks. The notification must follow the write.
     */
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
    private void ipl$routeSendBlockUpdated(
        BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci
    ) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        if (target != null) {
            target.sendBlockUpdated(pos, oldState, newState, flags);
            ci.cancel();
        }
    }

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"), cancellable = true)
    private void ipl$routeDestroyBlockProgress(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        if (target != null) {
            target.destroyBlockProgress(breakerId, pos, progress);
            ci.cancel();
        }
    }

    @Inject(
        method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routeLevelEvent(@Nullable Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        ServerLevel target = ipl$worldFrameTarget(pos);
        if (target != null) {
            target.levelEvent(player, type, pos, data);
            ci.cancel();
        }
    }

    @Inject(
        method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routePlaySeededSound(
        @Nullable Player player, double x, double y, double z, Holder<SoundEvent> sound,
        SoundSource category, float volume, float pitch, long seed, CallbackInfo ci
    ) {
        ServerLevel target = ipl$worldFrameTarget(x, z);
        if (target != null) {
            target.playSeededSound(player, x, y, z, sound, category, volume, pitch, seed);
            ci.cancel();
        }
    }

    @Inject(
        method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ipl$routeGameEvent(
        Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context, CallbackInfo ci
    ) {
        ServerLevel target = ipl$worldFrameTarget(pos.x, pos.z);
        if (target != null) {
            target.gameEvent(gameEvent, pos, context);
            ci.cancel();
        }
    }
}
