package ipl.sable.render;

import foundry.veil.forge.event.ForgeVeilAddShaderProcessorsEvent;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

/**
 * Loaded only when Veil is present on the classpath. The caller in
 * {@code IPModEntryClient.onInitializeClient} guards entry with
 * {@code ModList.isLoaded("veil")} so this class is never referenced -- and
 * therefore never verified -- on a Veil-less install. See the long-form
 * rationale on {@link IplVeilShaderPreProcessor} for why this hookup is
 * necessary.
 */
public final class IplVeilCompat {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-veil-preprocess");

    /**
     * The Veil {@link ShaderProcessorList} stores preprocessors in an unbounded
     * {@code List} and nulls its cached composed processor on every add, so
     * registering inside the event handler — which fires once *per compile cycle*,
     * not once per init — would grow the list linearly across the session.
     * Latch to register the singleton instance exactly once.
     */
    private static volatile IplVeilShaderPreProcessor INSTANCE;

    private IplVeilCompat() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ForgeVeilAddShaderProcessorsEvent.class, event -> {
            // Best-effort init of IP's YAML configs synchronously before Veil's
            // compile pass starts. IP's own init queues this via
            // Minecraft.execute(...) which is drained *after* Veil has already
            // compiled 58 vanilla shaders, so without this our preprocessor's
            // modify() short-circuits with configs == null and produces
            // un-injected GLSL.
            //
            // BUT: Veil also fires this event *very* early -- during
            // GameRenderer.preloadUiShader, while Minecraft's constructor is
            // still running -- for the blit_screen UI shader. At that point
            // the resource manager doesn't yet have IP's resource pack mounted
            // and McHelper.readTextResource hits Optional.get() on an empty
            // Optional, throwing NoSuchElementException. If we let that escape,
            // the event-dispatch fails and the client hard-crashes at boot.
            //
            // Strategy: try init opportunistically. If resources aren't ready
            // yet (i.e. the blit_screen-time early call), swallow the failure;
            // this event fires again later for the bulk vanilla recompile pass
            // when resources ARE ready, and init() is idempotent so the retry
            // populates configs cleanly.
            try {
                ShaderCodeTransformation.init();
            } catch (RuntimeException e) {
                LOG.debug(
                    "ShaderCodeTransformation.init() not yet possible "
                        + "(resources still loading?); will retry on next event firing",
                    e
                );
            }

            if (INSTANCE == null) {
                INSTANCE = new IplVeilShaderPreProcessor();
                LOG.info("Created IplVeilShaderPreProcessor singleton");
            }

            // addPreprocessorFirst so we run before any Veil-side dynamic-buffer
            // / sodium / dynamic-buffer-fallback rewrites mutate the tree. The
            // IP regex matches `void main(){` which is a stable anchor; running
            // first means we don't have to worry about other preprocessors
            // having already rewritten the function header into something the
            // regex no longer recognizes.
            event.addPreprocessorFirst(INSTANCE, false);
        });
    }
}
