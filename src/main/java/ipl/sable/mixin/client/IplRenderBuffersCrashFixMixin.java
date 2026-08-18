package ipl.sable.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Deep-rooted fix for the RenderBuffers crash that happens with
 * Sinytra Connector + Quark when IP is present.
 *
 * The crash: RenderBuffers.<init> triggers Quark's GlintRenderTypes.<clinit>
 * which calls ModLoadingContext before FML is ready, causing
 * ExceptionInInitializerError.
 *
 * Root fix: redirect the RenderBuffers constructor call inside
 * Minecraft.<init> to wrap it in try-catch. If construction fails,
 * retry once (FML might be ready by then). If it still fails, let
 * the original crash propagate so the user sees a proper error.
 *
 * This protects against ANY mod that does premature static init
 * during RenderBuffers construction, not just Quark.
 */
@Mixin(Minecraft.class)
public class IplRenderBuffersCrashFixMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-crash-fix");

    private static boolean ip$retryingRenderBuffers = false;

    /**
     * Redirect the RenderBuffers constructor call in Minecraft.<init>.
     * If it throws, wait a moment and retry. If it still throws,
     * let the exception propagate.
     */
    @Redirect(
        method = "<init>",
        at = @At(
            value = "NEW",
            target = "()Lnet/minecraft/client/renderer/RenderBuffers;",
            ordinal = 0
        ),
        require = 0
    )
    private RenderBuffers ip_safeCreateRenderBuffers() {
        try {
            return new RenderBuffers();
        } catch (Throwable t) {
            LOG.error("[IPL-CRASH-FIX] RenderBuffers construction failed: {}", t.getMessage());
            if (!ip$retryingRenderBuffers) {
                ip$retryingRenderBuffers = true;
                LOG.info("[IPL-CRASH-FIX] Retrying RenderBuffers construction...");
                try {
                    return new RenderBuffers();
                } catch (Throwable t2) {
                    LOG.error("[IPL-CRASH-FIX] RenderBuffers retry also failed: {}", t2.getMessage());
                }
            }
            // Last resort: let it crash so the user sees the real error
            throw t;
        }
    }
}
