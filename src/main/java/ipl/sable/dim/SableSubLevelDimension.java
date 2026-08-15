package ipl.sable.dim;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Constants and accessor for the dedicated {@code ipl_sable:sublevels} dimension that hosts every
 * Sable sub-level's plot chunks after the dim-agnostic refactor.
 *
 * <p><b>Why a dedicated dimension:</b> historically, Sable embedded each sub-level's chunks at a
 * far offset inside the parent {@code ServerLevel} (see {@code EmbeddedPlotLevelAccessor} and
 * {@code SubLevelContainer.DEFAULT_ORIGIN = 10000}). That embedding made cross-portal transit
 * architecturally impossible because the plot chunks could only live in one parent dim at a time.
 *
 * <p>After the refactor, all plot chunks live in this dimension, and {@code SubLevel.parentLevel}
 * becomes derived metadata indicating which parent the airship is currently visible from. Cross-
 * portal transit reduces to flipping {@code parentLevel} and letting IP's slot-0 clip stack crop
 * the visible region on each parent side.
 *
 * <p>Registered via datapack at:
 * <ul>
 *   <li>{@code data/ipl_sable/dimension_type/sublevels.json} - void-style type, fixed time, no spawn</li>
 *   <li>{@code data/ipl_sable/dimension/sublevels.json} - flat generator with empty layers</li>
 * </ul>
 *
 * <p><b>Phase 0 contains a kill-switch test</b> ({@link #init}): logs whether the dimension
 * actually resolved at server start. NeoForge 1.21.1's frozen-registry timing has caused silent
 * dimension-load failures for custom dim types in some prior versions; this listener exposes such
 * failures loudly in the log instead of leaving us to debug NPEs in later phases.
 */
public final class SableSubLevelDimension {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-dim");

    /**
     * Resource key for the dedicated sub-level hosting dimension.
     *
     * <p>Namespace {@code ipl_sable} matches our fork's mixin config ({@code ipl_sable.mixins.json})
     * and root package ({@code ipl.sable}). Path {@code sublevels} is unique within that namespace.
     */
    public static final ResourceKey<Level> SUBLEVELS = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("ipl_sable", "sublevels")
    );

    private SableSubLevelDimension() {}

    /**
     * Register the phase-0 kill-switch listener. Call once from {@code IPModEntry.onInitialize}.
     * Idiomatic NeoForge pattern: a static {@code init} that subscribes to the game bus, matching
     * how {@code CustomPortalGenManager} and {@code IPModInfoChecking} are wired.
     */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, SableSubLevelDimension::onServerStarted);
    }

    /**
     * Phase 0 kill-switch: log whether the hosting dimension actually loaded at server start.
     *
     * <p>If this prints a WARN, every subsequent phase will fail; better to surface it here than
     * to debug an NPE inside the renderer-rewire phase three days later.
     */
    private static void onServerStarted(ServerStartedEvent event) {
        ServerLevel level = event.getServer().getLevel(SUBLEVELS);
        if (level != null) {
            LOG.info(
                "[IPL-SABLE-DIM] ipl_sable:sublevels loaded OK - dim={} chunkSource={}",
                level.dimension().location(),
                level.getChunkSource().getClass().getSimpleName()
            );
        } else {
            LOG.warn(
                "[IPL-SABLE-DIM] ipl_sable:sublevels FAILED TO LOAD - server.getLevel({}) returned null. "
                    + "Check datapack JSON files under data/ipl_sable/ and NeoForge frozen-registry timing. "
                    + "Subsequent phases of the dim-agnostic refactor will not work until this is resolved.",
                SUBLEVELS.location()
            );
        }
    }

    /**
     * Resolve the loaded {@code ServerLevel} for the sub-level hosting dimension, or throw if not
     * loaded. The dimension is datapack-registered as required and should always be present after
     * server start. A null return from {@link MinecraftServer#getLevel(ResourceKey)} indicates the
     * datapack didn't load - likely a frozen-registry timing issue, file path typo, or JSON schema
     * mismatch.
     */
    public static ServerLevel getSableSubLevels(MinecraftServer server) {
        ServerLevel level = server.getLevel(SUBLEVELS);
        return Objects.requireNonNull(
            level,
            "ipl_sable:sublevels dimension not loaded - check datapack registration in "
                + "data/ipl_sable/dimension/sublevels.json and data/ipl_sable/dimension_type/sublevels.json"
        );
    }

    /**
     * Null-tolerant variant of {@link #getSableSubLevels} for code paths where the dimension's
     * absence should be handled gracefully (e.g., during startup before the dim has loaded, or in
     * unit-test scenarios with a stripped server).
     */
    @Nullable
    public static ServerLevel getSableSubLevelsOrNull(MinecraftServer server) {
        return server.getLevel(SUBLEVELS);
    }
}
