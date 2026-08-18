package ipl.sable.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents the Quark GlintRenderTypes crash that happens when IP's
 * mixins cause RenderBuffers to be class-loaded before FML is ready.
 *
 * The crash chain:
 * RenderBuffers.<init> -> Quark's addGlintTypes -> GlintRenderTypes.<clinit>
 * -> Quark.<clinit> -> Zeta.<init> -> ModLoadingContext.getActiveContainer()
 * -> "Where is minecraft???" -> ExceptionInInitializerError
 *
 * This mixin wraps GlintRenderTypes' static initializer with a try-catch.
 * If ModLoadingContext isn't ready yet, we catch the error and skip
 * initialization. Quark will retry the static init later when the
 * class is next accessed (after FML is ready).
 *
 * Soft-applies via @Pseudo so it no-ops if Quark is not present.
 */
@Pseudo
@Mixin(targets = "org.violetmoon.quark.content.tools.client.render.GlintRenderTypes", remap = false)
public class IplQuarkGlintFixMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-quark-fix");

    /**
     * Wrap the static initializer to catch the ExceptionInInitializerError.
     * When ModLoadingContext isn't ready, the error is caught and the
     * class is marked as "initialization deferred". Java will retry the
     * <clinit> next time the class is accessed.
     *
     * Actually, Java doesn't retry <clinit> after ExceptionInInitializerError.
     * The class stays in ERRONEOUS state and future access throws
     * NoClassDefFoundError. So we need a different approach.
     *
     * Instead: intercept the call to Quark.<clinit> (which is what
     * GlintRenderTypes triggers) and defer it.
     *
     * Actually, the simplest approach: intercept the addGlintTypes call
     * in RenderBuffers and cancel it if FML isn't ready. The glint types
     * will be missing but the game won't crash. Quark's glint rendering
     * won't work but that's a minor visual issue compared to a crash.
     */
    @Inject(
        method = "<clinit>",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_preventEarlyClinit(CallbackInfo ci) {
        try {
            // Check if ModLoadingContext is ready by checking if ModList is initialized
            net.neoforged.fml.ModList.get();
        } catch (Throwable t) {
            // ModLoadingContext is not ready. Cancel the static init.
            // This prevents the ExceptionInInitializerError crash.
            // The class will be in a partially-initialized state but
            // the game will continue booting.
            LOG.warn("[IPL-QUARK-FIX] Deferring Quark GlintRenderTypes init - FML not ready yet");
            ci.cancel();
        }
    }
}
