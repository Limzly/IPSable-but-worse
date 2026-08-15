# Dim-Agnostic Portal Physics — Architecture Spec (v2)

Status: **decided direction (per-Level scenes + authority swap), pending feasibility audit**
Scope: IPSable bridge between Immersive Portals (IP) and Sable / Create Simulated.

> v1 of this spec decided on a single shared Rapier scene with portal-isometry weld
> joints. It was superseded before implementation by two findings: (1) Sable's terrain
> lives in Rapier as a **per-scene voxel grid** (`Rapier3D.changeBlock(sceneId, x, y, z,
> packed)` — one packed value per cell), so multi-dimension terrain in one scene means
> write conflicts at overlapping coordinates, not just broad-phase churn — fixing that
> needs a native multi-layer grid; (2) scenes are already first-class across Sable's JNI
> (every call takes a `sceneId`), so per-dimension scenes flow with the existing
> architecture instead of against it. The weld design is retained in §3 as the
> documented escalation path. See git history for v1.

## 1. Problem

Sublevels (Sable physics bodies) must be able to straddle and traverse IP portals,
including portals between dimensions. Approaches rejected so far:

- **Phantom terrain** (current, shipping): straddling sublevels get a transformed copy
  of the through-portal terrain baked into their home scene. Misses sublevel↔sublevel
  and sublevel↔entity interaction on the far side, duplicates terrain geometry +
  friction materials, requires sync on block updates — and forcing foreign terrain
  into one scene has produced a whole bug class in practice: the nether height-profile
  shift, the post-flip arrival rebake hack, and the dimension-stack sub-floor voxel
  drop (source-frame voxels below the home dimension's build height get silently
  dropped past the feed).
- **Full sublevel cloning** (old branch): cloning **gameplay state** — blockstates,
  block entities, redstone, item transfers, modded BEs — caused unresolvable
  downstream issues. The lesson, load-bearing for this design: gameplay state is the
  expensive thing to clone. A physics body is cheap, stateless, and disposable.
- **True portal planes inside a forked Rapier**: scoped and rejected. Rapier's
  `PhysicsHooks` cannot *create* contact pairs, so the engine fork would still need
  transformed broad-phase proxies internally, plus two-frame contact constraints,
  CCD/query/island changes, and permanent forks of the natives. Months of engine work
  for one benefit (exact coupling when wedged across the plane).
- **One shared scene + weld joints** (spec v1): see status note above. Blocked on the
  voxel-grid terrain representation; would also require fixed-joint and dimension-tag
  exposure across JNI.

## 2. Decided architecture

**One authoritative, dimension-agnostic sublevel — gameplay state lives once. Physics
presence is projected per dimension: each Minecraft Level keeps its own Rapier scene
at natural coordinates, the sublevel has a real (dynamic, authoritative) body in its
parent dimension's scene, and zero or more mirrored clone bodies in far-side scenes
while straddling, coupled by isometry-mapped contact-impulse feedback, with contact
clipping at portal apertures. Authority swaps scenes when the center of mass crosses.**

Only derived physics data (collider sets) is ever projected twice. The clone is
stateless and disposable.

### 2.1 Container

- The sublevel's blocks/BEs/entities live once, in the dim-agnostic container — the
  `ipl_sable:sublevels` hosting dimension. The hosting Level is the storage substrate
  (persistence, BE ticking, tracking, packet stamping are already solved there); the
  sublevel is owned by no **parent** Level.
- Presence in a parent Level (rendering, interaction, physics) is a projection.
  Far-side gameplay interaction routes through IP's existing cross-portal interaction
  to the single authority.

### 2.2 Per-Level Rapier scenes

- **Each dimension keeps its own Rapier scene** (stock Sable's shape: scene per
  container/Level; `sceneId` is already a parameter of every native call). Bodies and
  terrain sit at their **natural per-dimension coordinates**.
- Terrain conflicts are structurally impossible: each scene's voxel grid holds exactly
  its own dimension's terrain. No dimension tags, no `filter_contact_pair` exposure,
  no multi-layer grid — the entire "foreign terrain in the wrong frame" bug class
  (§1) stops being expressible.
- f32 precision is a non-issue: separate scenes never share a coordinate space, and
  natural coordinates stay small.
- The hosting dimension's scene holds no terrain and, post-migration, no authoritative
  bodies; the sublevel's body registers in its **parent** dimension's scene. (The
  plumbing change from current v2: the pipeline manages a dimension→scene mapping
  instead of feeding parent terrain into one hosting scene.)

### 2.3 Physics clone

- On straddle: spawn a clone body in the far dimension's scene at
  `portalIsometry × realPose`, reusing the real body's collider data (Sable's collider
  handles are already shared per blockstate via the bakery — block-update rebuilds
  propagate to the clone by re-feeding, same path as the real body).
- **The clone is dynamic with the real body's mirrored mass properties,
  velocity-servoed to the mapped pose — NOT naively kinematic.** A kinematic body has
  infinite effective mass: a far-side ship ramming the through-part would bounce off
  as if it hit bedrock, and the feedback impulse would be computed against the wrong
  mass. With mirrored mass + a pose/velocity servo, far-side contacts resolve against
  physical mass and the servo gain bounds the drift.
- Far-side friction materials come from the far dimension's *real* terrain colliders —
  nothing to copy or sync.

### 2.4 Coupling: isometry-mapped impulse feedback

- Each step: far scene steps with the clone servoed to `P × realPose`; contact
  impulses accumulated on the clone are read out, mapped through the inverse portal
  isometry, and applied to the real body (same tick if step ordering allows, next
  tick otherwise).
- This is **one-step-lagged coupling** — the trade made deliberately against v1's
  in-solver weld. Lag manifests as slightly mushy far-side contact (≈ one physics
  step of penetration before correction). The pathological case — a ship *wedged* by
  closing geometry on both sides of the plane simultaneously — degrades to damped
  oscillation rather than resolving exactly; rare for airships, recoverable, accepted
  (§3).
- **Gravity**: applied in the home (real-side) frame only; the clone's servo owns its
  vertical motion. Rotated-portal "which way is down for the far half" is resolved by
  fiat: home-frame gravity.
- Portal animation: recompute the isometry per tick while the portal moves.

### 2.5 Contact clipping at the aperture

- Needed in **both** scenes: the real body must not collide with home terrain past the
  portal plane (that part of the hull "isn't there"); the clone must not collide with
  far terrain before the plane.
- Mechanism: `PhysicsHooks::modify_solver_contacts` (requires
  `ActiveHooks::MODIFY_SOLVER_CONTACTS` on the affected colliders), if reachable
  across JNI (§4). Predicate — drop a solver contact iff its point is **past the
  portal plane AND within the aperture's lateral bounds** (point-in-OBB on the portal
  rect extruded along the normal). The lateral bound handles geometry passing *beside*
  a free-standing portal frame, which an infinite half-space gets wrong both ways.
  Mirrors IP's `RectangularPortalShape.getThisSideCollisionExclusion` /
  `CollisionHelper.clipVoxelShape` semantics.
- **Half-open convention**: near side keeps `d < 0`, far side keeps `d >= 0`, so a
  seam contact is counted exactly once across the two bodies.
- Hook is stateless per step — clipping vanishes when the straddle entry is dropped.
- **CCD does not respect the hook**: disable CCD on a body while straddling AND cap
  traversal speed in the straddle window. (Not hypothetical: the dimension-stack test
  free-fell its seam in ~11 ticks; fast crossings happen.)
- Fallback if hooks are unreachable from the bridge mod (§4): shape surgery —
  per-block voxel enable/disable past the plane on the hull (the voxel grid makes
  this `changeBlock` calls), plus IP-style exclusion-box removal of near terrain
  voxels. Block-granularity seam error; strictly worse, use only if Sable cannot
  expose hooks.

### 2.6 Handoff and chaining

- When the center of mass crosses: the clone in the far scene is promoted to the real
  body (it already has the correct pose and velocity), the old real body is demoted to
  clone (or despawned if the straddle ended), and the container's parent reference
  flips. This is the existing, production-proven `executeHostedTransit` shape — pose
  remap, velocity remap through the isometry, riders carried — promoted from "transit
  event" to "authority handoff."
- No gameplay state migrates — none was cloned.
- A sublevel straddling N portals gets N clones (one per far scene) each fed back to
  the same real body. Nested/chained portals compose the isometries.

### 2.7 Friction

- Structurally free: each side solves friction natively in its own scene and frame
  (correct tangents through rotated portals); the feedback recombines.
- **Audit required**: if Simulated/Create Offroad runs a custom traction pass (likely,
  for wheels/suspension) instead of trusting Rapier materials, that pass must
  enumerate contacts on the real body *and clones*, mapping clone contact
  positions/forces back through the portal isometry before applying to the
  authoritative body. One loop gains a transform — but the loop must be found.

### 2.8 Create actors (drills, deployers, saws, harvesters)

- Pure gameplay routing, no physics. **Correction from v1**: on sublevels these are
  real block entities in plot chunks, NOT Create `MovementBehaviour` contraption
  actors — Sable's compat hooks their BE ticks and maps positions to the parent frame,
  then performs scattered `level.*` access.
- Scattered call sites are fine **because we own the Level they land on**: IPSable's
  world-frame router (shipped: `IplWorldFrameContext` + `IplHostedWorldFrameRouterMixin`,
  commit `99372b7`) already routes world-frame block get/set/destroy, drops,
  destroy-progress, level events, sounds and game events from the hosting Level to a
  thread-locally resolved target Level, set around each plot-chunk BE tick.
- The portal-aware extension: the routing predicate gains "past a straddled portal's
  plane AND inside its aperture → (destLevel, pos ⊕ isometry)". **Same half-open
  predicate as physics (§2.5), decided by block center** — the drill's physics
  presence and gameplay action must agree on which side it is on.
- Remaining router gaps to close in this pass: entity AABB queries (deliberately
  unrouted today — deployers can't attack far-side mobs), and rotation support in the
  mapping (router is offset-only today).
- **Orientation gating**: block placement encodes quarter-turns only. Actors operate
  through grid-aligned (90°-multiple) portal rotations; no-op otherwise.
- Feedback loop closes itself: far-side block mutation → far dimension rebuilds its
  own terrain voxels natively → clone collides with new geometry. (Per-scene makes
  this loop trivial — it was a sync hazard under phantom terrain.)

### 2.9 Entity collision layer

- Entities standing on / walking on sublevels go through `SubLevelEntityCollision` —
  Sable's own OBB/MTV pass against plot blocks, **outside Rapier**. The physics clone
  has no presence in that system, and doesn't need one: IPSable's straddle pose-map
  mixins (`IplStraddleCollisionPoseMixin`, `IplStraddleInBlockPoseMixin`,
  `IplStraddleOnPosMixin`, + the caller-seam fixes) already make entity collision
  query the one authoritative sublevel at the portal-mapped pose. They are the
  entity-layer analog of the clone and survive this redesign unchanged.
- Stated here so nobody later "discovers" entities as a gap in the per-scene design.

## 3. Descoped (explicitly)

- **In-solver-exact wedged coupling** — traded for one-step impulse feedback (§2.4).
  The weld-joint design (spec v1) remains the documented escalation path; it requires
  single-scene physics and therefore the native multi-layer terrain grid + joint JNI
  exposure, so the bar for reopening it is high.
- **Scaled portals for sublevel bodies** — isometries only; scaled mass/momentum has
  no consistent answer. Entities keep IP's existing scaled-portal traversal; sublevel
  bodies do not traverse scaled portals. (Already enforced in shipping code: the
  straddle machinery gates on scale-1, translation/90°-rotation pairs.)
- **Actor operation through non-grid portal rotations** (no-op).
- **CCD during straddle** (disabled; speed cap in the straddle window).

## 4. Risks / open questions (the audit)

Scope risk concentrates in **what Sable's JNI exposes** and how Simulated applies
forces:

1. **Scene lifecycle**: can bridge-mod Java create/destroy additional Rapier scenes
   and step them, or is scene creation fused to container creation? (Every native
   call takes `sceneId`, so multiplicity exists — who mints IDs?)
2. **Contact readout**: is there a JNI path reading contact events/impulses (points,
   normals, magnitudes) per body? (Sable likely has one for impact sounds/particles —
   find it.) This replaces v1's joint question; it is the coupling's load-bearing
   dependency.
3. **Body APIs**: spawn/despawn bodies at runtime reusing existing collider/voxel
   data; set mass properties, velocities (servo), kinematic/dynamic flags, gravity
   scale, CCD flags per body.
4. **Hooks**: can `PhysicsHooks` (`modify_solver_contacts`, `filter_contact_pair`) be
   registered/implemented from the bridge? Where does Sable configure hooks today, if
   at all? (Needed for §2.5; `filter_contact_pair` no longer needed for dimension
   isolation.)
5. **Natives provenance**: is the Rust source for the `Rapier3D` JNI layer in the
   sable repo (additive API = PR-able) or shipped as opaque binaries (= fork, the
   outcome we are avoiding)? f32 or f64 rapier build?
6. **Terrain representation** (confirm + detail): voxel grid via `changeBlock` —
   packed value layout (`packBlockState` — spare bits?), per-scene storage, conflict
   semantics. (Determines the §2.5 shape-surgery fallback's exact form.)
7. **Friction/traction**: does Sable/Offroad use Rapier material friction or a custom
   traction pass reading contacts and applying forces manually (§2.7)?
8. **Simulated actor seam** (corrected, §2.8): enumerate `level.*` access categories
   reachable from sublevel-hosted Create component code — confirm the Level-seam
   router covers them; find any `MovementBehaviour` usage that bypasses the BE-tick
   seam (true contraptions riding ships?).

If hooks/readout are not exposed, the work item becomes mixins into Sable's Java
pipeline (acceptable; Sable is mixin-heavy by design) or upstream PRs to the natives —
forking the Rust natives is the outcome we are avoiding.

## 5. Audit plan

Executed by agents over the local workspace (`ImmersivePortals-Sable/` = IPSable,
`sable-src/` = ryanhcode/sable, `simulated-src/` = Create Simulated + Offroad), each
answering its slice of §4 with file:line evidence.

Deliverable: a gap matrix mapping each spec component (§2.2–§2.9) to one of
{existing API | mixin into Sable/Simulated Java | upstream change to Rust natives},
with the specific class/method to hook and a rough size estimate per gap. Flag
anything that forces the §2.5 shape-surgery fallback.

## 6. Sequencing (post-audit)

1. Scene-per-dimension plumbing: dimension→scene mapping in the pipeline; terrain
   feeds go to each dimension's own scene at natural coordinates; authoritative body
   registers in the parent's scene. (Replaces hosting-scene terrain feeding; the
   hosting scene goes vestigial.)
2. Clone body + servo for a static, axis-aligned, same-dimension portal (two scenes
   faked in one dimension if needed); verify presence and far-side collision.
3. Impulse feedback; verify wedged-across-the-plane behavior is acceptable.
4. Contact clipping (hook path), half-open seam, aperture lateral bounds.
5. Authority swap + cross-dimension traversal (fold in `executeHostedTransit`); then
   chained portals.
6. Friction audit fix (traction pass isometry mapping) if needed.
7. Actor accessor portal-awareness: router predicate extension, entity-query routing,
   orientation gating.
