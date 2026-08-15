package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.render.SourceClipPortalFinder;
import ipl.sable.render.SubLevelBlockEntityRenderScope;
import ipl.sable.render.SubLevelClipUniformPatcher;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Bracket each per-BE render call inside {@link VanillaSubLevelBlockEntityRenderer#renderSingleBE}
 * with a sub-level clip patch, so Create cogs and other animated block
 * entities rendered through this path get the right
 * {@code ipl_subLevelClipEquation} and {@code GL_CLIP_DISTANCE1} enable.
 *
 * <p><b>Why this exists (separate from {@link SableSubLevelBlockEntityClipMixin}):</b>
 * Sable has <em>two</em> sub-level BE render paths and they go through
 * different call sites:
 *
 * <ol>
 *   <li>Sable's {@code LevelRendererMixin.sable$preRenderBEs} calls
 *       {@code dispatcher.renderBlockEntities(sublevels, ...)} which iterates
 *       chunk sections and dispatches collections of BEs. This is what
 *       {@link SableSubLevelBlockEntityClipMixin} wraps via
 *       {@code VanillaSubLevelRenderDispatcher.renderBlockEntities}'s inner
 *       calls. Used for the chunk-section-list BE batch.</li>
 *   <li>Sable's {@code LevelRendererMixin.sable$renderBlockEntities} wraps
 *       Mojang's vanilla {@code LevelRenderer.renderLevel} call to
 *       {@code BlockEntityRenderDispatcher.render(blockEntity, ...)} and,
 *       when the BE belongs to a sub-level, calls
 *       {@link VanillaSubLevelBlockEntityRenderer#renderSingleBE} directly
 *       BYPASSING the dispatcher's renderBlockEntities path. This is the
 *       path Create's {@code KineticBlockEntityRenderer} (cog rendering)
 *       lands in -- confirmed via RenderDoc cog_leak5.rdc EID 2294: the cog
 *       drew with {@code ipl_subLevelClipEquation = (0,0,0,1)} and
 *       {@code CLIP_DISTANCE1} disabled, because the dispatcher-wrap
 *       bracket never fired for it.</li>
 * </ol>
 *
 * <p>By mixing into {@code VanillaSubLevelBlockEntityRenderer.renderSingleBE}
 * directly, we catch both paths: the dispatcher path's per-BE call inside
 * its default-method loop AND the per-BE mixin path's direct call. The
 * brackets nest harmlessly (idempotent patch + idempotent enable + idempotent
 * clear) when both apply to the same BE render.
 *
 * <p>{@code @Pseudo} because the target class isn't on the compile classpath
 * at mixin-validation time -- it's a Sable mixinhelper.
 */
@Pseudo
@Mixin(value = VanillaSubLevelBlockEntityRenderer.class, remap = false)
public abstract class SableVanillaSubLevelBERMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-be-bracket");

    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean IPL$LOGGED_FIRED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @Unique
    private static final java.util.concurrent.atomic.AtomicLong IPL$LAST_LOG_NS =
        new java.util.concurrent.atomic.AtomicLong(0L);

    /** Per-class "have we logged the post-draw program for this BE class yet" set. */
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> IPL$POST_DRAW_LOGGED =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Unique
    private static void ipl$logPostDraw(String beClass, int progBefore, int progAfter) {
        // Log once per (class, progAfter) pair so we see every distinct
        // program any BE class binds. Capped to keep logs manageable.
        Integer prev = IPL$POST_DRAW_LOGGED.get(beClass);
        if (prev != null && prev == progAfter) return;
        if (IPL$POST_DRAW_LOGGED.size() > 30) return;
        IPL$POST_DRAW_LOGGED.put(beClass, progAfter);
        IPL$LOG.info("[IPL-BE-POST-DRAW] class={} progBefore={} progAfter={}",
            beClass, progBefore, progAfter);
    }

    /**
     * Wrap the inner {@code BlockEntityRenderDispatcher.render(...)} call so
     * we can bracket exactly the BE's render call with patch/clear. Going
     * through @WrapOperation instead of HEAD/RETURN inject pairs guarantees
     * the cleanup runs even if the inner render throws.
     */
    @WrapOperation(
        method = "renderSingleBE",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
        ),
        remap = false,
        require = 0
    )
    private void ipl$wrapBERender(
        BlockEntityRenderDispatcher dispatcher,
        BlockEntity be,
        float partialTick,
        PoseStack pose,
        MultiBufferSource source,
        Operation<Void> original
    ) {
        // DIAGNOSTIC: log on first fire + rate-limited thereafter, to confirm
        // this wrap fires for cog scenes. cog_leak8 EID 2265 showed the cog
        // drawing outside any bracket -- this log will confirm whether
        // SableVanillaSubLevelBlockEntityRenderer.renderSingleBE is even on
        // the cog's call path.
        if (IPL$LOGGED_FIRED.compareAndSet(false, true)) {
            IPL$LOG.info("[IPL-BE-BRACKET-FIRED] FIRST-FIRE be={} class={}",
                be.getBlockPos(), be.getClass().getSimpleName());
        } else {
            long now = System.nanoTime();
            long last = IPL$LAST_LOG_NS.get();
            if (now - last > 5_000_000_000L) {
                IPL$LAST_LOG_NS.set(now);
                IPL$LOG.info("[IPL-BE-BRACKET-FIRED] (rate-limited) be={} class={}",
                    be.getBlockPos(), be.getClass().getSimpleName());
            }
        }

        ClientSubLevel sub = Sable.HELPER.getContainingClient(be);
        if (sub == null) {
            // Shouldn't happen in practice -- this method's callers only invoke
            // it for sub-level BEs -- but be defensive.
            original.call(dispatcher, be, partialTick, pose, source);
            return;
        }

        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(sub);
        if (decision == null) {
            // Sub-level not straddling a portal -- no clip needed.
            original.call(dispatcher, be, partialTick, pose, source);
            return;
        }

        // Block entities use independent renderers, so they cannot inherit the
        // section shader's clip distance. Apply the same plane before dispatch:
        // a BE whose block center is in the culled half never emits vertices.
        Vec3 blockCenter = Vec3.atCenterOf(be.getBlockPos());
        Vec3 visibleCenter = sub.renderPose().transformPosition(blockCenter);
        Vec3 planePoint = decision.plane().pos();
        Vec3 planeNormal = decision.plane().normal();
        double signedDistance = (visibleCenter.x - planePoint.x) * planeNormal.x
            + (visibleCenter.y - planePoint.y) * planeNormal.y
            + (visibleCenter.z - planePoint.z) * planeNormal.z;
        if (signedDistance <= 0.0) {
            return;
        }

        // The hosted renderer owns one wider scope for a whole sub-level and keeps it
        // active through BufferSource.endBatch(), when deferred BE vertices actually draw.
        if (SubLevelBlockEntityRenderScope.isActiveFor(sub)) {
            original.call(dispatcher, be, partialTick, pose, source);
            return;
        }

        // NOTE (2026-06-01): the per-BE buffer-drain hack was REMOVED here.
        //
        // It previously called source.endBatch() before AND after the BE render,
        // to scope the slot-1 clip to exactly this BE's vertices under deferred
        // MultiBufferSource rendering. That mid-loop flush is fundamentally
        // incompatible with renderers that own a buffer's lifecycle ACROSS the
        // render call -- notably catnip's ShadeSeparatingSuperByteBuffer
        // (Create/offroad wheel mounts). Draining mid-build corrupts catnip's
        // shared shade-separation buffer; its next beginVertex throws
        // "Not building!" and crashes the render thread. Crucially, the corruption
        // outlives this BE: catching the throw does NOT repair the buffer, so the
        // crash just resurfaces on the next catnip consumer a few frames later
        // (crash-2026-06-01_11.31.37 then _11.46.31, different call sites, same
        // root). Catch-and-continue was the wrong tool; the only reliable fix is
        // to not corrupt the buffer in the first place -- i.e. don't drain.
        //
        // Cost of removal: the slot-1 clip for sub-level BEs is no longer perfectly
        // scoped to a single BE within the deferred batch (the cog-clip feature was
        // already incomplete -- cogs don't fully clip -- so this regresses nothing
        // that worked). The clip uniform + CD1 are still set around the BE render
        // below, which is the actual clip mechanism; only the fragile flush is gone.
        // The whole cog-clip approach is slated for a proper rethink next session.

        SubLevelClipUniformPatcher.patchForSubLevel(sub, decision.plane());
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
        // Second sub-level cut owns gl_ClipDistance[2] (see SableSourceClipMixin).
        GL11.glEnable(GL30.GL_CLIP_DISTANCE2);

        int progBefore = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            original.call(dispatcher, be, partialTick, pose, source);
            int progAfter = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            ipl$logPostDraw(be.getClass().getSimpleName(), progBefore, progAfter);
        } finally {
            // Always disable CD1 on bracket exit (see SableSourceClipMixin
            // for the same fix + rationale).
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
            SubLevelClipUniformPatcher.clearAndUpload();
        }
    }

}
