package ipl.sable.duck;

import com.mojang.blaze3d.shaders.Uniform;
import org.jetbrains.annotations.Nullable;

/**
 * Duck interface implemented on every {@link net.minecraft.client.renderer.ShaderInstance}
 * via {@link ipl.sable.mixin.client.IplShaderInstanceClipMixin}. Exposes a getter for
 * our independent per-sub-level clip-plane uniform.
 *
 * <p>Why a separate uniform from IP's {@code ip_clippingEquation}: IP's slot drives
 * {@code gl_ClipDistance[0]} and gets reused by IP's own pipeline (portal-through
 * view clip, entity render clip, weather clip, etc.). When we override that uniform
 * with our per-sub-level equation, we either fight IP for it or risk breaking IP's
 * intended visual effect in scenarios where both want to be active simultaneously
 * (e.g., a mirror inside a portal-through render -- IP wants to clip the dest-dim
 * view at the portal frame; we want to additionally clip the mirror's geometry at
 * its own plane). Using a parallel uniform tied to {@code gl_ClipDistance[1]}
 * lets both planes apply independently -- a fragment is culled if EITHER plane
 * says so, which is the geometrically-correct "intersection of kept half-spaces."
 */
public interface IplSubLevelClipShader {
    @Nullable
    Uniform ipl$getSubLevelClipUniform();

    /**
     * Element [1] of the (now array) clip-equation uniform — the SECOND cut plane
     * for multi-straddle source rendering. The shader takes {@code min} of both
     * plane distances into the same {@code gl_ClipDistance[1]}, so the kept region
     * is the intersection of half-spaces with no extra GL state. Neutral value
     * {@code (0,0,0,1)} disables the second cut.
     */
    @Nullable
    Uniform ipl$getSubLevelClipUniform2();
}
