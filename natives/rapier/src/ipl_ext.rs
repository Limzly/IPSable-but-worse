//! IPSable extensions to the sable_rapier natives (portal-physics spec phase 4),
//! rebased onto sable 2.0.3 (scene handles are raw `Arc<PhysicsScene>` pointers; sable
//! data lives behind `RwLock`, so hook-time reads are properly synchronized).
//!
//! Exposed under the `ipl.sable.natives.IplRapierNatives` Java class — sable's own JNI
//! surface is untouched.

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use std::collections::HashSet;
use marten::Real;
use rapier3d::dynamics::RigidBodyBuilder;
use rapier3d::geometry::{ColliderBuilder, SharedShape};
use rapier3d::glamx::{Pose3, Quat};
use rapier3d::math::Vec3;
use rapier3d::pipeline::PairFilterContext;
use rapier3d::prelude::{ActiveHooks, RigidBodyHandle};

use crate::scene::{LevelColliderID, PhysicsScene};

/// Native portal rim: four directly-authored Rapier cuboids, deliberately outside the
/// aperture. This bypasses Sable's voxel/neighborhood and chunk-octree machinery, which
/// has block-sized broad-phase cells even when a baked block shape is thin.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_createPortalRim<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    hole_width: jdouble,
    hole_height: jdouble,
    width: jdouble,
    half_thickness: jdouble,
) -> jint {
    if scene_handle == 0 || hole_width <= 0.0 || hole_height <= 0.0 || width <= 0.0
        || half_thickness <= 0.0 {
        return -1;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let width = width as Real;
    let half_thickness = half_thickness as Real;
    let hole_width = hole_width as Real;
    let hole_height = hole_height as Real;
    let half_w = hole_width * 0.5;
    let half_h = hole_height * 0.5;

    let bars = [
        (Vec3::new(-half_w - width * 0.5, 0.0, 0.0), width * 0.5, half_thickness, half_h + width),
        (Vec3::new(half_w + width * 0.5, 0.0, 0.0), width * 0.5, half_thickness, half_h + width),
        (Vec3::new(0.0, 0.0, -half_h - width * 0.5), half_w, half_thickness, width * 0.5),
        (Vec3::new(0.0, 0.0, half_h + width * 0.5), half_w, half_thickness, width * 0.5),
    ];

    let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let body = sim.rigid_body_set.insert(RigidBodyBuilder::kinematic_position_based());
    let crate::scene::SimulationSceneData { collider_set, rigid_body_set, .. } = &mut *sim;
    for (position, x, y, z) in bars {
        let collider = ColliderBuilder::new(SharedShape::cuboid(x, y, z))
            .position(Pose3::from_translation(position))
            .friction(0.45)
            .active_hooks(ActiveHooks::FILTER_CONTACT_PAIRS | ActiveHooks::MODIFY_SOLVER_CONTACTS)
            .collision_groups(crate::groups::level_group(scene.chart))
            .build();
        collider_set.insert_with_parent(collider, body, rigid_body_set);
    }
    // Collider handles are generational; keep an opaque native-side id instead of exposing
    // them to Java as a body id. A kinematic body is unique to exactly one rim.
    let id = next_portal_rim_id();
    drop(sim);
    let mut rims = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    rims.insert((scene_handle, id), PortalRim {
        body, exclusions: HashSet::new(), positioned: false,
    });
    id
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setPortalRimTransform<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    rim_id: jint,
    x: jdouble, y: jdouble, z: jdouble,
    qx: jdouble, qy: jdouble, qz: jdouble, qw: jdouble,
) {
    let Some((body_handle, first_pose)) = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner)
        .get_mut(&(scene_handle, rim_id))
        .map(|rim| {
            let first_pose = !rim.positioned;
            rim.positioned = true;
            (rim.body, first_pose)
        }) else {
        return;
    };
    if scene_handle == 0 { return; }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(body) = sim.rigid_body_set.get_mut(body_handle) {
        let pose = Pose3 {
            translation: Vec3::new(x as Real, y as Real, z as Real),
            rotation: Quat::from_xyzw(qx as Real, qy as Real, qz as Real, qw as Real).normalize(),
        };
        // Spawn directly at the aperture; later updates use Rapier's next pose so a
        // moving portal remains a genuine kinematic obstacle with correct velocity.
        if first_pose {
            body.set_position(pose, false);
        }
        body.set_next_kinematic_position(pose);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_removePortalRim<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>, scene_handle: jlong, rim_id: jint,
) {
    let Some(rim) = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&(scene_handle, rim_id)) else {
        return;
    };
    if scene_handle == 0 { return; }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    // Removing the parent body removes its attached compound collider too. Destructure
    // the scene first so Rust can prove these mutable fields do not overlap.
    let crate::scene::SimulationSceneData {
        rigid_body_set, island_manager, collider_set, impulse_joint_set,
        multibody_joint_set, ..
    } = &mut *sim;
    rigid_body_set.remove(rim.body, island_manager, collider_set,
        impulse_joint_set, multibody_joint_set, true);
}

static IPL_PORTAL_RIMS: std::sync::LazyLock<std::sync::RwLock<std::collections::HashMap<
    (jlong, jint), PortalRim
>>> = std::sync::LazyLock::new(|| std::sync::RwLock::new(std::collections::HashMap::new()));
static IPL_PORTAL_RIM_IDS: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(-2);

struct PortalRim {
    body: RigidBodyHandle,
    exclusions: HashSet<RigidBodyHandle>,
    positioned: bool,
}

fn next_portal_rim_id() -> jint {
    IPL_PORTAL_RIM_IDS.fetch_sub(1, std::sync::atomic::Ordering::Relaxed)
}

pub fn portal_rim_contact_allowed(context: &PairFilterContext) -> bool {
    let Some(body1) = context.rigid_body1 else { return true; };
    let Some(body2) = context.rigid_body2 else { return true; };
    IPL_PORTAL_RIMS.read().unwrap_or_else(std::sync::PoisonError::into_inner).values().all(|rim| {
        !((body1 == rim.body && rim.exclusions.contains(&body2))
            || (body2 == rim.body && rim.exclusions.contains(&body1)))
    })
}

fn set_portal_rim_exclusion(
    scene_handle: jlong, rim_id: jint, body_id: jint, excluded: jboolean,
) {
    if scene_handle == 0 || body_id < 0 { return; }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let Some(body) = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner).rigid_bodies
        .get(&(body_id as LevelColliderID)).copied() else { return; };
    let mut rims = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(rim) = rims.get_mut(&(scene_handle, rim_id)) else { return; };
    if excluded != 0 { rim.exclusions.insert(body); } else { rim.exclusions.remove(&body); }
}

/// An oriented clip volume: solver contacts past the plane (signed distance >= 0 along
/// `normal`) and inside the lateral rectangle are dropped from the owning body's manifolds.
/// Infinite half extents remain available to callers, but Atlas keeps both real and image
/// clipping bounded to the portal aperture so terrain beside a frame remains solid.
#[derive(Debug, Clone)]
pub struct IplClipRegion {
    pub point: Vec3,
    pub normal: Vec3,
    pub axis_w: Vec3,
    pub half_w: Real,
    pub axis_h: Vec3,
    pub half_h: Real,
    /// Tolerance BEFORE the plane. A contact whose point sits a hair on the near side of
    /// the cut while its geometry is already past it used to survive, which is what a
    /// player still "feels" when entering the doorway from the opposite side. The band is
    /// tiny (sub-millimetre by default) and does not widen the cut anywhere else.
    pub skin: Real,
    /// Depth bound BEYOND the plane. Previously the region was an infinite half-space, so
    /// it could reach arbitrarily far past the portal and neutralize contacts that had
    /// nothing to do with this crossing -- the "microscopically affects other sub-levels"
    /// symptom. Java sends the owning body's own diagonal, so the cutter is a closed box
    /// around exactly the volume that can be straddling.
    pub max_depth: Real,
}

/// Clip-region stride. The current payload is 16 doubles per region:
/// `[px py pz  nx ny nz  wx wy wz halfW  hx hy hz halfH  skin maxDepth]`.
/// The historical 14-double payload (no skin, unbounded depth) is still accepted so an
/// older Java side cannot hard-fail against a newer native.
#[inline]
fn ipl_clip_stride(len: usize) -> usize {
    if len != 0 && len % 16 == 0 { 16 } else { 14 }
}

impl IplClipRegion {
    #[inline]
    pub fn contains(&self, p: Vec3) -> bool {
        let rel = p - self.point;
        let depth = rel.dot(self.normal);
        depth >= -self.skin
            && depth <= self.max_depth
            && rel.dot(self.axis_w).abs() <= self.half_w
            && rel.dot(self.axis_h).abs() <= self.half_h
    }
}

/// Set (or clear, with an empty array) the clip regions of a body.
/// Layout: N regions x 14 doubles: [px py pz  nx ny nz  wx wy wz  halfW  hx hy hz  halfH].
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setClipRegions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    data: JDoubleArray<'local>,
) {
    if scene_handle == 0 {
        return;
    }
    let len = match env.get_array_length(&data) {
        Ok(l) => l as usize,
        Err(_) => return,
    };
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(&data, 0, &mut values).is_err() {
        return;
    }

    // Same handle-deref pattern as the upstream natives (with_handle).
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        // Diagnostic (stderr -> launcher log): a silent miss here means Java is
        // clipping a body id / scene pair that native doesn't know.
        eprintln!(
            "[ipl-natives] setClipRegions MISS: body {body_id} not in scene {scene_handle:x}"
        );
        return; // body already gone — nothing to clip
    };

    info.clip_regions.clear();
    for c in values.chunks_exact(ipl_clip_stride(values.len())) {
        info.clip_regions.push(IplClipRegion {
            point: Vec3::new(c[0] as Real, c[1] as Real, c[2] as Real),
            normal: Vec3::new(c[3] as Real, c[4] as Real, c[5] as Real),
            axis_w: Vec3::new(c[6] as Real, c[7] as Real, c[8] as Real),
            half_w: c[9] as Real,
            axis_h: Vec3::new(c[10] as Real, c[11] as Real, c[12] as Real),
            half_h: c[13] as Real,
            skin: if c.len() > 14 { c[14] as Real } else { 1.0e-3 },
            max_depth: if c.len() > 15 { c[15] as Real } else { Real::MAX },
        });
    }
    // No success log: straddle sessions re-push regions per tick — an unconditional
    // stderr write here is a server-thread tick cost. The MISS branch above stays.
}

/// Register (`excluded != 0`) or clear a contact exclusion between two bodies in one
/// scene. The dispatcher's dynamic-vs-dynamic path generates no manifolds for excluded
/// pairs (and drops persisted ones). Portal rims use this to exclude their carrier.
///
/// Defensive by design: no body-existence check (ids are just set keys), idempotent in
/// both directions, no-op on a null scene. Entries for despawned bodies are inert
/// (`nextBodyID` never reuses ids) but Java clears them on despawn anyway.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setBodyPairExclusion<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    id_a: jint,
    id_b: jint,
    excluded: jboolean,
) {
    if id_a < 0 {
        set_portal_rim_exclusion(scene_handle, id_a, id_b, excluded);
        return;
    }
    if id_b < 0 {
        set_portal_rim_exclusion(scene_handle, id_b, id_a, excluded);
        return;
    }
    if scene_handle == 0 || id_a == id_b || id_a < 0 || id_b < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let (a, b) = (id_a as LevelColliderID, id_b as LevelColliderID);
    let key = if a <= b { (a, b) } else { (b, a) };
    if excluded != 0 {
        sable_data.ipl_excluded_pairs.insert(key);
    } else {
        sable_data.ipl_excluded_pairs.remove(&key);
    }
}

/// Tag a hosted body's collider info with its parent-frame id. The dispatcher's
/// dynamic-vs-dynamic path drops native-vs-native manifolds between bodies whose
/// nonzero frames differ (see `ActiveLevelColliderInfo::ipl_parent_frame`); image
/// colliders are unaffected — their frame is the shape's chart, which the chart
/// guard already scopes. Java calls this only when a ship's parent dimension flips
/// (or its body/scene is recreated), not per tick.
///
/// Returns JNI_TRUE when the tag landed; JNI_FALSE when the body id is unknown in
/// this scene (registration race — Java retries next hosting tick).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setParentFrame<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    frame_id: jint,
) -> jboolean {
    if scene_handle == 0 || body_id < 0 {
        return 0;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return 0; // body not registered yet (or already gone) — caller retries
    };
    info.ipl_parent_frame = frame_id;
    1
}

/// Dormancy switch for a hosted body whose parent-pointer chunks are unloaded: a Fixed
/// body skips integration entirely (no gravity, immovable, still a valid joint/rope
/// anchor), so an unloaded-area ship cannot fall through terrain that was never baked.
/// Idempotent — Java re-applies it every tick while dormant, which also re-freezes a
/// body that was recreated (rehome twin) mid-dormancy. Velocities are zeroed on freeze
/// so the ship resumes at rest.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setBodyDormant<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    dormant: jboolean,
) {
    use rapier3d::prelude::RigidBodyType;

    if scene_handle == 0 || body_id < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let handle = {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let Some(handle) = sable_data
            .rigid_bodies
            .get(&(body_id as LevelColliderID))
            .copied()
        else {
            return;
        };
        handle
    };
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(body) = sim_data.rigid_body_set.get_mut(handle) else {
        return;
    };
    if dormant != 0 {
        if body.body_type() != RigidBodyType::Fixed {
            body.set_linvel(Vec3::new(0.0, 0.0, 0.0), false);
            body.set_angvel(Vec3::new(0.0, 0.0, 0.0), false);
            body.set_body_type(RigidBodyType::Fixed, true);
        }
    } else if body.body_type() != RigidBodyType::Dynamic {
        body.set_body_type(RigidBodyType::Dynamic, true);
    }
}

/// Sable body ids in the impulse-joint component containing `body_id`.
///
/// `rigid_only != 0`: traverse only joints that make a RIGID assembly — both endpoints
/// Sable ship bodies AND at least one ANGULAR axis locked (bearing/fixed/prismatic
/// couplings). This excludes flexible links regardless of construction: native rope
/// particle chains (endpoints aren't ship bodies) and rope/chain links built as
/// one-block sub-levels joined by ball-type joints (linear-only locks) or springs
/// (empty lock mask). Roped ships transit independently and the rope spans the portal;
/// only genuinely rigid assemblies cross as one unit.
///
/// `rigid_only == 0`: the full walk — every joint bridges.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_connectedSableBodyIds<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    rigid_only: jboolean,
) -> jni::objects::JIntArray<'local> {
    if scene_handle == 0 || body_id < 0 {
        return env.new_int_array(0).unwrap();
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(start) = sable_data.rigid_bodies.get(&(body_id as LevelColliderID)).copied() else {
        return env.new_int_array(0).unwrap();
    };
    let sable_handles: HashSet<_> = sable_data.rigid_bodies.values().copied().collect();
    let sim = scene.sim_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut connected = HashSet::from([start]);
    loop {
        let mut changed = false;
        for (_, joint) in sim.impulse_joint_set.iter() {
            if joint.body1 == scene.world.ground_handle || joint.body2 == scene.world.ground_handle {
                continue;
            }
            if rigid_only != 0 {
                if !sable_handles.contains(&joint.body1) || !sable_handles.contains(&joint.body2) {
                    continue;
                }
                // Rope/chain links can be sub-levels too: a ball-type link (linear-only
                // locks) or spring (empty mask) is flexible, not rigid. Rigid couplings
                // (bearing = revolute, fixed) always lock at least one angular axis.
                if !joint
                    .data
                    .locked_axes
                    .intersects(rapier3d::prelude::JointAxesMask::ANG_AXES)
                {
                    continue;
                }
            }
            if connected.contains(&joint.body1) && connected.insert(joint.body2) {
                changed = true;
            }
            if connected.contains(&joint.body2) && connected.insert(joint.body1) {
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    let mut ids: Vec<jint> = sable_data.rigid_bodies.iter()
        .filter_map(|(id, handle)| connected.contains(handle).then_some(*id as jint))
        .collect();
    ids.sort_unstable();
    let result = env.new_int_array(ids.len() as i32).unwrap();
    if !ids.is_empty() {
        env.set_int_array_region(&result, 0, &ids).unwrap();
    }
    result
}

// ---------------------------------------------------------------------------
// Atlas M2 (spec v3 §2.2): image colliders — the body's geometry projected into
// a far chart through a translation-only portal isometry (Tier 1). Contacts on
// an image act on the parent body EXACTLY via the engine's mapped-COM lever
// arms; there is no clone body, no servo, no feedback.
// ---------------------------------------------------------------------------

/// Create an image collider for `body_id` in the CALLING view's chart, with the
/// portal isometry `P = (R, t)`: translation `(dx, dy, dz)` and rotation quat
/// `(qx, qy, qz, qw)` (identity for translation-only portals — Tier 1). The
/// prefix maps the body's pose into the far chart. Returns the packed collider
/// handle (index << 32 | generation), or -1 if the body is unknown.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_createImageCollider<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) -> jlong {
    use rapier3d::prelude::*;

    if scene_handle == 0 || body_id < 0 {
        return -1;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = &mut *sim_data;

    let Some(body_handle) = sable_data.rigid_bodies.get(&(body_id as LevelColliderID)).copied() else {
        eprintln!("[ipl-natives] createImageCollider MISS: body {body_id} unknown");
        return -1;
    };
    let Some(info) = sable_data.level_colliders.get_mut(&(body_id as LevelColliderID)) else {
        eprintln!("[ipl-natives] createImageCollider MISS: body {body_id} unknown");
        return -1;
    };

    // Mirror the native collider's shape (same id → same info lookups in the
    // hooks/dispatcher), but tagged with the CALLING chart so it pairs with the
    // far chart's terrain and bodies.
    let native = sim_data
        .collider_set
        .get(info.collider)
        .and_then(|c| c.shape().as_shape::<crate::collider::LevelCollider>())
        .copied();
    let Some(native_shape) = native else {
        eprintln!("[ipl-natives] createImageCollider: body {body_id} has no LevelCollider shape");
        return -1;
    };
    let image_shape = crate::collider::LevelCollider {
        chart: scene.chart,
        ..native_shape
    };

    let prefix = rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
        )
        .normalize(),
    };
    let collider = ColliderBuilder::new(SharedShape::new(image_shape))
        .friction(0.525)
        .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
        .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS)
        .density(0.0)
        .collision_groups(crate::groups::image_group(scene.chart))
        .position(rapier3d::math::Pose::IDENTITY)
        .build();

    let handle =
        sim_data
            .collider_set
            .insert_with_parent(collider, body_handle, &mut sim_data.rigid_body_set);
    sim_data.collider_set.get_mut(handle).unwrap().set_portal_prefix(Some(prefix));
    let parent_pose = sim_data.rigid_body_set[body_handle].position().clone();
    sim_data.collider_set.get_mut(handle).unwrap()
        .refresh_portal_prefixed_pose(&parent_pose);
    // `insert_with_parent` composes before the Atlas prefix is attached. Recompose now so
    // this image never enters broad phase at identity and leaves a one-step real/image twin.

    info.image_colliders.push(handle);
    eprintln!(
        "[ipl-natives] image collider created: body {body_id} chart {} shift ({dx:.1},{dy:.1},{dz:.1})",
        scene.chart
    );

    let (idx, generation) = handle.into_raw_parts();
    ((idx as jlong) << 32) | (generation as jlong)
}

/// Remove an image collider previously created by `createImageCollider`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_removeImageCollider<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = &mut *sim_data;

    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );

    if let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    {
        info.image_colliders.retain(|h| *h != handle);
        info.image_clip.remove(&handle);
    }

    sim_data.collider_set.remove(
        handle,
        &mut sim_data.island_manager,
        &mut sim_data.rigid_body_set,
        true,
    );
}

/// Set (or clear, with an empty array) the clip regions of one IMAGE collider —
/// the far side of the half-open aperture seam. Layout matches `setClipRegions`
/// (N x 14 doubles).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setImageClipRegions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
    data: JDoubleArray<'local>,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let len = match env.get_array_length(&data) {
        Ok(l) => l as usize,
        Err(_) => return,
    };
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(&data, 0, &mut values).is_err() {
        return;
    }

    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return;
    };

    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );
    let mut regions = Vec::with_capacity(values.len() / 14);
    for c in values.chunks_exact(ipl_clip_stride(values.len())) {
        regions.push(IplClipRegion {
            point: Vec3::new(c[0] as Real, c[1] as Real, c[2] as Real),
            normal: Vec3::new(c[3] as Real, c[4] as Real, c[5] as Real),
            axis_w: Vec3::new(c[6] as Real, c[7] as Real, c[8] as Real),
            half_w: c[9] as Real,
            axis_h: Vec3::new(c[10] as Real, c[11] as Real, c[12] as Real),
            half_h: c[13] as Real,
            skin: if c.len() > 14 { c[14] as Real } else { 1.0e-3 },
            max_depth: if c.len() > 15 { c[15] as Real } else { Real::MAX },
        });
    }
    if regions.is_empty() {
        info.image_clip.remove(&handle);
    } else {
        info.image_clip.insert(handle, regions);
    }
}

/// Atlas M5 (spec v3 §2.8): update an image collider's portal prefix — moving
/// portals re-derive P = (R, t) per tick (animated portals, portals anchored to
/// physics structures). The engine recomposes the collider pose and re-runs
/// broad/narrow phase from the PARENT change flag.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipl_sable_natives_IplRapierNatives_setImagePrefix<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    packed_handle: jlong,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );
    let Some(collider) = sim_data.collider_set.get_mut(handle) else {
        return; // image already retired — a moving-portal refresh racing session end
    };
    collider.set_portal_prefix(Some(rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
        )
        .normalize(),
    }));
}
