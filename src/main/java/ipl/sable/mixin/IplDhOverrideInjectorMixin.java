package ipl.sable.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Suppresses Distant Horizons' {@code OverrideInjector.bind} exception that fires
 * when Iris's {@code LodRendererEvents$12.beforeSetup} tries to re-register its
 * DH render-setup override on every portal render re-entry.
 *
 * <p><b>Root cause:</b> When Immersive Portals renders a portal's destination
 * dimension, it re-enters {@code LevelRenderer.renderLevel}. DH fires its
 * {@code DhApiBeforeRenderSetupEvent} on every render. Iris registers a handler
 * for this event that calls {@code OverrideInjector.bind(priority=10)}. On the
 * first render this succeeds; on subsequent portal re-entries, DH's API rejects
 * the duplicate priority with {@code IllegalStateException: An override already
 * exists with the priority [10]}. This produces thousands of error log lines
 * per session and can cause DH to render LODs from the wrong dimension visible
 * through walls.
 *
 * <p><b>Fix:</b> When IP is actively rendering a portal (i.e.
 * {@link PortalRendering#isRendering()} returns true), skip the bind call
 * entirely by cancelling the method. The override is already registered from
 * the first (non-portal) render; the re-registration is redundant.
 *
 * <p><b>Soft-apply:</b> Uses {@code @Pseudo} so the mixin no-ops if DH is not
 * on the classpath. {@code require = 0} on the injector ensures the mixin won't
 * crash if the {@code bind} method signature differs across DH versions.
 *
 * <p>Registered in {@code ipl_sable.mixins.json} which has
 * {@code "defaultRequire": 0} at the injector level.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector", remap = false)
public class IplDhOverrideInjectorMixin {
    
    private static final Logger LOG = LoggerFactory.getLogger("ipl-dh-compat");
    
    /**
     * Intercept all overloads of {@code bind} and cancel the call when IP is
     * rendering a portal. The override is already registered from the first
     * (non-portal) render, so skipping the re-registration is safe.
     *
     * <p>The wildcard {@code method = "bind"} matches all overloads. If none
     * match (DH changed its API), the mixin silently doesn't apply thanks to
     * {@code require = 0}.
     */
    @Inject(
        method = "bind",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_skipDuplicateBindDuringPortalRender(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            LOG.debug(
                "Skipping DH OverrideInjector.bind during portal rendering "
                    + "(Iris LodRendererEvents handler would throw duplicate-priority exception)"
            );
            ci.cancel();
        }
    }
}
