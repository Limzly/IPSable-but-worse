package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Keeps a sub-level clip alive until its deferred block-entity vertices are drawn.
 * A block-entity renderer only appends vertices to BufferSource; the shader runs at
 * endBatch(), so a per-render-call bracket ends too early to control visibility.
 */
public final class SubLevelBlockEntityRenderScope {

    private static final ThreadLocal<java.util.ArrayDeque<ClientSubLevel>> ACTIVE =
        ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private SubLevelBlockEntityRenderScope() {}

    public static boolean isActiveFor(ClientSubLevel sub) {
        return sub != null && ACTIVE.get().contains(sub);
    }

    public static void renderAndFlush(
        ClientSubLevel sub, MultiBufferSource.BufferSource buffers, Runnable render
    ) {
        SourceClipPortalFinder.ClipDecision decision =
            SourceClipPortalFinder.findStraddlingPortalPlane(sub);
        if (decision == null) {
            disableStalePortalClipOutsidePortalPass();
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
            render.run();
            return;
        }

        SubLevelClipUniformPatcher.patchForSubLevel(sub, decision.plane());
        // IP owns slot 0 while its portal bracket is live. Outside that bracket it
        // must be off: a portal pass can otherwise leave an old slot-0 equation
        // enabled and cull native destination BEs after the parent handoff.
        disableStalePortalClipOutsidePortalPass();
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
        // Second sub-level cut lives on its own clip distance (see
        // SableSourceClipMixin for why min() into one slot bent the cut).
        GL11.glEnable(GL30.GL_CLIP_DISTANCE2);
        ACTIVE.get().addLast(sub);
        try {
            render.run();
            // Flush only after Sable has finished this sub-level's complete BE pass.
            // Flushing inside each renderer corrupts renderers that build shared buffers.
            buffers.endBatch();
        } finally {
            ACTIVE.get().removeLastOccurrence(sub);
            if (ACTIVE.get().isEmpty()) ACTIVE.remove();
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
            GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
            SubLevelClipUniformPatcher.clearAndUpload();
        }
    }

    private static void disableStalePortalClipOutsidePortalPass() {
        if (!FrontClipping.isClippingEnabled && !PortalRendering.isRendering()) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
        }
    }
}
