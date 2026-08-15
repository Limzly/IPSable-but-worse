package ipl.sable.render;

import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Cache of the most recent non-null clip-plane equations IP computed.
 *
 * <p><b>Why this is needed:</b> when a shader pack is loaded, Iris runs its
 * own gbuffer / deferred passes outside of IP's
 * {@code setupInnerClipping → render → disableClipping} bracket. By the time
 * Iris binds the entity shader for a leaky chest, IP has already called
 * {@code disableClipping()} -- {@code FrontClipping.isClippingEnabled} is
 * {@code false} and the equation getters return {@code null}, so IP's
 * {@code MixinRenderSystem_Clipping} writes the {@code (0,0,0,1)} no-clip
 * sentinel to the chest's program. The chest then renders unclipped.
 *
 * <p>This cache holds the last <i>real</i> equation we observed so the hook
 * can keep using it across the gap between {@code disableClipping()} and the
 * actual end of portal-related rendering ({@code PortalRendering.isRendering()}
 * going back to {@code false}). Stale across frame boundaries doesn't matter
 * for correctness because the cache is only consulted while
 * {@code PortalRendering.isRendering()} is true -- a stale value used during
 * normal main-scene rendering can't happen, because in that case
 * {@code PortalRendering.isRendering()} is false and the no-clip sentinel
 * path runs instead.
 *
 * <p>Read by {@link ipl.sable.mixin.client.IplGlUseProgramProbeMixin} (so
 * Iris-bound programs still get the right equation) and by IP's
 * {@code MixinRenderSystem_Clipping} (so IP doesn't unset the uniform during
 * Iris's pass). Refreshed lazily -- any caller observing live, non-null
 * equations on {@link FrontClipping} should call {@link #refreshFromActive}
 * before using the cache.
 */
public final class IplClipEquationCache {
    private IplClipEquationCache() {}

    private static volatile double[] lastWorldEq = null;
    private static volatile double[] lastEyeEq = null;

    public static void refreshFromActive() {
        double[] w = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
        double[] e = FrontClipping.getActiveClipPlaneEquationAfterModelView();
        if (w != null) lastWorldEq = w;
        if (e != null) lastEyeEq = e;
    }

    public static double[] getWorldEq() {
        return lastWorldEq;
    }

    public static double[] getEyeEq() {
        return lastEyeEq;
    }
}
