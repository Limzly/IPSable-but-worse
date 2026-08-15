package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.platform.SableChunkEventPlatform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Sable plot-chunk routing for IP's secondary-world client chunk storage.
 *
 * <p>Sable routes plot-grid chunks (global chunk coords around 1.28M) into the per-sub-level
 * {@code LevelPlot} via a mixin on the VANILLA {@code ClientChunkCache}. IP's secondary
 * client worlds use {@code ImmPtlClientChunkMap}, a subclass that OVERRIDES
 * {@code replaceWithPacketData}/{@code getChunk}/{@code drop} — so Sable's routing never
 * runs there, and a hosted sub-level's chunks would land in IP's flat chunk map while its
 * plot stayed empty (invisible ship, dim-agnostic bring-up bug A). {@code ImmPtlClientChunkMap}
 * calls into this helper to restore the routing, mirroring Sable's
 * {@code ClientChunkCacheMixin} logic.
 *
 * <p>Kept in a separate class so the IP core class only references Sable types behind the
 * dim-agnostic gate.
 */
public final class IplPlotChunkRouting {

    private IplPlotChunkRouting() {}

    /** Is this chunk coordinate inside the level's Sable plot grid? */
    public static boolean isPlotBound(ClientLevel level, int x, int z) {
        SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        return container != null && container.inBounds(x, z);
    }

    /**
     * Route a plot-grid chunk packet into the owning plot. Returns the stored chunk, or null
     * if the coords are not plot-bound (caller falls through to its own storage).
     */
    @Nullable
    public static LevelChunk tryReplaceWithPacketData(
        ClientLevel level, int x, int z,
        FriendlyByteBuf buf, CompoundTag nbt,
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer
    ) {
        SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        if (container == null || !container.inBounds(x, z)) {
            return null;
        }

        ChunkPos chunkPos = new ChunkPos(x, z);
        LevelChunk chunk = container.getChunk(chunkPos);

        boolean valid = chunk != null && chunk.getPos().x == x && chunk.getPos().z == z;
        if (!valid) {
            if (chunk != null) {
                SableChunkEventPlatform.INSTANCE.onOldChunkInvalid(chunk);
                level.unload(chunk);
            }
            chunk = new LevelChunk(level, chunkPos);
            chunk.replaceWithPacketData(buf, nbt, consumer);
            container.newPopulatedChunk(chunkPos, chunk);
        } else {
            chunk.replaceWithPacketData(buf, nbt, consumer);
        }

        level.onChunkLoaded(chunkPos);
        level.getLightEngine().setLightEnabled(chunkPos, true);
        SableChunkEventPlatform.INSTANCE.onClientChunkPacketReplaced(chunk);
        return chunk;
    }

    /** The plot chunk at plot-grid coords, or null if not plot-bound / not present. */
    @Nullable
    public static LevelChunk tryGetChunk(ClientLevel level, int x, int z) {
        SubLevelContainer container = SubLevelContainer.getContainer((Level) level);
        if (container == null || !container.inBounds(x, z)) {
            return null;
        }
        return container.getChunk(new ChunkPos(x, z));
    }

    /**
     * Route a section-dirty mark at plot-grid coords to the owning sub-level's render data
     * (mirrors Sable's {@code ViewAreaMixin}, which targets the vanilla ViewArea and never
     * applies to IP's {@code ImmPtlViewArea} override). Returns true when the coords are
     * plot-bound — the caller must then skip its own handling so IP never allocates phantom
     * render columns at plot coordinates.
     */
    public static boolean trySetSectionDirty(Level level, int x, int y, int z, boolean playerChanged) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(x, z)) {
            return false;
        }
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container.getPlot(x, z); // plot-bridged
        if (plot != null
            && plot.getSubLevel() instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientSub
            && clientSub.getRenderData() != null) {
            clientSub.getRenderData().setDirty(x, y, z, playerChanged);
        }
        return true;
    }
}
