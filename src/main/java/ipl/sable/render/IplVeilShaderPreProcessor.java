package ipl.sable.render;

import com.mojang.blaze3d.shaders.Program;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Veil-side mirror of IP's {@code MixinProgram.redirectReadShaderSource}.
 *
 * <p><b>Why this exists:</b> when Veil is loaded (bundled with Sable), Veil cancels
 * Mojang's stock shader compile path and reroutes vanilla {@code rendertype_*}
 * (and entity / particle / portal_area) shaders through its own pipeline. By the
 * time Veil compiles those 58 vanilla shaders ("Compiled 58 vanilla shaders in
 * NNN ms" in the log), IP's redirect on {@link com.mojang.blaze3d.shaders.Program}'s
 * {@code IOUtils.toString} call has long since fired -- but only against the
 * first-generation linked programs that vanilla MC built. Veil throws those away
 * and links its own second-generation programs from un-injected source, so
 * {@code glGetUniformLocation("iportal_ClippingEquation")} returns -1 on the
 * Veil-replaced programs, which are what actually get bound at draw time.
 *
 * <p>Veil exposes a clean preprocessor API for this exact case: register a
 * {@link ShaderPreProcessor} via the {@code VeilAddShaderPreProcessorsEvent} and
 * Veil will call {@link #modify} on the parsed {@link GlslTree} of every shader
 * before compile. We round-trip the tree to source, run IP's regex-based
 * transformation on it (the same code that IP's {@code MixinProgram} runs), and
 * splice the result back into the tree's body. The Veil-compiled program then
 * carries our uniform + {@code gl_ClipDistance} write just like the original
 * Mojang-compiled one would have.
 *
 * <p><b>Name matching:</b> IP's YAML uses bare names like {@code rendertype_solid}
 * and namespaced names like {@code sodium:blocks/block_layer_opaque.vsh}. Veil's
 * {@code Context.name()} returns a {@link ResourceLocation}, so we try the full
 * {@code namespace:path} form first, then fall back to {@code path} alone. This
 * lets the same YAML entry match either way.
 */
public class IplVeilShaderPreProcessor implements ShaderPreProcessor {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-veil-preprocess");

    // Once-per-distinct-shader log so we can see in latest.log exactly which Veil
    // recompiles get our injection and which ones our YAML doesn't cover. Mirrors
    // the [IPL-PROBE-A-VANILLA] / [IPL-PROBE-A-SODIUM] pattern.
    private static final ConcurrentMap<String, Boolean> SEEN = new ConcurrentHashMap<>();

    @Override
    public void modify(Context ctx, GlslTree tree) throws GlslSyntaxException {
        ResourceLocation rl = ctx.name();
        String full = rl.toString();
        String path = rl.getPath();
        String typeName;
        if (ctx.isVertex()) typeName = "VERTEX";
        else if (ctx.isFragment()) typeName = "FRAGMENT";
        else typeName = "OTHER";

        // Lookup key in the YAML's affectedShaders list. We need to try
        // multiple candidate forms because the shader name reaches us with
        // different shapes depending on which Veil code path is calling us:
        //
        //   - Veil's preprocessor-event path for its own / mod shaders:
        //       "veil:blit_screen", "simulated:end_sea"
        //     -> just match the full ResourceLocation toString().
        //
        //   - Veil's VanillaShaderCompiler path that recompiles every
        //     vanilla rendertype shader passes the full resource asset path:
        //       "minecraft:shaders/core/rendertype_solid.vsh"
        //     -> we strip "shaders/core/" and ".vsh"/".fsh" to get the bare
        //     name "rendertype_solid" that IP's YAML uses.
        //
        // IP's YAML mixes both styles already -- `rendertype_solid` (bare),
        // `sodium:blocks/block_layer_opaque.vsh` (namespaced+path) -- so
        // matching is best-effort across the candidates.
        String stripped = stripVanillaShaderName(path);
        String key;
        if (ShaderCodeTransformation.shouldAddUniform(full)) {
            key = full;
        } else if (ShaderCodeTransformation.shouldAddUniform(path)) {
            key = path;
        } else if (stripped != null && ShaderCodeTransformation.shouldAddUniform(stripped)) {
            key = stripped;
        } else {
            if (SEEN.putIfAbsent(full + "/" + typeName + "/skip", Boolean.TRUE) == null) {
                LOG.info("[IPL-VEIL-PREPROCESS] name='{}' type={} -- not in affectedShaders, skip", full, typeName);
            }
            return;
        }

        Program.Type type = ctx.isVertex()
            ? Program.Type.VERTEX
            : ctx.isFragment() ? Program.Type.FRAGMENT : null;
        if (type == null) {
            // YAML only covers VERTEX/FRAGMENT; geom/tess shouldn't reach here.
            return;
        }

        String before = tree.toSourceString();
        String after = ShaderCodeTransformation.transform(type, key, before);
        if (before.equals(after)) {
            // Regex didn't match -- entry exists in YAML but pattern doesn't fit
            // this shader's source. Important to log because it's the same
            // silent-no-op condition IP's own redirect has, and it means clipping
            // will be broken for this shader.
            if (SEEN.putIfAbsent(key + "/" + type + "/nochange", Boolean.TRUE) == null) {
                LOG.warn(
                    "[IPL-VEIL-PREPROCESS] name='{}' type={} -- YAML lists this shader but regex"
                        + " produced no change (pattern doesn't match Veil-preprocessed source)",
                    key, type
                );
            }
            return;
        }

        GlslTree replacement;
        try {
            replacement = GlslParser.parse(after);
        } catch (GlslSyntaxException e) {
            LOG.error(
                "[IPL-VEIL-PREPROCESS] name='{}' type={} -- transformed source failed to re-parse;"
                    + " leaving the tree unmodified. This will leave clipping disabled for this shader.",
                key, type, e
            );
            return;
        }

        // Splice the regenerated body into the existing tree. We keep the
        // existing tree's version statement, directives, and macros -- only
        // the body (declarations + main) is rewritten, which is all the IP
        // regex actually touches.
        tree.getBody().clear();
        tree.getBody().addAll(replacement.getBody());

        if (SEEN.putIfAbsent(key + "/" + type, Boolean.TRUE) == null) {
            LOG.info("[IPL-VEIL-PREPROCESS] name='{}' type={} -- injected IP clip transformation", key, type);
        }
    }

    /**
     * Strips the {@code shaders/core/} prefix and the {@code .vsh}/{@code .fsh}
     * suffix off a Veil VanillaShaderCompiler-style resource path so the
     * remainder can be looked up in IP's bare-name affectedShaders list.
     * Returns {@code null} if the input doesn't match the expected shape.
     */
    private static String stripVanillaShaderName(String path) {
        if (path == null) return null;
        String s = path;
        if (s.startsWith("shaders/core/")) {
            s = s.substring("shaders/core/".length());
        } else {
            return null;
        }
        if (s.endsWith(".vsh")) {
            s = s.substring(0, s.length() - ".vsh".length());
        } else if (s.endsWith(".fsh")) {
            s = s.substring(0, s.length() - ".fsh".length());
        } else {
            return null;
        }
        return s.isEmpty() ? null : s;
    }
}
