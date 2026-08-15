use jni::JavaVM;
use jni::objects::{JDoubleArray, JObject, JValue};
use jni::signature::ReturnType;
use jni::sys::{jboolean, jdouble, jint, jvalue};
use marten::Real;
use marten::level::VoxelColliderData;
use rapier3d::geometry::{Collider, SolverContact};
use rapier3d::pipeline::PairFilterContext;
use rapier3d::prelude::SolverFlags;
use rapier3d::glamx::DVec3;
use rapier3d::math::{Pose, Vec3};
use rapier3d::pipeline::{ContactModificationContext, PhysicsHooks};
use std::sync::{Arc, RwLock};

use crate::collider::LevelCollider;
use crate::scene::{LevelColliderID, SableManifoldInfoMap, SableSceneData};

#[derive(Clone)]
pub struct SablePhysicsHooks {
    pub sable_data: Arc<RwLock<SableSceneData>>,
    pub manifold_info_map: Arc<SableManifoldInfoMap>,
    pub current_step_vm: Option<Arc<JavaVM>>,
}

impl PhysicsHooks for SablePhysicsHooks {
    fn filter_contact_pair(&self, context: &PairFilterContext) -> Option<SolverFlags> {
        if !crate::ipl_ext::portal_rim_contact_allowed(context) { return None; }
        Some(SolverFlags::COMPUTE_IMPULSES)
    }

    fn modify_solver_contacts(&self, context: &mut ContactModificationContext) {
        // IPSable aperture clipping (spec §2.5): runs for EVERY manifold that reaches the
        // hook, independent of the NEEDS_HOOKS block-property gate below. Reads go through
        // this hook's own RwLock'd scene data (2.0's threading model). A/B kill switch:
        // IPL_DISABLE_CLIP=1.
        if !ipl_clip_disabled() {
            self.ipl_clip_contacts(context);
            if context.solver_contacts.is_empty() {
                return;
            }
        }

        if !VoxelColliderData::needs_hooks(*context.user_data) {
            return;
        }

        let mut remove = false;
        for contact in context.solver_contacts.iter_mut() {
            let Some(collider_a) = context.colliders.get(context.collider1) else {
                panic!("No collider A!");
            };

            let Some(collider_b) = context.colliders.get(context.collider2) else {
                panic!("No collider B!");
            };

            let level_collider_a = collider_a.shape().as_shape::<LevelCollider>();
            let level_collider_b = collider_b.shape().as_shape::<LevelCollider>();

            if level_collider_a.is_none() && level_collider_b.is_none() {
                continue;
            }

            let mut tangent_velo = Vec3::ZERO;

            let mut velocity = 0.0;
            let mut friction_multiplier = 1.0;

            if let Some(handle) = context.rigid_body1 {
                let mut velo_1 = context
                    .bodies
                    .get(handle)
                    .unwrap()
                    .velocity_at_point(contact.point);
                velo_1 += self.get_fake_velocity(contact, collider_a, level_collider_a);
                velocity += velo_1.dot(*context.normal);
            }
            if let Some(handle) = context.rigid_body2 {
                let mut velo_2 = context
                    .bodies
                    .get(handle)
                    .unwrap()
                    .velocity_at_point(contact.point);
                velo_2 += self.get_fake_velocity(contact, collider_a, level_collider_a);
                velocity -= velo_2.dot(*context.normal);
            }

            velocity = velocity.abs();

            let mut restitution: Real = 0.0;

            let manifold_index = (*context.user_data >> 1) as usize;

            if let Some(level_collider_a) = level_collider_a {
                let (add_velo, remove_a, friction_mult, block_restitution) = self
                    .handle_block_params(
                        collider_a.position(),
                        collider_a,
                        Some(level_collider_a),
                        &contact.point,
                        velocity,
                        manifold_index,
                        true,
                    );
                tangent_velo += add_velo;
                remove |= remove_a;
                friction_multiplier *= friction_mult;
                restitution = restitution.max(block_restitution);
            }

            if let Some(level_collider_b) = level_collider_b {
                let (add_velo, remove_b, friction_mult, block_restitution) = self
                    .handle_block_params(
                        collider_b.position(),
                        collider_b,
                        Some(level_collider_b),
                        &contact.point,
                        velocity,
                        manifold_index,
                        false,
                    );
                tangent_velo -= add_velo;
                remove |= remove_b;
                friction_multiplier *= friction_mult;
                restitution = restitution.max(block_restitution);
            }

            tangent_velo -= *context.normal * tangent_velo.dot(*context.normal);

            contact.tangent_velocity = tangent_velo;
            contact.friction *= friction_multiplier;
            contact.restitution = contact.restitution.max(restitution);
        }

        if remove {
            context.solver_contacts.clear()
        }
    }
}

static IPL_CLIP_DISABLED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
static IPL_CLIP_FIRST_DROP: std::sync::Once = std::sync::Once::new();

fn ipl_clip_disabled() -> bool {
    *IPL_CLIP_DISABLED
        .get_or_init(|| std::env::var("IPL_DISABLE_CLIP").map(|v| v == "1").unwrap_or(false))
}

impl SablePhysicsHooks {
    /// Drop solver contacts inside either collider's clip regions (IPSable, spec §2.5).
    /// Contacts are scene-frame points; regions are set per body via
    /// `Java_ipl_sable_natives_IplRapierNatives_setClipRegions`.
    fn ipl_clip_contacts(&self, context: &mut ContactModificationContext) {
        for handle in [context.collider1, context.collider2] {
            let Some(collider) = context.colliders.get(handle) else {
                continue;
            };
            let Some(level_collider) = collider.shape().as_shape::<LevelCollider>() else {
                continue;
            };
            let Some(id) = level_collider.id else {
                continue; // the static world collider is never clipped
            };
            let sable_data = self.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
            let Some(info) = sable_data.level_colliders.get(&(id as LevelColliderID)) else {
                continue;
            };
            // Atlas M2: image colliders carry their OWN clip regions (far side of
            // the half-open seam); the native set keeps the body regions.
            let regions: &[crate::ipl_ext::IplClipRegion] = info
                .image_clip
                .get(&handle)
                .map(|v| v.as_slice())
                .unwrap_or(&info.clip_regions);
            if regions.is_empty() {
                continue;
            }
            // NEUTRALIZE in place — do NOT retain()/shrink the list. Partially removing
            // solver contacts is a state stock code never produces (its remove path only
            // ever clears ALL), and every session where the retain pass partially shrank
            // a manifold died with the same wild jump (0xc0000005, stable bogus RIP, on
            // solver worker threads, no hs_err) — the fork's SIMD constraint batcher
            // lane-gathers contacts unchecked. A far-separated zero-friction contact
            // yields zero impulse: physically identical to removal, structurally
            // invisible to the batcher.
            let mut clipped = 0u64;
            for c in context.solver_contacts.iter_mut() {
                if regions.iter().any(|r| r.contains(c.point)) {
                    c.dist = 10.0;
                    c.friction = 0.0;
                    c.restitution = 0.0;
                    c.tangent_velocity = Vec3::ZERO;
                    clipped += 1;
                }
            }
            // Diagnostic: prove in-game that the clip pass fires at all (once).
            if clipped > 0 {
                IPL_CLIP_FIRST_DROP.call_once(|| {
                    eprintln!(
                        "[ipl-natives] aperture clip ACTIVE: first neutralize, {clipped} contact(s) for body {id}"
                    );
                });
            }
        }
    }

    fn get_fake_velocity(
        &self,
        contact: &SolverContact,
        collider_a: &Collider,
        level_collider_a: Option<&LevelCollider>,
    ) -> Vec3 {
        if let Some(level_collider_a) = level_collider_a
            && level_collider_a.id.is_some()
        {
            let sable_data = self.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);

            let collider_info =
                &sable_data.level_colliders[&(level_collider_a.id.unwrap() as LevelColliderID)];

            if let Some(fake_velo) = collider_info.fake_velocities {
                let transform = collider_a.position();
                return transform.transform_vector(fake_velo.velocity_at_point(
                    transform.inverse_transform_point(contact.point),
                    Vec3::ZERO,
                ));
            };
        }
        Vec3::ZERO
    }

    fn handle_block_params(
        &self,
        isometry: &Pose,
        _collider: &Collider,
        level_collider: Option<&LevelCollider>,
        global_point: &Vec3,
        velocity: Real,
        manifold_index: usize,
        body_a: bool,
    ) -> (Vec3, bool, Real, Real) {
        let state = crate::get_physics_state();

        let (tangent_velo, center_of_mass, skip_contact_events) = {
            let sable_data = self.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
            let collider_info =
                level_collider.and_then(|lc| lc.id.map(|id| &sable_data.level_colliders[&(id)]));

            let mut tangent_velo = Vec3::ZERO;
            if let Some(fake_velo) = collider_info.and_then(|info| info.fake_velocities) {
                tangent_velo += isometry.transform_vector(fake_velo.velocity_at_point(
                    isometry.inverse_transform_point(*global_point),
                    Vec3::ZERO,
                ));
            }

            let center_of_mass = collider_info.map_or(DVec3::ZERO, |b| b.center_of_mass.unwrap());
            let skip_contact_events = collider_info.is_some_and(|info| info.has_own_chunks());

            (tangent_velo, center_of_mass, skip_contact_events)
        };

        let Some(manifold_info) = self.manifold_info_map.list.get(&manifold_index) else {
            return (tangent_velo, false, 1.0, 0.0);
        };

        let (block_coord, block_id, other_block_coord, has_other_block) = if body_a {
            (
                manifold_info.pos_a,
                manifold_info.col_a as u32,
                manifold_info.pos_b,
                manifold_info.col_b != 0,
            )
        } else {
            (
                manifold_info.pos_b,
                manifold_info.col_b as u32,
                manifold_info.pos_a,
                manifold_info.col_a != 0,
            )
        };

        let local = isometry.inverse_transform_point(*global_point);
        let block_coord_d: DVec3 = local.as_dvec3() + center_of_mass;

        if block_id == 0 {
            return (tangent_velo, false, 1.0, 0.0);
        }

        let voxel_collider_data = &state
            .voxel_collider_map
            .voxel_colliders
            .get((block_id - 1) as usize);
        let mut friction_multiplier = 1.0;
        let mut restitution = 0.0;

        if voxel_collider_data.is_none() {
            return (tangent_velo, false, friction_multiplier, restitution);
        }

        let voxel_collider_data = voxel_collider_data.unwrap().as_ref().unwrap();
        friction_multiplier *= voxel_collider_data.friction;
        restitution = voxel_collider_data.restitution;

        let Some(contact_events) = voxel_collider_data.contact_events.as_ref() else {
            return (tangent_velo, false, friction_multiplier, restitution);
        };

        if skip_contact_events {
            return (tangent_velo, false, friction_multiplier, restitution);
        }

        let Some(current_step_vm) = &self.current_step_vm else {
            panic!("No current step env!");
        };

        let Some(method) = &voxel_collider_data.contact_method else {
            panic!("No contact method!");
        };

        let args = &[
            JValue::Int(block_coord.x as jint),
            JValue::Int(block_coord.y as jint),
            JValue::Int(block_coord.z as jint),
            JValue::Int(other_block_coord.x as jint),
            JValue::Int(other_block_coord.y as jint),
            JValue::Int(other_block_coord.z as jint),
            JValue::Double(block_coord_d.x),
            JValue::Double(block_coord_d.y),
            JValue::Double(block_coord_d.z),
            JValue::Double(velocity as jdouble),
            JValue::Bool(has_other_block as jboolean),
        ];

        let args: Vec<jvalue> = args.iter().map(|v| v.as_jni()).collect();

        let mut env = current_step_vm.get_env().unwrap();
        let result =
            unsafe { env.call_method_unchecked(contact_events, method, ReturnType::Array, &args) }
                .unwrap();
        let arr = JDoubleArray::from(JObject::try_from(result).unwrap());

        let mut velo_arr: [jdouble; 4] = [0.0, 0.0, 0.0, 0.0];
        env.get_double_array_region(arr, 0, &mut velo_arr).unwrap();

        let velo = Vec3::new(
            velo_arr[0] as Real,
            velo_arr[1] as Real,
            velo_arr[2] as Real,
        );

        (
            tangent_velo + isometry.transform_vector(velo),
            velo_arr[3] > 0.0,
            friction_multiplier,
            restitution,
        )
    }
}
