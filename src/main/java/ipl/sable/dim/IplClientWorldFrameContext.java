package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * CLIENT mirror of {@link IplWorldFrameContext}: the parent {@code ClientLevel} hosted BE
 * code is conceptually acting in while it ticks on the hosting {@code ClientLevel}.
 *
 * <p>IP's remote-world ticking runs the hosting dimension's client BE ticks (wheel mounts
 * computing suspension visuals, rope holders registering strands, Create behaviors). Their
 * world-frame reads — an Offroad wheel's visual {@code this.level.clip(...)} ground probe,
 * {@code getSignal} neighbor checks at world coordinates — address {@code this.level}: the
 * void hosting dimension. With this context armed around hosted plot BE ticks
 * ({@code IplHostedBeTickContextMixin}), {@code IplHostedClientWorldFrameRouterMixin}
 * forwards those world-frame reads to the dimension the ship actually occupies — same
 * doctrine as the server pair, read-only surface.
 */
public final class IplClientWorldFrameContext {

    private static final ThreadLocal<ClientLevel> CURRENT = new ThreadLocal<>();

    private IplClientWorldFrameContext() {}

    /** The parent level hosted client BE code is conceptually acting in, or null. */
    @Nullable
    public static ClientLevel current() {
        return CURRENT.get();
    }

    /** Set the context, returning the previous value for restoration in a finally block. */
    @Nullable
    public static ClientLevel push(ClientLevel parent) {
        ClientLevel prev = CURRENT.get();
        CURRENT.set(parent);
        return prev;
    }

    public static void pop(@Nullable ClientLevel prev) {
        CURRENT.set(prev);
    }

    /**
     * For a block entity ticking at {@code pos} on {@code level}: if this is a plot-chunk BE
     * on the hosting client dimension, the parent {@code ClientLevel} of the owning
     * sub-level. Null otherwise.
     */
    @Nullable
    public static ClientLevel resolveParentForPlotBe(Level level, BlockPos pos) {
        if (!level.isClientSide() || !IplDimAgnostic.isHostingLevel(level)) return null;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        if (!container.inBounds(pos)) return null;
        LevelPlot plot = container.getPlot(pos.getX() >> 4, pos.getZ() >> 4);
        if (plot == null) return null;
        SubLevel subLevel = plot.getSubLevel();
        if (subLevel == null || subLevel.isRemoved()) return null;
        Level parent = ((ipl.sable.duck.IplSubLevelDuck) subLevel).ipl$getParentLevel();
        return parent instanceof ClientLevel clientParent && !IplDimAgnostic.isHostingLevel(clientParent)
            ? clientParent : null;
    }
}
