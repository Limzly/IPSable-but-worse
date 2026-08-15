package qouteall.imm_ptl.core.render;

import java.util.Set;

/**
 * Sable-fork helper: shader names whose GLSL injection (in
 * {@code shader_transformation.yaml}) uses
 * {@code (ModelViewMat * vec4(Position, 1)).xyz} instead of
 * {@code Position + ChunkOffset}. These need the eye-space form of the
 * clipping-plane equation ({@link FrontClipping#getActiveClipPlaneEquationAfterModelView()})
 * because their vertex-side clip math is done in eye space. Pre-fork the chest,
 * item entities, banners, etc. failed to clip because the GLSL was dotting
 * model-space coordinates against a world-space equation, which is
 * geometrically meaningless.
 *
 * <p><b>Why this is a separate class:</b> declaring this {@code Set.of(...)} as
 * a {@code private static final} field directly inside
 * {@code MixinRenderSystem_Clipping} caused the mixin processor to merge the
 * field initializer into {@code RenderSystem}'s {@code <clinit>} on apply. The
 * string-constant references didn't survive the merge cleanly, so the call ran
 * as {@code Set.of(null, null, ...)} inside RenderSystem's static init -- NPE
 * inside {@code ImmutableCollections$SetN.probe} that killed the client at
 * boot before any frame rendered. Keeping the set in its own regular class
 * sidesteps the merge entirely.
 */
public final class EntityShaderNames {
    private EntityShaderNames() {}

    public static final Set<String> IS_ENTITY_STYLE = Set.of(
        "rendertype_entity_solid",
        "rendertype_entity_cutout",
        "rendertype_entity_cutout_no_cull",
        "rendertype_entity_cutout_no_cull_z_offset",
        "rendertype_item_entity_translucent_cull",
        "rendertype_entity_translucent_cull",
        "rendertype_entity_translucent",
        "rendertype_entity_smooth_cutout",
        "rendertype_beacon_beam",
        "rendertype_entity_translucent_emissive",
        "portal_area",
        "particle",
        // Iris-rewritten vanilla entity shader (no shaderpack). Iris's
        // glsl-transformer renames vanilla rendertype_entity_* into this
        // unified name with iris_-prefixed uniforms. Our YAML injection for
        // this shader uses iris_ModelViewMat * iris_Position (eye-space),
        // so the equation upload must also be eye-space.
        "block_entity_diffuse",
        // Iris-rewritten moving-block / particle (Create cogs and similar
        // animated mechanical visuals; particle effects under shader pack).
        // Same eye-space chain as block_entity_diffuse.
        "moving_block",
        "particles",
        // Veil-managed Simulated / Aeronautics block-entity sub-shaders.
        // Same eye-space GLSL injection as vanilla entity (Position model
        // space, ModelViewMat per-batch). Animated sub-components of
        // Create-Aeronautics machines (spring handles, rope segments,
        // levitite blocks, etc.) render through these.
        "simulated:end_sea",
        "simulated:spread_end_sea",
        "simulated:rope/rope",
        "simulated:redstone_accumulator/diode",
        "simulated:spring/spring",
        "simulated:laser_pointer/lens",
        "simulated:contraption_diagram/outline_diagram",
        "simulated:laser/laser",
        "aeronautics:levitite/levitite",
        "aeronautics:burner_flame",
        "aeronautics:hot_air_overlay",
        "aeronautics:soft_light",
        // Create's glowing_shader + NeoForge's unlit-translucent variant +
        // Veil's skinned mesh. All use vanilla Position/ModelViewMat layout
        // so the same eye-space upload path applies.
        "glowing_shader",
        "rendertype_entity_unlit_translucent",
        "veil:necromancer/skinned_mesh"
    );
}
