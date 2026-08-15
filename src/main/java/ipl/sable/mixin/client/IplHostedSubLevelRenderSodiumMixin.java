package ipl.sable.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import foundry.veil.api.client.render.VeilRenderBridge;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import ipl.sable.client.IplClientHostedLookup;
import ipl.sable.client.IplProjectionRenderSequence;
import ipl.sable.client.IplStraddleRenderState;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;

import java.util.List;
import java.util.Objects;

/**
 * Sodium-backend parity for {@link IplHostedSubLevelRenderMixin}. With Sodium loaded,
 * {@code LevelRenderer}'s vanilla terrain path (setupRender / compileSections /
 * renderSectionLayer) is dormant and Sable drives sub-level rendering from
 * {@code SodiumWorldRenderer} instead ({@code sublevel_render/impl/sodium}). Sable's
 * hooks gather from the camera level's own container, so hosted sub-levels — which
 * live in the {@code ipl_sable:sublevels} container — are never gathered. This mixin
 * mirrors each of Sable's Sodium hook points, feeding the hosted + straddle-projection
 * lists, exactly as the vanilla-backend mixin mirrors Sable's vanilla hooks.
 *
 * <p>Geometry still compiles through the vanilla section pipeline: Sable's
 * reach-around dispatcher builds its own {@code SectionRenderDispatcher} and rebinds it
 * to each sub-level's plot level via {@code setLevel()} per createRenderData, so hosted
 * sections read block/light data from the sublevels {@code ClientLevel} with no extra
 * routing (the {@code IplHostedRenderDataMixin} reroute is a vanilla-backend concern).
 * Block-update dirtiness flows through Sable's own {@code scheduleRebuildForChunk} hook
 * on the sublevels dimension's renderer. The vanilla mixin's {@code allChanged} /
 * {@code renderLevel} hooks (rebuild, block entities, after-sections) still fire under
 * Sodium and are not duplicated here.
 *
 * <p>Priority 1010: applies after Sable's own render mixins (1002).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", priority = 1010, remap = false)
public abstract class IplHostedSubLevelRenderSodiumMixin {

    @Shadow
    private ClientLevel level;

    /**
     * The dimension THIS pass is rendering. The shadowed {@code level} field belongs to the
     * singleton SodiumWorldRenderer and tracks the player's dimension for the whole frame;
     * IP swaps {@code Minecraft.level} (and the per-dimension LevelRenderer) around each
     * portal-content render but never touches Sodium's world renderer. Querying with the
     * stale field fed the camera dimension's hosted sub-levels into every portal pass —
     * a source-dim ship near a portal showed up inside the portal view.
     */
    @Unique
    private ClientLevel ipl$passLevel() {
        ClientLevel passLevel = Minecraft.getInstance().level;
        return passLevel != null ? passLevel : this.level;
    }

    @Unique
    private List<ClientSubLevel> ipl$hosted() {
        return IplClientHostedLookup.getHostedSubLevelsFor(ipl$passLevel());
    }

    @Unique
    private List<IplClientHostedLookup.StraddleProjection> ipl$projections() {
        return IplClientHostedLookup.getStraddleProjectionsInto(ipl$passLevel());
    }

    // mirrors the vanilla mixin's ipl$cullHosted (Sable: sable$markGraphDirty)
    @Inject(
        method = "setupTerrain",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;markGraphDirty()V"
        )
    )
    private void ipl$cullHosted(
        Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately,
        CallbackInfo ci
    ) {
        List<ClientSubLevel> hosted = ipl$hosted();
        List<IplClientHostedLookup.StraddleProjection> projections = ipl$projections();
        if (hosted.isEmpty() && projections.isEmpty()) return;

        // The pass's LevelRenderer: IP swaps Minecraft.levelRenderer per portal pass, so
        // this is the frustum of whichever dimension pass this SodiumWorldRenderer serves.
        Frustum frustum = ((IEWorldRenderer) Minecraft.getInstance().levelRenderer).portal_getFrustum();
        if (frustum == null) return;

        Vec3 cameraPosition = camera.getPosition();
        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
        if (!hosted.isEmpty()) {
            dispatcher.updateCulling(
                hosted, cameraPosition.x, cameraPosition.y, cameraPosition.z,
                VeilRenderBridge.create(frustum), spectator
            );
        }

        // Projection geometry uses the source sub-level's render data, but its mapped
        // pose belongs in this destination pass. Recompute its section visibility while
        // that pose override is active; source-pass culling is not valid here.
        for (IplClientHostedLookup.StraddleProjection projection : projections) {
            IplStraddleRenderState.set(
                projection.sub(), projection.mappedPose(), projection.destPlane(), projection.portal());
            try {
                dispatcher.updateCulling(
                    List.of(projection.sub()), cameraPosition.x, cameraPosition.y, cameraPosition.z,
                    VeilRenderBridge.create(frustum), spectator
                );
            } finally {
                IplStraddleRenderState.clear();
            }
        }
    }

    // mirrors the vanilla mixin's ipl$compileHostedSections (Sable: sable$setupTerrain)
    @Inject(method = "setupTerrain", at = @At("TAIL"))
    private void ipl$compileHostedSections(
        Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately,
        CallbackInfo ci
    ) {
        List<ClientSubLevel> hosted = ipl$hosted();
        List<IplClientHostedLookup.StraddleProjection> projections = ipl$projections();
        if (hosted.isEmpty() && projections.isEmpty()) return;

        RenderRegionCache renderRegionCache = new RenderRegionCache();
        PrioritizeChunkUpdates chunkUpdates =
            SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType()
                == TaskQueueType.ALWAYS_DEFER
                ? PrioritizeChunkUpdates.NONE
                : PrioritizeChunkUpdates.NEARBY;
        for (ClientSubLevel sublevel : hosted) {
            sublevel.getRenderData().compileSections(chunkUpdates, renderRegionCache, camera);
        }
        // Projections share the native render data; compiling here covers the case where
        // the parent dim's own pass isn't rendered this frame (e.g. player on the dest side
        // with no portal view back).
        for (IplClientHostedLookup.StraddleProjection projection : projections) {
            projection.sub().getRenderData().compileSections(chunkUpdates, renderRegionCache, camera);
        }
    }

    // mirrors the vanilla mixin's ipl$renderHostedSectionLayer + ipl$renderHostedVeilLayers
    // (Sable: sable$drawRenderSources)
    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void ipl$renderHostedSectionLayer(
        RenderType renderType, ChunkRenderMatrices matrices, double x, double y, double z,
        CallbackInfo ci
    ) {
        List<ClientSubLevel> hosted = ipl$hosted();
        List<IplClientHostedLookup.StraddleProjection> projections = ipl$projections();
        if (hosted.isEmpty() && projections.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        Matrix4f modelView = new Matrix4f(matrices.modelView());
        Matrix4f projection = new Matrix4f(matrices.projection());
        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();

        // Sub-level geometry draws through the vanilla ShaderInstance pipeline even under
        // Sodium (reach-around dispatcher), so set up vanilla render state around it.
        renderType.setupRenderState();
        ShaderInstance shader = null;
        try {
            shader = Objects.requireNonNull(RenderSystem.getShader(), "shader");
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
            shader.apply();

            if (!hosted.isEmpty()) {
                ipl$updateCulling(hosted);
                dispatcher.renderSectionLayer(
                    hosted, renderType, shader, x, y, z, modelView, projection, partialTick);
            }
            for (IplClientHostedLookup.StraddleProjection proj : projections) {
                IplStraddleRenderState.set(
                    proj.sub(), proj.mappedPose(), proj.destPlane(), proj.portal());
                try {
                    ipl$updateCulling(List.of(proj.sub()));
                    dispatcher.renderSectionLayer(
                        List.of(proj.sub()), renderType, shader, x, y, z, modelView, projection, partialTick);
                } finally {
                    IplStraddleRenderState.clear();
                }
            }
        } finally {
            if (shader != null) shader.clear();
            renderType.clearRenderState();
        }

        RenderType unwrapped = renderType;
        while (unwrapped instanceof VeilRenderType.RenderTypeWrapper wrapper) {
            unwrapped = wrapper.get();
        }
        if (!(unwrapped instanceof VeilRenderType.LayeredRenderType layered)
            || hosted.isEmpty() && projections.isEmpty()) return;

        for (RenderType layer : layered.getLayers()) {
            layer.setupRenderState();
            ShaderInstance layerShader = null;
            try {
                layerShader = Objects.requireNonNull(RenderSystem.getShader(), "shader");
                layerShader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
                layerShader.apply();

                if (!hosted.isEmpty()) {
                    ipl$updateCulling(hosted);
                    dispatcher.renderSectionLayer(
                        hosted, layer, layerShader, x, y, z, modelView, projection, partialTick);
                }
                for (IplClientHostedLookup.StraddleProjection proj : projections) {
                    IplStraddleRenderState.set(
                        proj.sub(), proj.mappedPose(), proj.destPlane(), proj.portal());
                    try {
                        ipl$updateCulling(List.of(proj.sub()));
                        dispatcher.renderSectionLayer(
                            List.of(proj.sub()), layer, layerShader, x, y, z, modelView, projection, partialTick);
                    } finally {
                        IplStraddleRenderState.clear();
                    }
                }
            } finally {
                if (layerShader != null) layerShader.clear();
                layer.clearRenderState();
            }
        }
    }

    /**
     * Sable queues one-block sub-level layers during {@code renderSectionLayer} and drains
     * that queue only from {@code renderAfterSections}. Sodium drives its own terrain
     * lifecycle, so the drain has to follow the matching Sodium chunk layer.
     *
     * <p>The drain is ONE call over a sequence of hosted sub-levels followed by the straddle
     * projections. The queue is shared and is cleared by the first drain, so the previous
     * per-projection calls always ran against an empty queue: single-block sub-levels (rope
     * ends, one-block contraptions) were simply absent from every destination projection.
     * {@link IplProjectionRenderSequence} arms each projection's mapped pose exactly while
     * its geometry is baked.
     */
    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void ipl$renderHostedSingleBlocks(
        RenderType renderType, ChunkRenderMatrices matrices, double x, double y, double z,
        CallbackInfo ci
    ) {
        List<ClientSubLevel> hosted = ipl$hosted();
        List<IplClientHostedLookup.StraddleProjection> projections = ipl$projections();
        if (hosted.isEmpty() && projections.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        Matrix4f modelView = new Matrix4f(matrices.modelView());
        Matrix4f projection = new Matrix4f(matrices.projection());

        // x/y/z are THIS pass's camera origin. The main camera belongs to the player and is
        // the wrong origin inside an IP portal pass, which shifted every single-block draw
        // seen through a portal.
        IplProjectionRenderSequence sequence =
            new IplProjectionRenderSequence(hosted, projections);
        try {
            SubLevelRenderDispatcher.get().renderAfterSections(
                sequence, x, y, z, modelView, projection, partialTick);
        } finally {
            sequence.disarm();
        }
    }

    @Unique
    private void ipl$updateCulling(List<ClientSubLevel> sublevels) {
        Frustum frustum = ((IEWorldRenderer) Minecraft.getInstance().levelRenderer).portal_getFrustum();
        if (frustum == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
        boolean spectator = minecraft.player != null && minecraft.player.isSpectator();
        SubLevelRenderDispatcher.get().updateCulling(
            sublevels, cameraPosition.x, cameraPosition.y, cameraPosition.z,
            VeilRenderBridge.create(frustum), spectator
        );
    }

}
