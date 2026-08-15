pub mod algo;
mod boxes;
mod buoyancy;
mod collider;
mod config;
mod contraptions;
mod dispatcher;
mod event_handler;
mod groups;
mod hooks;
pub mod ipl_ext;
mod joints;
mod rope;
mod scene;
mod voxel_collider;

use jni::objects::{JClass, JDoubleArray, JIntArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use jni::{JNIEnv, JavaVM};
use rapier3d::glamx::{DVec3, Quat};
use std::collections::HashMap;
use std::sync::{Arc, OnceLock, RwLock};

use fern::colors::{Color, ColoredLevelConfig};
use log::info;

use crate::buoyancy::compute_buoyancy;
use crate::collider::{LevelCollider, update_collider_aabb};
use crate::dispatcher::SableDispatcher;
use crate::event_handler::SableEventHandler;
use crate::glamx::IVec3;
use crate::joints::SableJointSet;
use crate::rope::RopeMap;
use crate::scene::{
    ChunkAccess, ChunkMap, SableManifoldInfoMap, SableSceneData, SimulationSceneData,
    pack_section_pos,
};
use crate::voxel_collider::VoxelColliderMap;
use hooks::SablePhysicsHooks;
use marten::Real;
use marten::level::VoxelPhysicsState::Interior;
use marten::level::{
    ALL_VOXEL_PHYSICS_STATES, BlockState, CHUNK_SHIFT, ChunkSection, OCTREE_CHUNK_SHIFT,
    OCTREE_CHUNK_SIZE, OctreeChunkSection, VoxelPhysicsState,
};
use marten::octree::SubLevelOctree;
use rapier3d::parry::query::{DefaultQueryDispatcher, QueryDispatcher};
use rapier3d::prelude::*;
use scene::{LevelColliderID, PhysicsScene, ReportedCollisionBuffer};

#[derive(Debug)]
pub struct ActiveLevelColliderInfo {
    pub collider: ColliderHandle,
    /// IPL atlas: which chart (dimension frame) this body lives in. Drives chunk-map
    /// selection in the dispatcher, buoyancy scoping, and collision-report routing.
    pub chart: scene::ChartId,
    /// IPL atlas: parent-frame id of a hosted body. Real hosted bodies share the
    /// hosting chart at their own parents' coordinates, so numeric overlap between
    /// bodies with different parents is meaningless — the dispatcher's dynamic pair
    /// path drops native-vs-native manifolds when nonzero frames differ. 0 = untagged
    /// (non-hosted bodies, contraptions, boot window): collides with everything,
    /// matching pre-tag behavior. Set from Java on parent flips (`setParentFrame`).
    pub ipl_parent_frame: i32,
    pub static_mount: Option<RigidBodyHandle>,
    pub fake_velocities: Option<RigidBodyVelocity<Real>>,
    pub local_bounds_min: Option<IVec3>,
    pub local_bounds_max: Option<IVec3>,
    pub center_of_mass: Option<DVec3>,
    pub octree: Option<SubLevelOctree>,
    pub chunk_map: Option<ChunkMap>,
    /// IPSable: aperture clip volumes (spec §2.5) — contacts inside any of these are
    /// dropped from this body's manifolds in `modify_solver_contacts`.
    pub clip_regions: Vec<crate::ipl_ext::IplClipRegion>,
    /// Atlas M2: image colliders of this body (extra colliders with a portal
    /// prefix, colliding in far charts). Tracked for removal and AABB refresh.
    pub image_colliders: Vec<ColliderHandle>,
    /// Atlas M2: per-image-collider clip regions (the far side keeps d >= 0 while
    /// the native set keeps d < 0 — one body, two region sets). The clip hook
    /// selects by collider handle, falling back to `clip_regions`.
    pub image_clip: HashMap<ColliderHandle, Vec<crate::ipl_ext::IplClipRegion>>,
}

impl ChunkAccess for ActiveLevelColliderInfo {
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection> {
        self.chunk_map
            .as_mut()
            .unwrap()
            .get_mut(&pack_section_pos(x, y, z))
    }

    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection> {
        self.chunk_map
            .as_ref()
            .unwrap()
            .get(&pack_section_pos(x, y, z))
    }
}

impl ActiveLevelColliderInfo {
    /// Creates a new handle for a sable object with rigidbody and collider handles
    #[must_use]
    pub fn new(collider: ColliderHandle, chart: scene::ChartId) -> Self {
        Self {
            collider,
            chart,
            ipl_parent_frame: 0,
            static_mount: None,
            fake_velocities: None,
            chunk_map: None,
            local_bounds_min: None,
            local_bounds_max: None,
            center_of_mass: None,
            octree: None,
            clip_regions: Vec::new(),
            image_colliders: Vec::new(),
            image_clip: HashMap::new(),
        }
    }

    pub fn has_own_chunks(&self) -> bool {
        self.chunk_map.is_some()
    }

    /// Sets the local bounds for the object
    pub fn set_local_bounds(
        &mut self,
        min: IVec3,
        max: IVec3,
        level_chunks: &ChunkMap,
        collider_map: &VoxelColliderMap,
    ) {
        if Some(min) != self.local_bounds_min || Some(max) != self.local_bounds_max {
            self.local_bounds_min = Some(min);
            self.local_bounds_max = Some(max);

            let max_axis = (max - min).max_element() as u32 + 1;
            let smallest_pow_2_above = max_axis.next_power_of_two();

            let chunk_min = min >> CHUNK_SHIFT;
            let chunk_max = max >> CHUNK_SHIFT;

            self.octree = Some(SubLevelOctree::new(
                smallest_pow_2_above.trailing_zeros() as i32
            ));

            let has_own_chunks = self.has_own_chunks();

            for cx in chunk_min.x..=chunk_max.x {
                for cy in chunk_min.y..=chunk_max.y {
                    for cz in chunk_min.z..=chunk_max.z {
                        let chunk = if has_own_chunks {
                            self.chunk_map
                                .as_ref()
                                .unwrap()
                                .get(&pack_section_pos(cx, cy, cz))
                        } else {
                            level_chunks.get(&pack_section_pos(cx, cy, cz))
                        };

                        if let Some(chunk_section) = chunk {
                            for x in 0..16 {
                                for y in 0..16 {
                                    for z in 0..16 {
                                        let block_owned = chunk_section.get_block(x, y, z);
                                        if block_owned.1 == VoxelPhysicsState::Empty {
                                            continue;
                                        }

                                        insert_block_octree(
                                            collider_map,
                                            self.octree.as_mut().unwrap(),
                                            &block_owned,
                                            false,
                                            (x + (cx << CHUNK_SHIFT)) - min.x,
                                            (y + (cy << CHUNK_SHIFT)) - min.y,
                                            (z + (cz << CHUNK_SHIFT)) - min.z,
                                        );
                                    }
                                }
                            }
                        }
                        // let chunk = scene.main_level_chunks.get(&pack_section_pos(cx, cy, cz));

                        // if let Some(chunk) = chunk {
                        //     self.insert_chunk(chunk, cx, cy, cz);
                        // }
                    }
                }
            }
        }
        self.local_bounds_min = Some(min);
        self.local_bounds_max = Some(max);
    }

    fn insert_chunk(
        &mut self,
        chunk_section: &ChunkSection,
        cx: i32,
        cy: i32,
        cz: i32,
        collider_map: &VoxelColliderMap,
    ) {
        for x in 0..16 {
            for y in 0..16 {
                for z in 0..16 {
                    self.insert_block(
                        x + (cx << CHUNK_SHIFT),
                        y + (cy << CHUNK_SHIFT),
                        z + (cz << CHUNK_SHIFT),
                        &chunk_section.get_block(x, y, z),
                        false,
                        collider_map,
                    );
                }
            }
        }
    }

    fn insert_block(
        &mut self,
        x: i32,
        y: i32,
        z: i32,
        state: &BlockState,
        remove: bool,
        collider_map: &VoxelColliderMap,
    ) {
        let local_min = self.local_bounds_min.unwrap();
        let x = x - local_min.x;
        let y = y - local_min.y;
        let z = z - local_min.z;

        let Some(octree) = &mut self.octree else {
            panic!("No octree!");
        };
        insert_block_octree(collider_map, octree, state, remove, x, y, z);
    }

    fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        if self.local_bounds_min.is_none() || self.local_bounds_max.is_none() {
            return false;
        }

        let local_min = self.local_bounds_min.unwrap();
        let local_max = self.local_bounds_max.unwrap();

        x >= local_min.x
            && x <= local_max.x
            && y >= local_min.y
            && y <= local_max.y
            && z >= local_min.z
            && z <= local_max.z
    }
}

/// Global physics engine state shared across all scenes.
pub struct PhysicsState {
    /// The integration parameters, updated every time-step
    integration_parameters: IntegrationParameters,

    /// An array of i32 IDs -> block collider entries
    voxel_collider_map: VoxelColliderMap,
}

/// A collision to report to the Java side.
#[derive(Debug, Clone)]
pub struct ReportedCollision {
    body_a: Option<LevelColliderID>,
    body_b: Option<LevelColliderID>,
    local_point_a: DVec3,
    local_point_b: DVec3,
    local_normal_a: DVec3,
    local_normal_b: DVec3,
    force_amount: f64,
}

pub static PHYSICS_STATE: OnceLock<RwLock<PhysicsState>> = OnceLock::new();

pub fn with_handle<F, R>(handle: jlong, f: F) -> R
where
    F: FnOnce(&PhysicsScene) -> R,
{
    assert!(handle != 0, "null scene handle");
    unsafe { f(&*(handle as *const PhysicsScene)) }
}

#[inline(always)]
pub fn get_physics_state() -> std::sync::RwLockReadGuard<'static, PhysicsState> {
    PHYSICS_STATE
        .get()
        .expect("No physics state!")
        .read()
        .unwrap()
}

#[inline(always)]
pub fn get_physics_state_mut() -> std::sync::RwLockWriteGuard<'static, PhysicsState> {
    PHYSICS_STATE
        .get()
        .expect("No physics state!")
        .write()
        .unwrap()
}

#[inline(always)]
pub fn get_rigid_body_mut<'a>(
    sim: &'a mut SimulationSceneData,
    sable_data: &SableSceneData,
    id: LevelColliderID,
) -> &'a mut RigidBody {
    let handle = sable_data
        .rigid_bodies
        .get(&id)
        .expect("No rigid body for id");
    &mut sim.rigid_body_set[*handle]
}

#[inline(always)]
pub fn get_rigid_body<'a>(
    sim: &'a SimulationSceneData,
    sable_data: &SableSceneData,
    id: LevelColliderID,
) -> &'a RigidBody {
    let handle = sable_data
        .rigid_bodies
        .get(&id)
        .expect("No rigid body for id");
    &sim.rigid_body_set[*handle]
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_initialize<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    universal_drag: jdouble,
) -> jlong {
    PHYSICS_STATE.get_or_init(|| {
        let colors = ColoredLevelConfig::new()
            .info(Color::Green)
            .error(Color::Red)
            .debug(Color::Blue);

        let _ = fern::Dispatch::new()
            .format(move |out, message, record| {
                out.finish(format_args!(
                    "[{}] [{}] ({}) {}",
                    humantime::format_rfc3339(std::time::SystemTime::now()),
                    colors.color(record.level()),
                    record.target(),
                    message
                ))
            })
            .level(log::LevelFilter::Info)
            .level_for("jni", log::LevelFilter::Error)
            .chain(std::io::stdout())
            .apply();

        RwLock::new(PhysicsState {
            integration_parameters: IntegrationParameters {
                dt: 1.0 / 20.0,

                max_ccd_substeps: 3,
                normalized_prediction_distance: 0.005,

                contact_softness: SpringCoefficients {
                    natural_frequency: 30.0,
                    damping_ratio: 5.0,
                },

                normalized_max_corrective_velocity: 50.0,
                normalized_allowed_linear_error: 0.0025,

                ..IntegrationParameters::default()
            },
            voxel_collider_map: VoxelColliderMap::new(),
        })
    });

    let gravity = Vec3::new(x as Real, y as Real, z as Real);
    let current_step_vm = Some(Arc::new(unsafe {
        JavaVM::from_raw(env.get_java_vm().unwrap().get_java_vm_pointer()).unwrap()
    }));

    // IPL atlas (spec v3 §2.1): all "scenes" are chart views of ONE world. The first
    // initialize creates the world (sim sets, terrain collider, ground body); every
    // call mints a new chart with its own report buffer. The registry holds a Weak so
    // the world dies with its last view (server restart in one JVM gets a fresh world).
    let mut registry = ipl_world_registry().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    let world = if let Some(world) = registry.upgrade() {
        if (world.world_gravity - gravity).length() > 1e-6 {
            info!(
                "IPL atlas: chart gravity {:?} differs from world gravity {:?} — the world \
                 steps with the FIRST chart's gravity (assert-uniform, spec v3 §5)",
                gravity, world.world_gravity
            );
        }
        world
    } else {
        let sable_data = Arc::new(RwLock::new(SableSceneData {
            charts: HashMap::new(),
            joint_set: SableJointSet::new(),
            rope_map: RopeMap::default(),
            level_colliders: HashMap::<LevelColliderID, ActiveLevelColliderInfo>::new(),
            rigid_bodies: HashMap::<LevelColliderID, RigidBodyHandle>::new(),
            bodies_by_chart: HashMap::<scene::ChartId, std::collections::HashSet<LevelColliderID>>::new(),
            ipl_excluded_pairs: std::collections::HashSet::new(),
        }));
        let manifold_info_map = Arc::new(SableManifoldInfoMap::default());
        let chart_buffers: Arc<scene::ChartBuffers> = Arc::new(scene::ChartBuffers::default());

        let dispatcher = SableDispatcher {
            sable_data: Arc::clone(&sable_data),
            manifold_info_map: Arc::clone(&manifold_info_map),
        };

        let sim_data = Arc::new(RwLock::new(SimulationSceneData {
            pipeline: PhysicsPipeline::new(),
            rigid_body_set: RigidBodySet::new(),
            collider_set: ColliderSet::new(),
            island_manager: IslandManager::new(),
            broad_phase: DefaultBroadPhase::new(),
            narrow_phase: NarrowPhase::with_query_dispatcher(
                dispatcher.chain(DefaultQueryDispatcher),
            ),
            impulse_joint_set: ImpulseJointSet::new(),
            multibody_joint_set: MultibodyJointSet::new(),
            ccd_solver: CCDSolver::new(),
            physics_hooks: SablePhysicsHooks {
                sable_data: Arc::clone(&sable_data),
                manifold_info_map: Arc::clone(&manifold_info_map),
                current_step_vm: current_step_vm.clone(),
            },
            event_handler: SableEventHandler {
                sable_data: Arc::clone(&sable_data),
                chart_buffers: Arc::clone(&chart_buffers),
            },
        }));

        let ground_handle = {
            let mut sim = sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
            sim.rigid_body_set.insert(RigidBodyBuilder::fixed())
        };

        let world = Arc::new(scene::WorldCore {
            sim_data,
            sable_data,
            manifold_info_map,
            chart_buffers,
            ground_handle,
            world_gravity: gravity,
            next_chart: std::sync::atomic::AtomicUsize::new(0),
        });
        *registry = Arc::downgrade(&world);
        info!("IPL atlas: world created");
        world
    };
    drop(registry);

    let chart = world
        .next_chart
        .fetch_add(1, std::sync::atomic::Ordering::Relaxed) as scene::ChartId;
    let reported_collisions = Arc::new(ReportedCollisionBuffer::new());
    world
        .chart_buffers
        .insert(chart, Arc::clone(&reported_collisions));
    {
        // Pre-create the chart's storage so read paths see an (empty) chart.
        world.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner).chart_mut(chart);
    }

    // Per-chart static terrain collider: the shape carries the chart id (the
    // dispatcher's terrain-vs-convex path only sees shapes), and its groups make
    // cross-chart terrain pairs impossible.
    let terrain_collider = {
        let mut sim = world.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let terrain =
            ColliderBuilder::new(SharedShape::new(LevelCollider::new(None, true, chart)))
                .collision_groups(groups::terrain_group(chart))
                .build();
        sim.collider_set.insert(terrain)
    };

    let scene = PhysicsScene {
        terrain_collider: Some(terrain_collider),
        sim_data: Arc::clone(&world.sim_data),
        sable_data: Arc::clone(&world.sable_data),
        manifold_info_map: Arc::clone(&world.manifold_info_map),
        ground_handle: Some(world.ground_handle),
        world,
        reported_collisions,
        current_step_vm,
        gravity,
        universal_drag: universal_drag as Real,
        chart,
    };

    info!("Rapier scene initialized (atlas chart {chart})");
    Arc::into_raw(Arc::new(scene)) as jlong
}

/// Registry of the singleton world (Weak — the world lives exactly as long as its
/// chart views).
fn ipl_world_registry() -> &'static std::sync::Mutex<std::sync::Weak<scene::WorldCore>> {
    static REGISTRY: OnceLock<std::sync::Mutex<std::sync::Weak<scene::WorldCore>>> =
        OnceLock::new();
    REGISTRY.get_or_init(|| std::sync::Mutex::new(std::sync::Weak::new()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_dispose<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            let scene = Arc::from_raw(handle as *const PhysicsScene);
            // IPL atlas: retire the chart — unregister its report buffer, remove its
            // terrain collider, and drop its storage so a dead chart can't receive
            // routed records or leak chunks. Bodies are removed by Java via
            // removeSubLevel before dispose (stock flow).
            scene.world.chart_buffers.remove(&scene.chart);
            if let Some(terrain) = scene.terrain_collider {
                let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
                let sim = &mut *sim;
                sim.collider_set.remove(
                    terrain,
                    &mut sim.island_manager,
                    &mut sim.rigid_body_set,
                    false,
                );
            }
            scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner).charts.remove(&scene.chart);
            drop(scene);
        }
    }
}

/// Extracts a message from a caught panic payload
fn panic_message(payload: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

/// Catches a panic and throws a JVM RuntimeException with the panic message
fn throw_on_panic(env: &mut JNIEnv, result: Result<(), Box<dyn std::any::Any + Send>>) {
    if let Err(payload) = result {
        let msg = format!("Rapier native panic: {}", panic_message(&payload));
        let _ = env.throw_new("java/lang/RuntimeException", &msg);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_tick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    _time_step: jdouble,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        with_handle(handle, |scene| {
            rope::tick(scene);
            joints::tick(scene);
            compute_buoyancy(scene);
        });
    }));

    throw_on_panic(&mut env, result);
}

/// Steps physics
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_step<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    time_step: jdouble,
) {
    get_physics_state_mut().integration_parameters.dt = time_step as Real;

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        with_handle(handle, |scene| {
            rope::tick(scene);
            joints::tick(scene);

            scene.manifold_info_map.clear();

            // Atlas: the world steps with the world gravity (assert-uniform, v3 §5).
            let gravity = scene.world.world_gravity;
            let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
            let sim = &mut *sim;

            let params = get_physics_state();
            let params = &params.integration_parameters;

            sim.pipeline.step(
                gravity,
                params,
                &mut sim.island_manager,
                &mut sim.broad_phase,
                &mut sim.narrow_phase,
                &mut sim.rigid_body_set,
                &mut sim.collider_set,
                &mut sim.impulse_joint_set,
                &mut sim.multibody_joint_set,
                &mut sim.ccd_solver,
                &sim.physics_hooks,
                &sim.event_handler,
            );
        });
    }));

    throw_on_panic(&mut env, result);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_getPose<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    store: JDoubleArray<'local>,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = scene.sim_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);

        let Some(ipl_body_handle) = sable_data.rigid_bodies.get(&(id as LevelColliderID)).copied() else {
            return; // IPL hardening: body gone - never panic across JNI
        };
        let Some(rb) = sim_data.rigid_body_set.get(ipl_body_handle) else {
            return;
        };

        let arr: [jdouble; 7] = [
            rb.translation().x as jdouble,
            rb.translation().y as jdouble,
            rb.translation().z as jdouble,
            rb.rotation().x as jdouble,
            rb.rotation().y as jdouble,
            rb.rotation().z as jdouble,
            rb.rotation().w as jdouble,
        ];

        env.set_double_array_region(&store, 0, &arr).unwrap();
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_setCenterOfMass<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) {
    with_handle(handle, |scene| {
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let info = sable_data
            .level_colliders
            .get_mut(&(id as LevelColliderID))
            .unwrap();
        info.center_of_mass = Some(DVec3::new(x, y, z));
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        update_collider_aabb(&mut sim_data, info);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_setLocalBounds<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    min_x: jint,
    min_y: jint,
    min_z: jint,
    max_x: jint,
    max_y: jint,
    max_z: jint,
) {
    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let SableSceneData {
            level_colliders,
            charts,
            ..
        } = &mut *sable_data;

        let info = level_colliders.get_mut(&(id as LevelColliderID)).unwrap();
        // Atlas: read the body's sections from ITS chart's map (the body may live in
        // a different chart than the calling view during clone feeds).
        let chart_chunks = &charts.entry(info.chart).or_default().main_level_chunks;
        info.set_local_bounds(
            IVec3::new(min_x, min_y, min_z),
            IVec3::new(max_x, max_y, max_z),
            chart_chunks,
            collider_map,
        );
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        update_collider_aabb(&mut sim_data, info);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_createSubLevel<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    pose: JDoubleArray<'local>,
) {
    let mut pose_arr: [jdouble; 7] = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0];
    env.get_double_array_region(pose, 0, &mut pose_arr).unwrap();

    let quat = Quat::from_xyzw(
        pose_arr[3] as Real,
        pose_arr[4] as Real,
        pose_arr[5] as Real,
        pose_arr[6] as Real,
    );

    let mut rigid_body = RigidBodyBuilder::dynamic()
        .ccd_enabled(true)
        .translation(Vec3::new(
            pose_arr[0] as Real,
            pose_arr[1] as Real,
            pose_arr[2] as Real,
        ))
        .build();
    rigid_body.set_rotation(quat, false);
    let activation_params = rigid_body.activation_mut();
    activation_params.angular_threshold = 0.15;
    activation_params.normalized_linear_threshold = 0.15;

    with_handle(handle, |scene| {
        rigid_body.set_linear_damping(scene.universal_drag);
        rigid_body.set_angular_damping(scene.universal_drag);
        rigid_body.enable_gyroscopic_forces(true);

        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = &mut *sim_data;
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let handle = sim_data.rigid_body_set.insert(rigid_body);

        // make a level collider
        let collider = ColliderBuilder::new(SharedShape::new(LevelCollider::new(
            Some(id as LevelColliderID),
            false,
            scene.chart,
        )))
        .friction(0.525)
        .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
        .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS)
        .density(0.0)
        .collision_groups(groups::level_group(scene.chart))
        .build();

        let collider_handle = sim_data.collider_set.insert_with_parent(
            collider,
            handle,
            &mut sim_data.rigid_body_set,
        );

        sable_data.level_colliders.insert(
            id as LevelColliderID,
            ActiveLevelColliderInfo::new(collider_handle, scene.chart),
        );

        sable_data
            .rigid_bodies
            .insert(id as LevelColliderID, handle);
        sable_data
            .bodies_by_chart
            .entry(scene.chart)
            .or_default()
            .insert(id as LevelColliderID);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_removeSubLevel<
    'local,
>(
    mut _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
) {
    with_handle(handle, |scene| {
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let body_id = id as LevelColliderID;
        if let Some(info) = sable_data.level_colliders.remove(&body_id) {
            if let Some(ids) = sable_data.bodies_by_chart.get_mut(&info.chart) {
                ids.remove(&body_id);
            }
        }
        // Java-side cleanup can race scene teardown or repeat after a body was already
        // removed during transit. JNI panics cannot unwind into Java, so an absent body
        // must mean "already cleaned up", not abort the process.
        let Some(handle) = sable_data.rigid_bodies.remove(&body_id) else {
            return;
        };

        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let sim_data = &mut *sim_data;
        let rigid_body_set = &mut sim_data.rigid_body_set;
        let island_manager = &mut sim_data.island_manager;
        let collider_set = &mut sim_data.collider_set;
        let impulse_joint_set = &mut sim_data.impulse_joint_set;
        let multibody_joint_set = &mut sim_data.multibody_joint_set;

        rigid_body_set.remove(
            handle,
            island_manager,
            collider_set,
            impulse_joint_set,
            multibody_joint_set,
            true,
        );
    })
}

pub fn insert_block_octree(
    collider_map: &VoxelColliderMap,
    octree: &mut SubLevelOctree,
    state: &BlockState,
    remove: bool,
    x: i32,
    y: i32,
    z: i32,
) {
    let block_collider_id = state.0;
    let block_collider = if block_collider_id > 0 {
        Some(
            collider_map
                .voxel_colliders
                .get(block_collider_id as usize - 1)
                .unwrap(),
        )
    } else {
        None
    };
    let voxel_state = state.1;

    let solid = voxel_state != Interior
        && voxel_state != VoxelPhysicsState::Empty
        && (block_collider_id > 0
            && !block_collider
                .unwrap()
                .as_ref()
                .unwrap()
                .collision_boxes
                .is_empty());

    if remove && !solid {
        octree.insert(x, y, z, -1);
    }

    if solid {
        octree.insert(x, y, z, block_collider_id as i32);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_addChunk<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    data: JIntArray<'local>,
    global: jboolean,
    object_id: jint,
) {
    let mut ints: [jint; 4096] = [0; 4096];
    env.get_int_array_region(data, 0, &mut ints).unwrap();

    let mut blocks = Vec::with_capacity(ints.len());

    for block in ints {
        // split it in half
        let block_collider_id = (block >> 16) as u16;
        let voxel_state_id = (block & 0xFFFF) as u16;

        blocks.push((
            block_collider_id as u32,
            ALL_VOXEL_PHYSICS_STATES[voxel_state_id as usize],
        ));
    }

    let chunk = ChunkSection::new(blocks);

    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let SableSceneData {
            charts,
            level_colliders,
            ..
        } = &mut *sable_data;
        // Atlas: writes land in the calling view's chart.
        let scene::ChartData {
            main_level_chunks,
            octree_chunks,
            ..
        } = charts.entry(scene.chart).or_default();

        // Kinematic contraptions own private section storage because they have no Java-side
        // plot. Atlas image colliders always read their real body's chart storage.
        if global == 0 && object_id != -1 {
            // Defensive: a feed racing a despawned body must not panic across JNI. Missing
            // bodies fall through to the shared path, preserving stock behavior.
            if let Some(body) = level_colliders.get_mut(&(object_id as LevelColliderID)) {
                if body.has_own_chunks() {
                    body.insert_chunk(&chunk, x, y, z, collider_map);
                    body.chunk_map
                        .as_mut()
                        .unwrap()
                        .insert(pack_section_pos(x, y, z), chunk);
                    return;
                }
            }
        }

        main_level_chunks.insert(pack_section_pos(x, y, z), chunk);
        let chunk = main_level_chunks.get(&pack_section_pos(x, y, z)).unwrap();
        if global == 0 {
            if object_id != -1 {
                let body = level_colliders
                    .get_mut(&(object_id as LevelColliderID))
                    .unwrap();
                body.insert_chunk(chunk, x, y, z, collider_map);
            }
        } else {
            for bx in 0..16 {
                for by in 0..16 {
                    for bz in 0..16 {
                        let block = chunk.get_block(bx, by, bz);
                        let x = bx + (x << CHUNK_SHIFT);
                        let y = by + (y << CHUNK_SHIFT);
                        let z = bz + (z << CHUNK_SHIFT);

                        // insert into level octree
                        let ox = x >> OCTREE_CHUNK_SHIFT;
                        let oy = y >> OCTREE_CHUNK_SHIFT;
                        let oz = z >> OCTREE_CHUNK_SHIFT;

                        let mut octree_chunk = octree_chunks.get_mut(&pack_section_pos(ox, oy, oz));

                        if octree_chunk.is_none() {
                            octree_chunks
                                .insert(pack_section_pos(ox, oy, oz), OctreeChunkSection::new());
                            octree_chunk = octree_chunks.get_mut(&pack_section_pos(ox, oy, oz));
                        }

                        let Some(octree_chunk) = octree_chunk else {
                            panic!("No octree chunk!")
                        };

                        if block.0 == 0 {
                            insert_block_octree(
                                collider_map,
                                &mut octree_chunk.liquid_octree,
                                &block,
                                false,
                                x & (OCTREE_CHUNK_SIZE - 1),
                                y & (OCTREE_CHUNK_SIZE - 1),
                                z & (OCTREE_CHUNK_SIZE - 1),
                            );
                            insert_block_octree(
                                collider_map,
                                &mut octree_chunk.octree,
                                &block,
                                false,
                                x & (OCTREE_CHUNK_SIZE - 1),
                                y & (OCTREE_CHUNK_SIZE - 1),
                                z & (OCTREE_CHUNK_SIZE - 1),
                            );
                        } else {
                            if collider_map.voxel_colliders[(block.0 - 1) as usize]
                                .as_ref()
                                .unwrap()
                                .is_fluid
                            {
                                insert_block_octree(
                                    collider_map,
                                    &mut octree_chunk.liquid_octree,
                                    &block,
                                    false,
                                    x & (OCTREE_CHUNK_SIZE - 1),
                                    y & (OCTREE_CHUNK_SIZE - 1),
                                    z & (OCTREE_CHUNK_SIZE - 1),
                                );
                            } else {
                                insert_block_octree(
                                    collider_map,
                                    &mut octree_chunk.octree,
                                    &block,
                                    false,
                                    x & (OCTREE_CHUNK_SIZE - 1),
                                    y & (OCTREE_CHUNK_SIZE - 1),
                                    z & (OCTREE_CHUNK_SIZE - 1),
                                );
                            }
                        }
                    }
                }
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_removeChunk<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    global: jboolean,
) {
    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let chart_data = sable_data.chart_mut(scene.chart);

        chart_data
            .main_level_chunks
            .remove(&pack_section_pos(x, y, z));

        if global > 0 {
            let octree_chunk = chart_data.octree_chunks.get_mut(&pack_section_pos(
                (x << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                (y << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                (z << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
            ));

            if let Some(octree_chunk) = octree_chunk {
                for bx in 0..16 {
                    for by in 0..16 {
                        for bz in 0..16 {
                            let x = bx + (x << CHUNK_SHIFT);
                            let y = by + (y << CHUNK_SHIFT);
                            let z = bz + (z << CHUNK_SHIFT);

                            insert_block_octree(
                                collider_map,
                                &mut octree_chunk.octree,
                                &(0, VoxelPhysicsState::Empty),
                                true,
                                x & (OCTREE_CHUNK_SIZE - 1),
                                y & (OCTREE_CHUNK_SIZE - 1),
                                z & (OCTREE_CHUNK_SIZE - 1),
                            );
                            insert_block_octree(
                                collider_map,
                                &mut octree_chunk.liquid_octree,
                                &(0, VoxelPhysicsState::Empty),
                                true,
                                x & (OCTREE_CHUNK_SIZE - 1),
                                y & (OCTREE_CHUNK_SIZE - 1),
                                z & (OCTREE_CHUNK_SIZE - 1),
                            );
                        }
                    }
                }

                if octree_chunk.octree.buffer[0] == 0 && octree_chunk.liquid_octree.buffer[0] == 0 {
                    chart_data.octree_chunks.remove(&pack_section_pos(
                        (x << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                        (y << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                        (z << CHUNK_SHIFT) >> OCTREE_CHUNK_SHIFT,
                    ));
                }
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_changeBlock<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    block: jint,
) {
    let block_collider_id = (block >> 16) as u16;
    let voxel_state_id = (block & 0xFFFF) as u16;

    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let SableSceneData {
            charts,
            level_colliders,
            ..
        } = &mut *sable_data;
        let scene::ChartData {
            main_level_chunks,
            octree_chunks,
            ..
        } = charts.entry(scene.chart).or_default();

        let chunk = main_level_chunks.get_mut(&pack_section_pos(x >> 4, y >> 4, z >> 4));

        if let Some(chunk) = chunk {
            let block_state = (
                block_collider_id as u32,
                ALL_VOXEL_PHYSICS_STATES[voxel_state_id as usize],
            );

            chunk.set_block(x & 15, y & 15, z & 15, block_state);

            let mut any = false;
            for (_, sable_body) in level_colliders.iter_mut() {
                // Atlas: only bodies of the calling chart may own this block —
                // coordinates are only meaningful within one chart's frame.
                if sable_body.chart == scene.chart && sable_body.contains(x, y, z) {
                    sable_body.insert_block(x, y, z, &block_state, true, collider_map);
                    any = true;
                    break;
                }
            }

            if !any {
                // insert into level octree
                let ox = x >> OCTREE_CHUNK_SHIFT;
                let oy = y >> OCTREE_CHUNK_SHIFT;
                let oz = z >> OCTREE_CHUNK_SHIFT;

                let mut octree_chunk = octree_chunks.get_mut(&pack_section_pos(ox, oy, oz));

                if octree_chunk.is_none() {
                    octree_chunks.insert(pack_section_pos(ox, oy, oz), OctreeChunkSection::new());
                    octree_chunk = octree_chunks.get_mut(&pack_section_pos(ox, oy, oz));
                }

                let Some(octree_chunk) = octree_chunk else {
                    panic!("No octree chunk!")
                };

                if block_collider_id == 0 {
                    insert_block_octree(
                        collider_map,
                        &mut octree_chunk.octree,
                        &block_state,
                        true,
                        x & (OCTREE_CHUNK_SIZE - 1),
                        y & (OCTREE_CHUNK_SIZE - 1),
                        z & (OCTREE_CHUNK_SIZE - 1),
                    );
                    insert_block_octree(
                        collider_map,
                        &mut octree_chunk.liquid_octree,
                        &block_state,
                        true,
                        x & (OCTREE_CHUNK_SIZE - 1),
                        y & (OCTREE_CHUNK_SIZE - 1),
                        z & (OCTREE_CHUNK_SIZE - 1),
                    );
                } else {
                    if collider_map
                        .voxel_colliders
                        .get(block_collider_id as usize - 1)
                        .unwrap()
                        .as_ref()
                        .unwrap()
                        .is_fluid
                    {
                        insert_block_octree(
                            collider_map,
                            &mut octree_chunk.liquid_octree,
                            &block_state,
                            false,
                            x & (OCTREE_CHUNK_SIZE - 1),
                            y & (OCTREE_CHUNK_SIZE - 1),
                            z & (OCTREE_CHUNK_SIZE - 1),
                        );
                    } else {
                        insert_block_octree(
                            collider_map,
                            &mut octree_chunk.octree,
                            &block_state,
                            false,
                            x & (OCTREE_CHUNK_SIZE - 1),
                            y & (OCTREE_CHUNK_SIZE - 1),
                            z & (OCTREE_CHUNK_SIZE - 1),
                        );
                    }
                }
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_setMassProperties<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    mass: jdouble,
    center_of_mass: JDoubleArray<'local>,
    inertia: JDoubleArray<'local>,
) {
    let mut com: [jdouble; 3] = [0.0, 0.0, 0.0];
    env.get_double_array_region(center_of_mass, 0, &mut com)
        .unwrap();

    let mut inertia_arr: [jdouble; 9] = [0.0; 9];
    env.get_double_array_region(inertia, 0, &mut inertia_arr)
        .unwrap();

    let inertia_tensor = Mat3::from_cols(
        Vec3::new(
            inertia_arr[0] as Real,
            inertia_arr[1] as Real,
            inertia_arr[2] as Real,
        ),
        Vec3::new(
            inertia_arr[3] as Real,
            inertia_arr[4] as Real,
            inertia_arr[5] as Real,
        ),
        Vec3::new(
            inertia_arr[6] as Real,
            inertia_arr[7] as Real,
            inertia_arr[8] as Real,
        ),
    );

    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let Some(ipl_body_handle) = sable_data.rigid_bodies.get(&(id as LevelColliderID)).copied() else {
            return; // IPL hardening: body gone - never panic across JNI
        };
        let Some(rb) = sim_data.rigid_body_set.get_mut(ipl_body_handle) else {
            return;
        };

        rb.set_additional_mass_properties(
            MassProperties::with_inertia_matrix(Vec3::ZERO, mass as Real, inertia_tensor.into()),
            true,
        );
    })
}

/// Teleports the object to the given position.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_teleportObject<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    i: jdouble,
    j: jdouble,
    k: jdouble,
    r: jdouble,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let Some(ipl_body_handle) = sable_data.rigid_bodies.get(&(id as LevelColliderID)).copied() else {
            return; // IPL hardening: body gone - never panic across JNI
        };
        let Some(rb) = sim_data.rigid_body_set.get_mut(ipl_body_handle) else {
            return;
        };

        let mut pose = *rb.position();
        pose.translation = Vec3::new(x as Real, y as Real, z as Real);
        pose.rotation = Quat::from_xyzw(i as Real, j as Real, k as Real, r as Real);
        rb.set_position(pose, true);
    })
}

/// Wakes up an object.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_wakeUpObject<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let Some(ipl_body_handle) = sable_data.rigid_bodies.get(&(id as LevelColliderID)).copied() else {
            return; // IPL hardening: body gone - never panic across JNI
        };
        let Some(rb) = sim_data.rigid_body_set.get_mut(ipl_body_handle) else {
            return;
        };
        rb.wake_up(true);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_addLinearAngularVelocities<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    linear_x: jdouble,
    linear_y: jdouble,
    linear_z: jdouble,
    angular_x: jdouble,
    angular_y: jdouble,
    angular_z: jdouble,
    wake_up: jboolean,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let rb = get_rigid_body_mut(&mut sim_data, &sable_data, id as LevelColliderID);

        if wake_up == 0 && rb.is_sleeping() {
            return;
        }

        rb.set_linvel(
            rb.linvel() + Vec3::new(linear_x as Real, linear_y as Real, linear_z as Real),
            wake_up > 0,
        );
        rb.set_angvel(
            rb.angvel() + Vec3::new(angular_x as Real, angular_y as Real, angular_z as Real),
            wake_up > 0,
        );
    })
}

/// Clears & queries all collisions
///
/// TODO: Do not pass body IDs as doubles, stupid as hell lmao
///
/// A collision is formatted as follows:
/// [body_a, body_b, force_amount, local_normal_a, local_normal_b, local_point_a, local_point_b]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_clearCollisions<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JDoubleArray<'local> {
    let arr: Vec<jdouble> = with_handle(handle, |scene| {
        let mut reported = scene.reported_collisions.borrow_mut();

        let max_collisions = 100;

        reported.truncate(max_collisions);
        let mut arr: Vec<jdouble> = Vec::with_capacity(reported.len() * 15);

        for collision in reported.iter() {
            let body_a = if let Some(id) = collision.body_a {
                id as jdouble
            } else {
                -1.0
            };

            let body_b = if let Some(id) = collision.body_b {
                id as jdouble
            } else {
                -1.0
            };

            arr.push(body_a);
            arr.push(body_b);
            arr.push(collision.force_amount as jdouble);
            arr.push(collision.local_normal_a.x as jdouble);
            arr.push(collision.local_normal_a.y as jdouble);
            arr.push(collision.local_normal_a.z as jdouble);
            arr.push(collision.local_normal_b.x as jdouble);
            arr.push(collision.local_normal_b.y as jdouble);
            arr.push(collision.local_normal_b.z as jdouble);
            arr.push(collision.local_point_a.x as jdouble);
            arr.push(collision.local_point_a.y as jdouble);
            arr.push(collision.local_point_a.z as jdouble);
            arr.push(collision.local_point_b.x as jdouble);
            arr.push(collision.local_point_b.y as jdouble);
            arr.push(collision.local_point_b.z as jdouble);
        }

        reported.clear();

        arr
    });

    let double_array = _env.new_double_array(arr.len() as jint).unwrap();
    _env.set_double_array_region(&double_array, 0, &arr)
        .unwrap();

    double_array
}

/// Applies a force to a body
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_applyForce<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    fx: jdouble,
    fy: jdouble,
    fz: jdouble,
    wake_up: jboolean,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        // IPL hardening: a missing body must never panic across the JNI boundary
        // (an unwinding panic in an extern "system" fn aborts the JVM).
        let Some(body) = sable_data.rigid_bodies.get(&(id as LevelColliderID)) else {
            return;
        };
        let Some(rb) = sim_data.rigid_body_set.get_mut(*body) else {
            return;
        };

        if wake_up == 0 && rb.is_sleeping() {
            return;
        }

        let force: Vec3 = rb
            .rotation()
            .mul_vec3(Vec3::new(fx as Real, fy as Real, fz as Real));
        let force_pos = rb
            .position()
            .transform_point(Vec3::new(x as Real, y as Real, z as Real));

        rb.apply_impulse(force, wake_up > 0);

        let torque_impulse = (force_pos - rb.position().translation).cross(force);
        rb.apply_torque_impulse(torque_impulse, wake_up > 0);
    })
}

/// Applies a force and torque
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_applyForceAndTorque<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    fx: jdouble,
    fy: jdouble,
    fz: jdouble,
    tx: jdouble,
    ty: jdouble,
    tz: jdouble,
    wake_up: jboolean,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        // IPL hardening: a missing body must never panic across the JNI boundary
        // (an unwinding panic in an extern "system" fn aborts the JVM).
        let Some(body) = sable_data.rigid_bodies.get(&(id as LevelColliderID)) else {
            return;
        };
        let Some(rb) = sim_data.rigid_body_set.get_mut(*body) else {
            return;
        };

        if wake_up == 0 && rb.is_sleeping() {
            return;
        }

        let force: Vec3 = rb
            .rotation()
            .mul_vec3(Vec3::new(fx as Real, fy as Real, fz as Real));
        rb.apply_impulse(force, wake_up > 0);

        let torque: Vec3 = rb
            .rotation()
            .mul_vec3(Vec3::new(tx as Real, ty as Real, tz as Real));
        rb.apply_torque_impulse(torque, wake_up > 0);
    })
}

/// Gets the linear velocity of a body
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_getLinearVelocity<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    store: JDoubleArray<'local>,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = scene.sim_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);

        // IPL hardening: a missing body must never panic across the JNI boundary
        // (an unwinding panic in an extern "system" fn aborts the JVM).
        let Some(body) = sable_data.rigid_bodies.get(&(id as LevelColliderID)) else {
            return;
        };
        let Some(rb) = sim_data.rigid_body_set.get(*body) else {
            return;
        };

        let vel = rb.linvel();

        _env.set_double_array_region(
            &store,
            0,
            &[vel.x as jdouble, vel.y as jdouble, vel.z as jdouble],
        )
        .unwrap();
    })
}

/// Gets the angular velocity of a body
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_getAngularVelocity<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    store: JDoubleArray<'local>,
) {
    with_handle(handle, |scene| {
        let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = scene.sim_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);

        // IPL hardening: a missing body must never panic across the JNI boundary
        // (an unwinding panic in an extern "system" fn aborts the JVM).
        let Some(body) = sable_data.rigid_bodies.get(&(id as LevelColliderID)) else {
            return;
        };
        let Some(rb) = sim_data.rigid_body_set.get(*body) else {
            return;
        };

        let vel = rb.angvel();

        _env.set_double_array_region(
            &store,
            0,
            &[vel.x as jdouble, vel.y as jdouble, vel.z as jdouble],
        )
        .unwrap();
    })
}
