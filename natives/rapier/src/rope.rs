use std::collections::HashMap;

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong, jsize};
use marten::Real;
use rapier3d::dynamics::{GenericJointBuilder, JointAxis, RigidBodyBuilder, SpringCoefficients};
use rapier3d::geometry::{ColliderBuilder, SharedShape};
use rapier3d::glamx::DVec3;
use rapier3d::math::Vec3;
use rapier3d::prelude::{
    ImpulseJointHandle, ImpulseJointSet, JointAxesMask, RigidBodyHandle, RopeJointBuilder,
};

use crate::config::{JOINT_SPRING_DAMPING_RATIO, JOINT_SPRING_FREQUENCY};
use crate::scene::{LevelColliderID, PhysicsScene, SableSceneData, SimulationSceneData};
use crate::with_handle;

const MIN_BOUND_STIFFNESS: Real = 150.0;
const MIN_BOUND_DAMPING: Real = 10.0;

struct RopeAttachment {
    joint: ImpulseJointHandle,
    sub_level_id: Option<LevelColliderID>,
    location: DVec3,
    /// IPL atlas: portal seam. When the attached ship transited a portal, the joint is
    /// re-targeted to the static GROUND body and this isometry maps the ship's attach
    /// point (in its post-transit frame) back into the rope's source frame each tick —
    /// the anchor tracks the ship's IMAGE at the portal, so rope tension pulls the
    /// trailing body toward and through the aperture instead of across raw
    /// source/dest coordinates. One-way coupling (the ship feels no rope reaction);
    /// the Java-side overstretch break bounds the error. None = normal attachment.
    portal_prefix: Option<(Vec3, rapier3d::math::Rotation)>,
}

struct RopeStrand {
    points: Vec<RigidBodyHandle>,
    joints: Vec<(ImpulseJointHandle, ImpulseJointHandle)>,

    point_radius: Real,
    first_joint_length: Real,

    start_attachment: Option<RopeAttachment>,
    end_attachment: Option<RopeAttachment>,

    /// IPL atlas: owning chart — per-level `tick` maintains only its own strands,
    /// and new points get the chart's rope collision group.
    chart: crate::scene::ChartId,
}
#[derive(Default)]
pub struct RopeMap {
    counting_id: usize,
    ropes: HashMap<usize, RopeStrand>,
}

pub fn tick(scene: &PhysicsScene) {
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

    let mut dead_start_attachments = Vec::new();
    let mut dead_end_attachments = Vec::new();

    for (id, rope) in sable_data.rope_map.ropes.iter() {
        // Atlas: this view's tick maintains only its own chart's strands.
        if rope.chart != scene.chart {
            continue;
        }
        if let Some(attachment) = &rope.start_attachment {
            if !sim.impulse_joint_set.contains(attachment.joint) {
                dead_start_attachments.push(id.clone());
            } else if let Some(anchor) = attachment_anchor(attachment, &sable_data, &sim) {
                let impulse_joint = sim
                    .impulse_joint_set
                    .get_mut(attachment.joint, false)
                    .unwrap();
                impulse_joint.data.set_local_anchor1(anchor);
            }
        }

        if let Some(attachment) = &rope.end_attachment {
            if !sim.impulse_joint_set.contains(attachment.joint) {
                dead_end_attachments.push(id.clone());
            } else if let Some(anchor) = attachment_anchor(attachment, &sable_data, &sim) {
                let impulse_joint = sim
                    .impulse_joint_set
                    .get_mut(attachment.joint, false)
                    .unwrap();
                impulse_joint.data.set_local_anchor1(anchor);
            }
        }
    }

    for id in dead_start_attachments {
        if let Some(rope) = sable_data.rope_map.ropes.get_mut(&id) {
            rope.start_attachment = None;
        }
    }

    for id in dead_end_attachments {
        if let Some(rope) = sable_data.rope_map.ropes.get_mut(&id) {
            rope.end_attachment = None;
        }
    }
}

/// The attachment joint's body1 local anchor for this tick. Normal attachments: the
/// stored location in the counterpart's COM space. Portal-seam attachments (joint
/// re-targeted to GROUND by `setRopePortalPrefix`): the ship's attach point mapped
/// through the portal isometry into the rope's source frame — a world-space anchor
/// that tracks the ship's image at the portal.
fn attachment_anchor(
    attachment: &RopeAttachment,
    sable_data: &SableSceneData,
    sim: &SimulationSceneData,
) -> Option<Vec3> {
    if let (Some((pt, pr)), Some(id_b)) = (&attachment.portal_prefix, attachment.sub_level_id) {
        let info = sable_data.level_colliders.get(&id_b)?;
        let com = info.center_of_mass?;
        let body = *sable_data.rigid_bodies.get(&id_b)?;
        let pose = *sim.rigid_body_set.get(body)?.position();
        let dest = pose.transform_point((attachment.location - com).as_vec3());
        return Some(*pr * dest + *pt);
    }
    let local_anchor = attachment.location
        - if let Some(id_b) = attachment.sub_level_id {
            sable_data.level_colliders.get(&id_b)?.center_of_mass?
        } else {
            DVec3::ZERO
        };
    Some(local_anchor.as_vec3())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_createRope<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    point_radius: jdouble,
    first_joint_length: jdouble,
    points: JDoubleArray<'local>,
    num_points: jint,
) -> jlong {
    let mut coordinates = vec![0.0; (num_points * 3) as usize];
    env.get_double_array_region(points, 0, &mut coordinates)
        .unwrap();

    with_handle(handle, |scene| {
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let universal_drag = scene.universal_drag;

        let mut vec = Vec::with_capacity(num_points as usize);
        for i in 0..(num_points as usize) {
            let coordinate = Vec3::new(
                coordinates[i * 3] as Real,
                coordinates[i * 3 + 1] as Real,
                coordinates[i * 3 + 2] as Real,
            );

            let handle = create_rope_body(
                &mut sim_data,
                universal_drag,
                coordinate,
                point_radius as Real,
                scene.chart,
            );

            vec.push(handle);
        }

        let mut joints: Vec<(ImpulseJointHandle, ImpulseJointHandle)> =
            Vec::with_capacity(vec.len() - 1);
        for i in 0..vec.len() - 1 {
            let point_handle_0 = &vec[i];
            let point_handle_1 = &vec[i + 1];

            let length = if i == 0 {
                first_joint_length as Real
            } else {
                1.0
            };
            joints.push(add_rope_joint(
                &mut sim_data.impulse_joint_set,
                point_handle_0,
                point_handle_1,
                length,
            ));
        }

        let strand = RopeStrand {
            points: vec,
            point_radius: point_radius as Real,
            first_joint_length: first_joint_length as Real,
            start_attachment: None,
            end_attachment: None,
            joints,
            chart: scene.chart,
        };

        sable_data.rope_map.counting_id += 1;
        let id = sable_data.rope_map.counting_id;

        sable_data.rope_map.ropes.insert(id, strand);

        id as jlong
    })
}

fn add_rope_joint(
    impulse_joint_set: &mut ImpulseJointSet,
    point_handle_0: &RigidBodyHandle,
    point_handle_1: &RigidBodyHandle,
    length: Real,
) -> (ImpulseJointHandle, ImpulseJointHandle) {
    let mut joint = RopeJointBuilder::new(length)
        .local_anchor1(Vec3::ZERO)
        .local_anchor2(Vec3::ZERO)
        .softness(SpringCoefficients::new(
            JOINT_SPRING_FREQUENCY,
            JOINT_SPRING_DAMPING_RATIO,
        ));
    joint.0.data.set_limits(JointAxis::LinX, [0.0, length]);
    joint.0.data.set_motor_position(
        JointAxis::LinX,
        length,
        MIN_BOUND_STIFFNESS,
        MIN_BOUND_DAMPING,
    );
    let handle = impulse_joint_set.insert(*point_handle_0, *point_handle_1, joint.build(), true);
    let damp_handle = impulse_joint_set.insert(
        *point_handle_0,
        *point_handle_1,
        GenericJointBuilder::new(JointAxesMask::empty())
            .softness(SpringCoefficients::new(
                JOINT_SPRING_FREQUENCY,
                JOINT_SPRING_DAMPING_RATIO,
            ))
            .build(),
        true,
    );

    let damp_joint = &mut impulse_joint_set.get_mut(damp_handle, false).unwrap().data;

    let damping_strength = 18.0;
    damp_joint.set_motor_velocity(JointAxis::LinX, 0.0, damping_strength);
    damp_joint.set_motor_velocity(JointAxis::LinY, 0.0, damping_strength);
    damp_joint.set_motor_velocity(JointAxis::LinZ, 0.0, damping_strength);

    (handle, damp_handle)
}

fn create_rope_body(
    sim_data: &mut SimulationSceneData,
    universal_drag: Real,
    coordinate: Vec3,
    point_radius: Real,
    chart: crate::scene::ChartId,
) -> RigidBodyHandle {
    let mut rigid_body = RigidBodyBuilder::dynamic()
        .translation(coordinate)
        .lock_rotations()
        .build();

    rigid_body.set_linear_damping(universal_drag);
    rigid_body.set_angular_damping(universal_drag);

    let handle = sim_data.rigid_body_set.insert(rigid_body);
    let collider = ColliderBuilder::new(SharedShape::cuboid(
        point_radius as Real,
        point_radius as Real,
        point_radius as Real,
    ))
    .friction(0.15)
    .mass(0.35)
    .collision_groups(crate::groups::rope_group(chart))
    .build();

    sim_data
        .collider_set
        .insert_with_parent(collider, handle, &mut sim_data.rigid_body_set);

    handle
}

/// Removes a rope
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_queryRope<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jlong,
) -> JDoubleArray<'local> {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = scene.sim_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);

        let strand = sable_data.rope_map.ropes.get(&(id as usize)).unwrap();

        let flattened: Vec<jdouble> = strand
            .points
            .iter()
            .flat_map(|x| {
                let pos = sim_data
                    .rigid_body_set
                    .get(*x)
                    .unwrap()
                    .position()
                    .translation;
                vec![pos.x as f64, pos.y as f64, pos.z as f64]
            })
            .collect();

        let double_array = env
            .new_double_array((strand.points.len() * 3) as jsize)
            .unwrap();
        env.set_double_array_region(&double_array, 0, &flattened)
            .unwrap();
        double_array
    })
}

/// Removes a rope
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_removeRope<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jlong,
) {
    with_handle(handle, |scene| {
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = &mut *sim_data;

        let strand = sable_data.rope_map.ropes.remove(&(id as usize)).unwrap();
        for handle in strand.points {
            sim_data.rigid_body_set.remove(
                handle,
                &mut sim_data.island_manager,
                &mut sim_data.collider_set,
                &mut sim_data.impulse_joint_set,
                &mut sim_data.multibody_joint_set,
                true,
            );
        }
    })
}

/// Sets the joint
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_setRopeFirstSegmentLength<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jlong,
    length: jdouble,
) {
    with_handle(handle, |scene| {
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let strand = sable_data.rope_map.ropes.get_mut(&(id as usize)).unwrap();

        strand.first_joint_length = length as Real;
        let first_joint = &mut sim_data
            .impulse_joint_set
            .get_mut(strand.joints.first().unwrap().0, true)
            .unwrap()
            .data;
        first_joint.set_limits(JointAxis::LinX, [0.0, length as Real]);
        first_joint.set_motor_position(
            JointAxis::LinX,
            length as Real,
            MIN_BOUND_STIFFNESS,
            MIN_BOUND_DAMPING,
        );
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_removeRopePointAtStart<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jlong,
) {
    with_handle(handle, |scene| {
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = &mut *sim_data;

        let strand = sable_data.rope_map.ropes.get_mut(&(id as usize)).unwrap();
        let point = strand.points.remove(0);
        strand.joints.remove(0);
        sim_data.rigid_body_set.remove(
            point,
            &mut sim_data.island_manager,
            &mut sim_data.collider_set,
            &mut sim_data.impulse_joint_set,
            &mut sim_data.multibody_joint_set,
            true,
        );

        let new_first_joint = &mut sim_data
            .impulse_joint_set
            .get_mut(strand.joints.first().unwrap().0, false)
            .unwrap()
            .data;
        new_first_joint.set_limits(JointAxis::LinX, [0.0, strand.first_joint_length]);
        new_first_joint.set_motor_position(
            JointAxis::LinX,
            strand.first_joint_length,
            MIN_BOUND_STIFFNESS,
            MIN_BOUND_DAMPING,
        );

        if strand.start_attachment.is_some() {
            sim_data
                .impulse_joint_set
                .remove(strand.start_attachment.as_ref().unwrap().joint, true);
            strand.start_attachment = None;
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_addRopePointAtStart<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) {
    with_handle(handle, |scene| {
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let universal_drag = scene.universal_drag;

        let strand = sable_data.rope_map.ropes.get_mut(&(id as usize)).unwrap();
        let point_radius = strand.point_radius;

        // set joint that will no longer be the first
        let old_joint = &mut sim_data
            .impulse_joint_set
            .get_mut(strand.joints.first().unwrap().0, false)
            .unwrap()
            .data;
        old_joint.set_limits(JointAxis::LinX, [0.0, 1.0]);
        old_joint.set_motor_position(JointAxis::LinX, 1.0, MIN_BOUND_STIFFNESS, MIN_BOUND_DAMPING);

        let strand_chart = strand.chart;
        let handle = create_rope_body(
            &mut sim_data,
            universal_drag,
            Vec3::new(x as Real, y as Real, z as Real),
            point_radius,
            strand_chart,
        );
        strand.joints.insert(
            0,
            add_rope_joint(
                &mut sim_data.impulse_joint_set,
                &handle,
                strand.points.first().unwrap(),
                strand.first_joint_length,
            ),
        );
        strand.points.insert(0, handle);

        if strand.start_attachment.is_some() {
            sim_data
                .impulse_joint_set
                .remove(strand.start_attachment.as_ref().unwrap().joint, true);
            strand.start_attachment = None;
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_wakeUpRope<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    rope_id: jlong,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let strand = sable_data.rope_map.ropes.get(&(rope_id as usize)).unwrap();

        for point in &strand.points {
            sim_data
                .rigid_body_set
                .get_mut(*point)
                .unwrap()
                .wake_up(true);
        }
    })
}

/// Sets the attachment at a given end
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_setRopeAttachment<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    rope_id: jlong,
    sub_level_id: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    end: jboolean,
) {
    with_handle(handle, |scene| {
        let ground_handle = scene.ground_handle.unwrap();
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let SableSceneData {
            rope_map,
            rigid_bodies,
            ..
        } = &mut *sable_data;

        let strand = rope_map.ropes.get_mut(&(rope_id as usize)).unwrap();

        let rope_body = if end > 0 {
            strand.points.last()
        } else {
            strand.points.first()
        }
        .unwrap();
        let sub_level_body = if sub_level_id == -1 {
            ground_handle
        } else {
            *rigid_bodies
                .get(&(sub_level_id as LevelColliderID))
                .unwrap()
        };

        let joint = RopeJointBuilder::new(0.0)
            .local_anchor1(Vec3::ZERO)
            .local_anchor2(Vec3::ZERO)
            .softness(SpringCoefficients::new(
                JOINT_SPRING_FREQUENCY,
                JOINT_SPRING_DAMPING_RATIO,
            ));
        let joint =
            sim_data
                .impulse_joint_set
                .insert(sub_level_body, *rope_body, joint.build(), true);

        if if end > 0 {
            &strand.end_attachment
        } else {
            &strand.start_attachment
        }
        .is_some()
        {
            let attachment = if end > 0 {
                &strand.end_attachment
            } else {
                &strand.start_attachment
            }
            .as_ref()
            .unwrap();
            sim_data.impulse_joint_set.remove(attachment.joint, true);
        }
        let attachment = RopeAttachment {
            sub_level_id: if sub_level_id == -1 {
                None
            } else {
                Some(sub_level_id as LevelColliderID)
            },
            joint,
            location: DVec3::new(x, y, z),
            portal_prefix: None,
        };

        if end > 0 {
            strand.end_attachment = Some(attachment);
        } else {
            strand.start_attachment = Some(attachment);
        }
    })
}

// ---------------------------------------------------------------------------
// IPL atlas: rope portal seams. A rope whose attached ship transited a portal
// keeps its chain (and the joint solve) entirely in the SOURCE frame: the end
// joint is re-targeted to the static GROUND body and its anchor tracks the
// ship's image through the portal isometry (see `attachment_anchor`).
// ---------------------------------------------------------------------------

/// Set (`has != 0`) or clear the portal prefix on one rope end. Setting re-targets the
/// attachment joint to the ground body (anchor driven per tick); clearing restores the
/// normal ship attachment. No-op when the rope/end/ship doesn't exist.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setRopePortalPrefix<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    rope_id: jlong,
    end: jboolean,
    has: jboolean,
    px: jdouble,
    py: jdouble,
    pz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    with_handle(handle, |scene| {
        let Some(ground_handle) = scene.ground_handle else {
            return;
        };
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let SableSceneData {
            rope_map,
            rigid_bodies,
            ..
        } = &mut *sable_data;

        let Some(strand) = rope_map.ropes.get_mut(&(rope_id as usize)) else {
            return;
        };
        let rope_body = *if end > 0 {
            strand.points.last()
        } else {
            strand.points.first()
        }
        .unwrap();
        let attachment = if end > 0 {
            &mut strand.end_attachment
        } else {
            &mut strand.start_attachment
        };
        let Some(attachment) = attachment.as_mut() else {
            return;
        };
        let Some(sub_level_id) = attachment.sub_level_id else {
            return; // ground attachments never split — the ground doesn't transit
        };

        let was_prefixed = attachment.portal_prefix.is_some();
        if has != 0 {
            attachment.portal_prefix = Some((
                Vec3::new(px as Real, py as Real, pz as Real),
                rapier3d::math::Rotation::from_xyzw(
                    qx as Real,
                    qy as Real,
                    qz as Real,
                    qw as Real,
                )
                .normalize(),
            ));
        } else {
            attachment.portal_prefix = None;
        }

        // Re-target the joint when the counterpart body changes: ground while
        // prefixed (anchor driven per tick), the ship when restored.
        if was_prefixed != attachment.portal_prefix.is_some() {
            let counterpart = if attachment.portal_prefix.is_some() {
                ground_handle
            } else {
                let Some(body) = rigid_bodies.get(&sub_level_id) else {
                    return;
                };
                *body
            };
            sim_data.impulse_joint_set.remove(attachment.joint, true);
            let joint = RopeJointBuilder::new(0.0)
                .local_anchor1(Vec3::ZERO)
                .local_anchor2(Vec3::ZERO)
                .softness(SpringCoefficients::new(
                    JOINT_SPRING_FREQUENCY,
                    JOINT_SPRING_DAMPING_RATIO,
                ));
            attachment.joint =
                sim_data
                    .impulse_joint_set
                    .insert(counterpart, rope_body, joint.build(), true);
        }
    })
}

/// Teleport an entire rope chain through an isometry (both ends re-unified on the far
/// side of a portal): every particle's pose and velocity map through `(R, t)`, and the
/// strand + its colliders restamp to the chart of `dest_scene_handle` (0 = keep chart).
/// The caller clears both ends' portal prefixes afterwards.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_remapRope<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    rope_id: jlong,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
    dest_scene_handle: jlong,
) {
    with_handle(handle, |scene| {
        let dest_chart = if dest_scene_handle == 0 {
            scene.chart
        } else {
            unsafe { &*(dest_scene_handle as *const PhysicsScene) }.chart
        };
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let Some(strand) = sable_data.rope_map.ropes.get_mut(&(rope_id as usize)) else {
            return;
        };
        let rot = rapier3d::math::Rotation::from_xyzw(
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
        )
        .normalize();
        let tr = Vec3::new(dx as Real, dy as Real, dz as Real);

        for point in &strand.points {
            if let Some(rb) = sim.rigid_body_set.get_mut(*point) {
                let mut pose = *rb.position();
                pose.translation = rot * pose.translation + tr;
                pose.rotation = (rot * pose.rotation).normalize();
                rb.set_position(pose, true);
                let lv = rb.linvel();
                rb.set_linvel(rot * lv, true);
            }
        }

        strand.chart = dest_chart;
        let group = crate::groups::rope_group(dest_chart);
        let collider_handles: Vec<_> = strand
            .points
            .iter()
            .filter_map(|p| sim.rigid_body_set.get(*p))
            .flat_map(|rb| rb.colliders().to_vec())
            .collect();
        for ch in collider_handles {
            if let Some(collider) = sim.collider_set.get_mut(ch) {
                collider.set_collision_groups(group);
            }
        }
    })
}

/// Ropes whose start/end attachment names the given Sable body. Returns packed
/// `(ropeId << 1) | endBit` values.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_ropesAttachedToSableBody<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    body_id: jint,
) -> jni::objects::JLongArray<'local> {
    let mut packed: Vec<jlong> = Vec::new();
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        for (id, strand) in sable_data.rope_map.ropes.iter() {
            if let Some(a) = &strand.start_attachment {
                if a.sub_level_id == Some(body_id as LevelColliderID) {
                    packed.push(((*id as jlong) << 1) | 0);
                }
            }
            if let Some(a) = &strand.end_attachment {
                if a.sub_level_id == Some(body_id as LevelColliderID) {
                    packed.push(((*id as jlong) << 1) | 1);
                }
            }
        }
    });
    let result = env.new_long_array(packed.len() as jsize).unwrap();
    if !packed.is_empty() {
        env.set_long_array_region(&result, 0, &packed).unwrap();
    }
    result
}

/// Rope ids whose total stretch ratio (chain length + attachment gaps, over natural
/// length) exceeds `threshold`. Prefix-mapped attachment gaps measure through the
/// portal, so a rope reeling a trailing ship registers its true through-portal
/// overstretch. Used by the Java break monitor.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_overstretchedRopes<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    threshold: jdouble,
) -> jni::objects::JLongArray<'local> {
    let mut over: Vec<jlong> = Vec::new();
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim = scene.sim_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        for (id, strand) in sable_data.rope_map.ropes.iter() {
            if strand.points.len() < 2 {
                continue;
            }
            let natural =
                strand.first_joint_length + (strand.points.len() as Real - 2.0).max(0.0);
            if natural <= 0.01 {
                continue;
            }
            let mut actual: Real = 0.0;
            let mut ok = true;
            let mut prev: Option<Vec3> = None;
            for point in &strand.points {
                let Some(rb) = sim.rigid_body_set.get(*point) else {
                    ok = false;
                    break;
                };
                let pos = rb.position().translation;
                if let Some(p) = prev {
                    actual += (pos - p).length();
                }
                prev = Some(pos);
            }
            if !ok {
                continue;
            }
            // Attachment gaps: distance from each attached rope end to its anchor
            // target (image-mapped when a portal prefix is active).
            for (attachment, point) in [
                (&strand.start_attachment, strand.points.first()),
                (&strand.end_attachment, strand.points.last()),
            ] {
                if let (Some(a), Some(point)) = (attachment, point) {
                    let Some(anchor) = attachment_anchor(a, &sable_data, &sim) else {
                        continue;
                    };
                    // Ground-frame anchors (prefixed or plain ground) are world-space;
                    // ship-frame anchors need the ship pose applied.
                    let world = if a.portal_prefix.is_some() || a.sub_level_id.is_none() {
                        anchor
                    } else if let Some(body) = a
                        .sub_level_id
                        .and_then(|id_b| sable_data.rigid_bodies.get(&id_b))
                    {
                        match sim.rigid_body_set.get(*body) {
                            Some(rb) => rb.position().transform_point(anchor),
                            None => continue,
                        }
                    } else {
                        continue;
                    };
                    if let Some(rb) = sim.rigid_body_set.get(*point) {
                        actual += (world - rb.position().translation).length();
                    }
                }
            }
            if (actual / natural) as jdouble > threshold {
                over.push(*id as jlong);
            }
        }
    });
    let result = env.new_long_array(over.len() as jsize).unwrap();
    if !over.is_empty() {
        env.set_long_array_region(&result, 0, &over).unwrap();
    }
    result
}
