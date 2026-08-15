package ipl.sable.mixin.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import ipl.sable.duck.IplSubLevelClipShader;
import net.minecraft.client.renderer.ShaderInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.FrontClipping;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Mirror IP's currently-installed clip equation onto our independent
 * {@code ipl_subLevelClipEquation} uniform (gl_ClipDistance[1]) on every
 * {@link RenderSystem#setShader} so that ALL geometry rendered through
 * affected shaders gets clipped during portal-through render -- not just
 * Sable sub-level chunks.
 *
 * <p><b>Why this exists:</b> IP's {@code MixinRenderSystem_Clipping}
 * unconditionally calls {@code FrontClipping.unsetClippingUniform()} on
 * every {@code setShader} call when Iris is present, presumably assuming
 * shaderpack-side portal handling will take over. But shaderpacks only
 * handle terrain via their own gl_ClipDistance writes; entities (block
 * entities like chests, mob renderers, item entities) are left unclipped
 * during the portal-through view. The user's previous test showed a chest
 * in nether visible from the back of a source portal -- IP's slot-0
 * uniform was being zeroed, so IP's discard write evaluated to no-clip.
 *
 * <p>This mixin restores the clip behavior by writing IP's installed
 * equation to our own slot-1 uniform on every shader bind, and enabling
 * {@code GL_CLIP_DISTANCE1} whenever IP's portal-through clipping is
 * active ({@link FrontClipping#isClippingEnabled}). Entity / block-entity
 * shaders that we extended in {@code shader_transformation.yaml} now
 * write {@code gl_ClipDistance[1]} from this uniform, so the discard
 * fires regardless of what IP did with slot 0.
 *
 * <p><b>Coordination with sub-level clip:</b>
 * {@link SableSourceClipMixin} writes a per-sub-level equation into the
 * same uniform during {@code renderChunkedSubLevel}. That mixin's HEAD
 * inject runs AFTER this {@code setShader} hook fires (since the shader
 * is bound before Sable's render call), so the per-sub-level equation
 * naturally overrides this mirror. After Sable's RETURN, the next
 * {@code setShader} call refreshes this hook again, restoring IP's
 * equation for the rest of the render. So:
 *
 * <ul>
 *   <li>Outside any sub-level draw, this hook drives slot 1 from IP's
 *       portal-through state.</li>
 *   <li>Inside a sub-level draw, the SableSourceClip path takes over.</li>
 *   <li>After RETURN, this hook reasserts on the next shader switch.</li>
 * </ul>
 *
 * <p>Priority left default so this fires after IP's
 * {@code MixinRenderSystem_Clipping} (which is what we're augmenting).
 */
@Mixin(value = RenderSystem.class, remap = false)
public class IplShaderClipMirrorMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable-clip-discover");

    /**
     * Names of shaders we've already logged "discover" info for. Used to ensure
     * each distinct shader name surfaces in the log at most once per session,
     * regardless of how many times {@code setShader} fires for it during portal
     * rendering. Vital because {@code setShader} fires per-draw and would spam
     * the log otherwise.
     */
    @Unique
    private static final ConcurrentMap<String, Boolean> IPL$DISCOVERED_SHADERS =
        new ConcurrentHashMap<>();

    @Inject(
        method = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V",
        at = @At("RETURN")
    )
    private static void ipl$mirrorClipEquationToSlot1(
        Supplier<ShaderInstance> supplier,
        CallbackInfo ci
    ) {
        if (!FrontClipping.isClippingEnabled) {
            return;
        }

        double[] worldEq = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
        if (worldEq == null) return;

        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) return;

        // Discovery diagnostic: the user has reported certain block entities
        // (chest mesh, enchanting-table book, banners, etc.) leaking through the
        // portal even with entities_* added to our affected-shaders list. The
        // likely cause is that those block entities don't use the shaders we
        // think they do under Iris -- they may bind a different named shader
        // (e.g. a baked-layer shader, a special entity shader variant, or
        // something Sodium-specific).
        //
        // This log fires the FIRST time each distinct shader name is bound while
        // FrontClipping.isClippingEnabled (i.e. while we're in a portal-through
        // render). It records both the shader name and whether our slot-1 uniform
        // is registered on that shader. After running a scene where the leaky
        // BEs are visible through a portal, the log will contain every shader
        // bound during that portal-through pass. The leaky ones are the entries
        // where hasUniform=false -- those are the names we need to add to our
        // YAML affected-shaders list.
        String name = shader.getName();
        Uniform uniform = ((IplSubLevelClipShader) shader).ipl$getSubLevelClipUniform();
        boolean hasUniform = uniform != null;
        // Also probe IP's slot-0 uniform on the just-bound shader. If slot 0 is
        // present, IP's MixinRenderSystem_Clipping should have written the
        // equation right before us. We can read the location to confirm whether
        // glGetUniformLocation actually resolved it (location >= 0 means
        // resolved; -1 means the uniform exists but the linker stripped it
        // before location resolution). This is the key signal for the chest-leak
        // diagnosis: if hasIPClip=true but IP's loc=-1, the slot-0 path is
        // resolved-then-stale (Veil swapped the program after IP cached the
        // location). If hasIPClip=false entirely, the shader was never wired.
        Uniform ipClip = ((IEShader) shader).ip_getClippingEquationUniform();
        int ipLoc = ipClip == null ? -2 : ipClip.getLocation();
        if (IPL$DISCOVERED_SHADERS.putIfAbsent(name, Boolean.TRUE) == null) {
            IPL$LOG.info(
                "[IPL-CLIP-DISCOVER] shader='{}' slot0(IP)={} slot0Loc={} slot1(IPL)={} eq=[{},{},{},{}]",
                name,
                ipClip != null ? "PRESENT" : "ABSENT",
                ipLoc,
                hasUniform ? "PRESENT" : "ABSENT",
                worldEq[0], worldEq[1], worldEq[2], worldEq[3]
            );
        }

        if (uniform == null) return;

        uniform.set(
            (float) worldEq[0],
            (float) worldEq[1],
            (float) worldEq[2],
            (float) worldEq[3]
        );
        // NOTE: do NOT call upload() here. setShader's RETURN fires before
        // glUseProgram (apply() does that), so calling upload() now errors out
        // with "No active program" -- and worse, Uniform.upload() clears the
        // dirty flag even on GL error, so the subsequent apply() would skip
        // re-upload thinking the value is already on the GPU. Leaving as set()
        // only means the next apply() will pick up the dirty flag and push our
        // value correctly. The natural apply()-driven flow handles this for
        // both terrain (renderSectionLayer) and BE / entity (BufferSource flush
        // -> drawWithShader -> apply).

        // Slot 1 belongs only to a sub-level draw bracket. Enabling it for IP's
        // portal-wide state leaks the last mapped projection equation into later draws.
    }
}
