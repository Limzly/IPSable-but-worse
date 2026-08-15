package qouteall.imm_ptl.core.chunk_loading;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.q_misc_util.my_util.SignalArged;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Vanilla use a 2D array to store the chunk references on client and cannot store the chunks that are far from player.
 * This use map to store the chunk references, to eliminate such limitation.
 * (Two maps, one for main thread and one for other threads)
 */
//@OnlyIn(Dist.CLIENT)
@IPVanillaCopy
public class ImmPtlClientChunkMap extends ClientChunkCache {
    private static final Logger LOGGER = LogManager.getLogger();
    
    // the most chunk accesses are from the main thread,
    // so we use two maps to reduce synchronization.
    // the main thread accesses this map, without synchronization
    protected final Long2ObjectOpenHashMap<LevelChunk> chunkMapForMainThread =
        new Long2ObjectOpenHashMap<>();
    // other threads read this map, with synchronization
    protected final Long2ObjectOpenHashMap<LevelChunk> chunkMapForOtherThreads =
        new Long2ObjectOpenHashMap<>();
    
    public final Thread mainThread;
    
    public static final SignalArged<LevelChunk> clientChunkLoadSignal = new SignalArged<>();
    public static final SignalArged<LevelChunk> clientChunkUnloadSignal = new SignalArged<>();
    
    public ImmPtlClientChunkMap(ClientLevel clientWorld, int loadDistance) {
        super(clientWorld, 1);
        // the chunk array is unused. make it small by passing 1 as load distance to super constructor

        mainThread = ((IEMinecraftClient) Minecraft.getInstance()).ip_getRunningThread();
    }

    /** Sable plot routing: stand-in for vacant plot-grid coords (see getChunk). */
    private LevelChunk ipl$plotEmptyChunk;

    private LevelChunk ipl$getPlotEmptyChunk() {
        if (ipl$plotEmptyChunk == null) {
            ipl$plotEmptyChunk = new net.minecraft.world.level.chunk.EmptyLevelChunk(
                this.level, net.minecraft.world.level.ChunkPos.ZERO,
                this.level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)
            );
        }
        return ipl$plotEmptyChunk;
    }
    
    @Override
    public void drop(ChunkPos chunkPos) {
        Validate.isTrue(Thread.currentThread() == mainThread);

        // Sable plot chunks are lifecycle-managed by their SubLevelContainer, never dropped
        // by view-range logic (mirrors Sable's own ClientChunkCacheMixin on the vanilla cache).
        if (ipl.sable.client.IplPlotChunkRouting.isPlotBound(this.level, chunkPos.x, chunkPos.z)) {
            return;
        }

//        LOGGER.info("unload {} {}", level, chunkPos);

        LevelChunk chunk = chunkMapForMainThread.get(chunkPos.toLong());
        if (chunk != null) {
            modifyChunkMap(chunkMap -> {
                chunkMap.remove(chunkPos.toLong());
            });
            
            O_O.postClientChunkUnloadEvent(chunk);
            this.level.unload(chunk);
            SodiumInterface.invoker.onClientChunkUnloaded(level, chunkPos.x, chunkPos.z);
            clientChunkUnloadSignal.emit(chunk);
        }
    }
    
    public <T> T readChunkMap(Function<Long2ObjectOpenHashMap<LevelChunk>, T> func) {
        if (Thread.currentThread() == mainThread) {
            return func.apply(chunkMapForMainThread);
        }
        else {
            synchronized (chunkMapForOtherThreads) {
                return func.apply(chunkMapForOtherThreads);
            }
        }
    }
    
    public void modifyChunkMap(Consumer<Long2ObjectOpenHashMap<LevelChunk>> func) {
        Validate.isTrue(Thread.currentThread() == mainThread);
        func.accept(chunkMapForMainThread);
        synchronized (chunkMapForOtherThreads) {
            func.accept(chunkMapForOtherThreads);
        }
    }
    
    @Override
    public LevelChunk getChunk(int x, int z, ChunkStatus chunkStatus, boolean create) {
        // Sable plot chunks live in the sub-level plots, not in IP's flat map. This is what
        // lets the section compiler / block reads on a secondary world see hosted airships.
        // For VACANT plot-grid coords we must return a non-null empty chunk, exactly like
        // Sable's hook on the vanilla cache does: the section compiler's hasAllNeighbors()
        // check treats null as "not loaded" and silently cancels every compile task,
        // freezing hosted sub-level geometry at UNCOMPILED forever.
        if (ipl.sable.client.IplPlotChunkRouting.isPlotBound(this.level, x, z)) {
            LevelChunk plotChunk = ipl.sable.client.IplPlotChunkRouting.tryGetChunk(this.level, x, z);
            return plotChunk != null ? plotChunk : ipl$getPlotEmptyChunk();
        }

        return readChunkMap(chunkMap -> {
            LevelChunk chunk = chunkMap.get(ChunkPos.asLong(x, z));
            if (chunk != null) {
                return chunk;
            }
            
            return create ? this.emptyChunk : null;
        });
    }
    
    public boolean isChunkLoaded(int x, int z) {
        return readChunkMap(chunkMap -> {
            return chunkMap.containsKey(ChunkPos.asLong(x, z));
        });
    }
    
    @Override
    public void replaceBiomes(int x, int z, FriendlyByteBuf friendlyByteBuf) {
        Validate.isTrue(Thread.currentThread() == mainThread);
        
        long chunkPosLong = ChunkPos.asLong(x, z);
        
        LevelChunk worldChunk = chunkMapForMainThread.get(chunkPosLong);
        ChunkPos chunkPos = new ChunkPos(x, z);
        if (worldChunk == null) {
            LOGGER.error("Trying to replace biomes for missing chunk {} {}", x, z);
        }
        else {
            worldChunk.replaceBiomes(friendlyByteBuf);
        }
    }
    
    @Override
    public LevelChunk replaceWithPacketData(
        int x, int z,
        FriendlyByteBuf buf, CompoundTag nbt,
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        Validate.isTrue(Thread.currentThread() == mainThread);

        // Route Sable plot-grid chunks into the owning sub-level plot. Sable's own routing is
        // a mixin on the vanilla ClientChunkCache and never applies to this override — without
        // this, hosted sub-level chunks land in IP's flat map and the plot stays empty.
        LevelChunk routed = ipl.sable.client.IplPlotChunkRouting.tryReplaceWithPacketData(
            this.level, x, z, buf, nbt, consumer);
        if (routed != null) {
            return routed;
        }

        long chunkPosLong = ChunkPos.asLong(x, z);
        LevelChunk worldChunk = chunkMapForMainThread.get(chunkPosLong);
        if (worldChunk == null) {
            worldChunk = new LevelChunk(this.level, new ChunkPos(x, z));
            loadChunkDataFromPacket(buf, nbt, worldChunk, consumer);
            
            LevelChunk worldChunkToPut = worldChunk; // lambda can only capture effectively final variables
            modifyChunkMap(chunkMap -> {
                chunkMap.put(chunkPosLong, worldChunkToPut);
            });
        }
        else {
            loadChunkDataFromPacket(buf, nbt, worldChunk, consumer);
        }
        
        this.level.onChunkLoaded(new ChunkPos(x, z));
        O_O.postClientChunkLoadEvent(worldChunk);
        SodiumInterface.invoker.onClientChunkLoaded(level, x, z);
        clientChunkLoadSignal.emit(worldChunk);
        
//        LOGGER.info("load {} {} {}", level, x, z);
        
        return worldChunk;
    }
    
    /**
     * {@link net.minecraft.core.IdMap#byIdOrThrow(int)}
     * {@link net.minecraft.world.level.chunk.LinearPalette#read(FriendlyByteBuf)}
     */
    private void loadChunkDataFromPacket(
        FriendlyByteBuf buf,
        CompoundTag nbt,
        LevelChunk worldChunk,
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        try {
            worldChunk.replaceWithPacketData(buf, nbt, consumer);
        }
        catch (Exception e) {
            LOGGER.error(
                "Error deserializing chunk packet {} {}",
                worldChunk.getLevel().dimension().location(),
                worldChunk.getPos(),
                e
            );
            CHelper.printChat(
                Component
                    .literal("Failed to deserialize chunk packet. %s %s %s".formatted(
                        worldChunk.getLevel().dimension().location(),
                        worldChunk.getPos().x, worldChunk.getPos().z
                    ))
                    .append(Component.literal(" Report issue:"))
                    .append(McHelper.getLinkText(O_O.getIssueLink()))
                    .withStyle(ChatFormatting.RED)
            );
            
            throw new RuntimeException(e);
        }
    }
    
    public List<LevelChunk> getCopiedChunkList() {
        return readChunkMap((chunkMap) -> {
            return Arrays.asList(chunkMap.values().toArray(new LevelChunk[0]));
        });
    }
    
    @Override
    public void updateViewCenter(int x, int z) {
        // do nothing
    }
    
    @Override
    public void updateViewRadius(int r) {
        // do nothing
    }
    
    @Override
    public String gatherStats() {
        return "Client Chunks (ImmPtl) " + getLoadedChunksCount();
    }
    
    @Override
    public int getLoadedChunksCount() {
        return readChunkMap(chunkMap -> {
            return chunkMap.size();
        });
    }
    
    @Override
    public void onLightUpdate(LightLayer lightType, SectionPos chunkSectionPos) {
        ClientWorldLoader.getWorldRenderer(level.dimension())
            .setSectionDirty(chunkSectionPos.x(), chunkSectionPos.y(), chunkSectionPos.z());
    }
    
}
