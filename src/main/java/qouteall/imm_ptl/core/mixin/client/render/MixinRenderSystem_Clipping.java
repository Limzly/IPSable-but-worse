package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import ipl.sable.render.IplClipEquationCache;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.EntityShaderNames;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.function.Supplier;

@Mixin(value = RenderSystem.class, remap = false)
public class MixinRenderSystem_Clipping {

    // Helper kept in a separate class on purpose: declaring this Set.of(...) as
    // a `private static final` field directly in the mixin causes the mixin
    // processor to merge the field initializer into the target class's
    // <clinit>. The string-constant remap doesn't survive that merge cleanly,
    // and the merged Set.of(...) ends up being called with null entries inside
    // RenderSystem's static init -- NPE inside ImmutableCollections$SetN.probe,
    // killing client boot before any code ever runs. See EntityShaderNames.
    private static boolean ip$isEntityStyleShader() {
        ShaderInstance s = RenderSystem.getShader();
        return s != null && EntityShaderNames.IS_ENTITY_STYLE.contains(s.getName());
    }

    /**
     * Sable-fork helper: write the cached real equation onto the currently
     * bound shader and re-assert GL_CLIP_PLANE0. Used during Iris's gbuffer
     * passes when IP's {@code FrontClipping.isClippingEnabled} has already
     * been flipped off but {@code PortalRendering.isRendering()} is still
     * true (we're still inside the through-portal render).
     */
    private static void ip$writeCachedEquation(boolean entityStyle) {
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;
        Uniform u = ((IEShader) shader).ip_getClippingEquationUniform();
        if (u == null) return;
        double[] eq = entityStyle
            ? IplClipEquationCache.getEyeEq()
            : IplClipEquationCache.getWorldEq();
        if (eq == null) return;
        u.set((float) eq[0], (float) eq[1], (float) eq[2], (float) eq[3]);
        // GL_CLIP_PLANE0 == GL_CLIP_DISTANCE0 (both 0x3000). IP's disableClipping
        // already turned this off but we want it back on for this draw.
        GL11.glEnable(GL11.GL_CLIP_PLANE0);
    }

    @Inject(
        method = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V",
        at = @At("RETURN")
    )
    private static void onSetShader(Supplier<ShaderInstance> supplier, CallbackInfo ci) {
        if (IPGlobal.enableClippingMechanism) {
            if (!IrisInterface.invoker.isIrisPresent()) {
                if (CrossPortalEntityRenderer.isRenderingEntityNormally ||
                    CrossPortalEntityRenderer.isRenderingEntityProjection
                ) {
                    FrontClipping.updateClippingEquationUniformForCurrentShader(true);
                }
                else if (RenderStates.isRenderingPortalWeather) {
                    FrontClipping.updateClippingEquationUniformForCurrentShader(false);
                }
                else if (FrontClipping.isClippingEnabled) {
                    // Keep our cache fresh whenever IP says clipping is on,
                    // so when Iris's pass binds shaders post-bracket we have
                    // a recent equation ready.
                    IplClipEquationCache.refreshFromActive();
                    // BUG FIX (sable fork): during a normal portal-through render,
                    // the original code hit the `else` below and called
                    // unsetClippingUniform() on every shader bind. That zeroes
                    // iportal_ClippingEquation on the just-bound shader. Terrain
                    // works because MixinLevelRenderer_Optional re-sets the uniform
                    // before each renderSectionLayer apply(), but block-entity and
                    // entity passes bind their shaders via this setShader hook and
                    // then draw without a full apply in between -- so the uniform
                    // stays zeroed and BEs render unclipped through the portal.
                    //
                    // Sable-fork extension: also pass isRenderingEntities=true when
                    // the bound shader is an entity-style shader, so IP uploads the
                    // EYE-space clip equation (matching the entity GLSL injection
                    // that does `dot((ModelViewMat * vec4(Position, 1)).xyz, eq)`).
                    // The world-space equation only makes sense for terrain shaders
                    // whose GLSL injection uses `Position + ChunkOffset`.
                    FrontClipping.updateClippingEquationUniformForCurrentShader(
                        ip$isEntityStyleShader()
                    );
                }
                else if (PortalRendering.isRendering()) {
                    // Sable-fork extension: PortalRendering.isRendering() stays true
                    // throughout the entire through-portal render (any portal layer
                    // != 0), which spans Iris's gbuffer passes that happen AFTER IP's
                    // setupInnerClipping bracket has already called disableClipping().
                    // RenderDoc confirmed: when a shader pack is loaded, the chest's
                    // entity shader binds during this post-bracket Iris pass, IP
                    // would write the (0,0,0,1) no-clip sentinel, and the chest
                    // renders unclipped through the portal. Use the cached real
                    // equation from when isClippingEnabled was last true.
                    ip$writeCachedEquation(ip$isEntityStyleShader());
                }
                else {
                    FrontClipping.unsetClippingUniform();
                }
            }
            else {
                // Iris path: same fix. Under Iris IP previously unconditionally
                // unset; this assumed shaderpack-side handling would cover
                // everything but in practice it only covers terrain. For
                // entities / block entities under Iris we'd still want IP's
                // slot 0 active. The Iris-rewritten shader names (entities_*)
                // still need to be in IP's affectedShaders list to declare the
                // uniform in their GLSL, but that's already handled via our
                // YAML extension.
                if (FrontClipping.isClippingEnabled) {
                    IplClipEquationCache.refreshFromActive();
                    FrontClipping.updateClippingEquationUniformForCurrentShader(
                        ip$isEntityStyleShader()
                    );
                } else if (PortalRendering.isRendering()) {
                    // Same Iris-pass-after-bracket case as above.
                    ip$writeCachedEquation(ip$isEntityStyleShader());
                } else {
                    FrontClipping.unsetClippingUniform();
                }
            }
        }

    }
}
