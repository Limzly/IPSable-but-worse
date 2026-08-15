package ipl.sable.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the pipeline's collider bakery for portal rim geometry. Native collider handles
 * are process-global and valid in every chart.
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public interface IplRapierPipelineAccess {

    @Accessor(value = "colliderBakery", remap = false)
    RapierVoxelColliderBakery ipl$colliderBakery();

    /** Sable 2.0: scene ids are native {@code long} handles held by the pipeline. */
    @org.spongepowered.asm.mixin.gen.Invoker(value = "getSceneHandle", remap = false)
    long ipl$sceneHandle();

    /**
     * Raw scene FIELD — null once the pipeline is torn down (or never armed). Use
     * this for liveness checks: {@code ipl$sceneHandle()} is an invoker into
     * {@code getSceneHandle}, which dereferences the scene and NPEs when null.
     */
    @Accessor(value = "scene", remap = false)
    dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsScene ipl$scene();

}
