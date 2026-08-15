use crate::event_handler::SableEventHandler;
use crate::hooks::SablePhysicsHooks;
use crate::joints::SableJointSet;
use crate::rope::RopeMap;
use crate::{ActiveLevelColliderInfo, ReportedCollision};
use dashmap::DashMap;
use jni::JavaVM;
use marten::Real;
use marten::level::{ChunkSection, OctreeChunkSection};
use rapier3d::dynamics::{
    CCDSolver, ImpulseJointSet, IslandManager, MultibodyJointSet, RigidBodyHandle, RigidBodySet,
};
use rapier3d::geometry::{ColliderSet, DefaultBroadPhase, NarrowPhase};
use rapier3d::glamx::IVec3;
use rapier3d::math::Vec3;
use rapier3d::pipeline::PhysicsPipeline;
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, RwLock};

pub type LevelColliderID = usize;

pub trait ChunkAccess {
    #[allow(unused)]
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection>;
    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection>;
}

#[inline(always)]
pub fn pack_section_pos(i: i32, j: i32, k: i32) -> i64 {
    let mut l: i64 = 0;
    l |= (i as i64 & 4194303i64) << 42;
    l |= j as i64 & 1048575i64;
    l | (k as i64 & 4194303i64) << 20
}

pub type ChunkMap = HashMap<i64, ChunkSection>;

/// IPL atlas (spec v3 §2.1): a chart is one dimension's coordinate frame inside the
/// single shared world. Chart ids are minted per Java-side `initialize` call.
pub type ChartId = u16;

/// Per-chart chunk storage: terrain sections AND hosted ships' plot-space sections
/// (plot coords are globally unique, so they coexist keyed by position). Ropes and
/// joints stay world-global (id-keyed) — each entry is chart-STAMPED instead, and
/// the per-level `Rapier3D.tick` loops filter by chart.
#[derive(Default)]
pub struct ChartData {
    pub main_level_chunks: ChunkMap,
    pub octree_chunks: HashMap<i64, OctreeChunkSection>,
}

impl ChunkAccess for ChartData {
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection> {
        self.main_level_chunks.get_mut(&pack_section_pos(x, y, z))
    }

    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection> {
        self.main_level_chunks.get(&pack_section_pos(x, y, z))
    }
}

impl ChartData {
    pub fn get_octree_chunk(&self, x: i32, y: i32, z: i32) -> Option<&OctreeChunkSection> {
        self.octree_chunks.get(&pack_section_pos(x, y, z))
    }

    pub fn get_octree_chunk_mut(
        &mut self,
        x: i32,
        y: i32,
        z: i32,
    ) -> Option<&mut OctreeChunkSection> {
        self.octree_chunks.get_mut(&pack_section_pos(x, y, z))
    }
}

/// IPL fix: this was `RefCell<Vec>` with an `unsafe impl Sync` — but the event handler
/// extends it from rapier's PARALLEL island solver threads (the `parallel` feature is
/// on), so concurrent pushes raced and corrupted the Vec (0xc0000005 JVM death under
/// heavy contact-force volume, e.g. a straddling body wedged into terrain). A Mutex keeps
/// the same call-site shape; per-batch locking is negligible next to the solve.
pub struct ReportedCollisionBuffer(Mutex<Vec<ReportedCollision>>);

impl ReportedCollisionBuffer {
    pub fn new() -> Self {
        Self(Mutex::new(Vec::with_capacity(16)))
    }

    pub fn borrow_mut(&self) -> std::sync::MutexGuard<'_, Vec<ReportedCollision>> {
        self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

impl Default for ReportedCollisionBuffer {
    fn default() -> Self {
        Self::new()
    }
}

pub struct SimulationSceneData {
    pub pipeline: PhysicsPipeline,
    pub rigid_body_set: RigidBodySet,
    pub collider_set: ColliderSet,
    pub island_manager: IslandManager,
    pub broad_phase: DefaultBroadPhase,
    pub narrow_phase: NarrowPhase,
    pub impulse_joint_set: ImpulseJointSet,
    pub multibody_joint_set: MultibodyJointSet,
    pub ccd_solver: CCDSolver,
    pub physics_hooks: SablePhysicsHooks,
    pub event_handler: SableEventHandler,
}

pub struct SableSceneData {
    /// IPL atlas: per-chart chunk storage. Body-id-keyed maps stay world-global
    /// below — ids are process-unique.
    pub charts: HashMap<ChartId, ChartData>,

    /// The companion joint set (world-global; entries chart-stamped)
    pub joint_set: SableJointSet,

    /// Rope map (world-global; strands chart-stamped)
    pub rope_map: RopeMap,

    pub level_colliders: HashMap<LevelColliderID, ActiveLevelColliderInfo>,
    pub rigid_bodies: HashMap<LevelColliderID, RigidBodyHandle>,
    /// Real dynamic Sable bodies indexed by their owning chart. Atlas views share the
    /// maps above, but buoyancy must not scan every world's ships from every chart tick.
    pub bodies_by_chart: HashMap<ChartId, HashSet<LevelColliderID>>,

    /// IPL: body pairs (normalized id order) that must never generate contacts. Portal
    /// rims use this to exempt an anchored portal carrier from its containment body.
    pub ipl_excluded_pairs: HashSet<(LevelColliderID, LevelColliderID)>,

}

impl SableSceneData {
    /// Chart accessor for read paths. A missing chart (e.g. a body whose chart was
    /// disposed mid-race) reads as empty via `None` — callers treat that as
    /// "no chunks/no data", never a panic (JNI panics abort the JVM).
    #[inline]
    pub fn chart(&self, chart: ChartId) -> Option<&ChartData> {
        self.charts.get(&chart)
    }

    /// Chart accessor for write paths — creates the chart's storage on first use.
    #[inline]
    pub fn chart_mut(&mut self, chart: ChartId) -> &mut ChartData {
        self.charts.entry(chart).or_default()
    }
}

/// Per-chart collision-report buffers, keyed by chart id. The event handler routes
/// records here by the contacting body's chart so each Java pipeline's
/// `clearCollisions` drains exactly its own dimension's records.
pub type ChartBuffers = DashMap<ChartId, Arc<ReportedCollisionBuffer>>;

/// IPL atlas: the state shared by every chart view of the single world. Created by
/// the first `initialize` call, dropped when the last view is disposed (the JNI
/// registry holds only a `Weak`).
pub struct WorldCore {
    pub sim_data: Arc<RwLock<SimulationSceneData>>,
    pub sable_data: Arc<RwLock<SableSceneData>>,
    pub manifold_info_map: Arc<SableManifoldInfoMap>,
    pub chart_buffers: Arc<ChartBuffers>,
    pub ground_handle: RigidBodyHandle,
    /// Gravity the world actually steps with (first chart wins — assert-uniform,
    /// spec v3 §5 gravity decision (a)).
    pub world_gravity: Vec3,
    pub next_chart: AtomicUsize,
}

/// A physics scene handle as seen by Java. Since the atlas merge (spec v3 §2.1)
/// this is a CHART VIEW: the heavy state is shared world state (`Arc`s into the
/// `WorldCore`), while gravity/drag/report-buffer/chart-id are per-view. Field
/// names/shapes are kept so per-body JNI call sites work unchanged.
pub struct PhysicsScene {
    /// Keeps the shared world alive; also the registry's canonical handle.
    pub world: Arc<WorldCore>,

    pub sim_data: Arc<RwLock<SimulationSceneData>>,
    pub sable_data: Arc<RwLock<SableSceneData>>,

    /// This chart's collision-report buffer (drained by `clearCollisions`).
    pub reported_collisions: Arc<ReportedCollisionBuffer>,

    pub manifold_info_map: Arc<SableManifoldInfoMap>,

    pub current_step_vm: Option<Arc<JavaVM>>,

    /// The handle to a static rigidbody
    pub ground_handle: Option<RigidBodyHandle>,

    /// The current gravity vector for all bodies. [m/s^2]
    pub gravity: Vec3,

    /// Universal linear drag applied to all bodies
    pub universal_drag: Real,

    /// Which chart this view addresses.
    pub chart: ChartId,

    /// This chart's static terrain collider (removed on dispose).
    pub terrain_collider: Option<rapier3d::geometry::ColliderHandle>,
}

#[derive(Default)]
pub struct SableManifoldInfoMap {
    pub list: DashMap<usize, SableManifoldInfo>,
    pub counter: AtomicUsize,
}

impl SableManifoldInfoMap {
    pub fn clear(&self) {
        self.list.clear();
        self.counter.store(0, Ordering::Relaxed);
    }
}

pub struct SableManifoldInfo {
    pub pos_a: IVec3,
    pub pos_b: IVec3,
    pub col_a: usize,
    pub col_b: usize,
}

