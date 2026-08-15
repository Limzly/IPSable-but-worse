# Atlas vs. Dim-Agnostic v2 — What Changed (2026-07-21)

One-day delta from the shipping dim-agnostic architecture (`dim_agnostic_portal_physics_spec.md`)
to the atlas architecture (`atlas_portal_physics_spec.md`). Full rationale in the specs;
this is the working reference. Branch: `feature/atlas-portal-physics`.

## The one-sentence version

v2 coupled TWO dynamic bodies (real + clone) across per-dimension scenes with a
servo and impulse feedback; atlas has ONE body in ONE world whose far-side presence
is extra *geometry* (image colliders), so portal contacts are solved exactly,
in-solver — the servo, feedback lag, authority swap, and multi-straddle jitter are
not "fixed", they are structurally inexpressible.

## Rapier engine fork (now VENDORED at `natives/engine/rapier`, was a git pin)

| Change | Where | What it does |
|---|---|---|
| `ColliderParent.portal_prefix: Option<Pose>` | `collider_components.rs`, composed at 4 sites (`rigid_body_components.rs:1126,1154`, `rigid_body_set.rs:420`, `user_changes.rs:23`) + soft-ccd predictor | A collider's world pose becomes `P × bodyPose × local` — an image of its body through portal isometry P. Mass composition and CCD deliberately unprefixed. |
| `Collider::set_portal_prefix / portal_prefix` | `collider.rs` | Public API; flags PARENT change so broad/narrow re-run. |
| `ContactManifoldData.portal_shift1/2 + portal_rot1/2` | `contact_pair.rs`, stamped in `narrow_phase.rs` manifold loop | Per-manifold copy of each side's portal isometry (identity when un-imaged) — the solver builders never see collider handles, so the manifold carries it. |
| Mapped-COM / frame-mapped constraint builders | `contact_with_coulomb_friction.rs`, `contact_with_twist_friction.rs`, `generic_contact_constraint.rs` | The imaged side's state is expressed in the far frame at `generate()`/`update()`: `com → P(com)`, `v → R·v`, `ω → R·ω`, `I⁻¹ → R·I⁻¹·Rᵀ` (manual wide similarity; parry `quadform` is scalar-only). Anchors stored in the mapped frame; `update()` re-adds shifts so a body rotation can't swing the portal offset through re-projection. |
| Velocity mapping at gather/scatter | same files, `warmstart()`/`solve()` | Per-constraint wide rotations + a skip flag: gathered velocities map into the portal frame, solve runs UNCHANGED (shared contact dirs are valid — both bodies in one frame), deltas map back on scatter. Ordinary contacts pay one predictable branch. |
| Retention check | `narrow_phase.rs` | `velocity_at_point` evaluated at `P⁻¹(pt)` and rotated — correct approach speeds for imaged contacts. |
| Same-parent narrow-phase filter (stock, leveraged) | `narrow_phase.rs:843` | A body never contacts its own image — v2's clone-vs-real pair exclusions deleted, the engine does it by construction. |

Limits: locked/anisotropic-axis imaged bodies unsupported (per-axis inv mass isn't
rotation-mapped); non-SIMD builds are translation-only; multibody path stays Tier-1.

## Natives crate (`sable_rapier`)

- **One world, chart views** (was: one `PhysicsScene` per dimension). `PhysicsScene`
  handles are now views sharing a `WorldCore`; the 57-function JNI surface is
  unchanged. Terrain/plot chunk maps, octrees, terrain colliders, collision-report
  buffers are **per chart**; body-id maps stay global (ids were always process-unique).
- **Collision groups**: And-mode chart×type masks (bits 0-15 level, 16-31 rope);
  one static terrain collider PER CHART carrying its chart id on the shape.
- **Dispatcher**: pair admission by SHAPE chart (images carry the far chart),
  chunk storage by BODY chart (an image reads its parent's sections), static-octree
  queries take an explicit chart (an image queries far-chart terrain).
- **Image collider JNI**: `createImageCollider(t, R)` / `setImagePrefix` /
  `setImageClipRegions` / `removeImageCollider`; per-image clip regions selected by
  collider handle in the clip hook (far half of the half-open seam).
- Chart-scoped `tick` work (ropes, joints, buoyancy) so fused ticking never
  double-applies; per-chart contact-report routing so `clearCollisions` stays
  per-dimension with zero Java changes.

## Bridge (Java)

- **Fused step** (`IplFusedStep`): all dimensions' substep phases run once per
  SERVER tick in `getAllLevels()` order; every pipeline keeps its housekeeping but
  only the last one's native step is armed. Replaces per-level stepping (which
  would step the shared world N times). Wheel-mount static-flush bug fixed en route.
- **Straddle sessions = image colliders** (`IplStraddleCloneBody`, 1168 → ~370
  lines): clone spawn/feed/despawn, servo, transfer caps, authority swap, damps,
  fraction weighting, force redirects, feedback tee — all DELETED after in-game
  verification. A session is now: registry entry + image collider + clip seam +
  mapping queries.
- **Moving portals** (M5a): sessions re-derive the isometry each tick and push it
  to the image (`setImagePrefix`) + rebuild the clip seam when the portal moved.
- **Ship-anchored portals** (M6, `IplShipPortalAnchor` + `/iplsable_portal`):
  portal origin glued to a ship-local pose, driven from fresh physics poses after
  the fused step; dest end held fixed via the destLock transform
  (`R_t(now) = R_t(0)∘O(0)∘O(now)⁻¹`); `rectifyClusterPortals` handles the pair.
  Parent-aware ship lookup (hosted ships live in the hosting container); NO
  auto-anchor (ships grabbed passing portals); a carrier never straddles or
  transits its OWN anchored portal.

## Commits (today, in order)

```
c065db3  docs: atlas spec v3 + M0 audit results
b39754f  M1 natives: chart views over one shared Rapier world
809a33f  M1 bridge: fused physics step + wheel-mount level filter
d8f9e38  M2 engine: vendor rapier fork + portal prefix + Tier-1 COM substitution
4406f20  M2 natives+bridge: image colliders (flagged)
78593d4  M3: image colliders default ON
41f3286  M4: rotated portals (Tier 2, any fixed R, frame-mapping)
d17b98a  M3 deletion: clone/servo era removed
35248aa  M5a: moving portals — per-tick isometry refresh
4727d41  M6: ship-anchored portals
c8e0205  M6 fix: anchor bounds measured to ship surface
6cf3b71  M6 fix: parent-aware lookup (+auto-anchor, reverted next)
5798ecc  M6 fix: no auto-anchor; carriers ignore their own portals
```

## Still open

- **M5b — frame twist** (task #20): contact velocity term `v_P + ω_P × (p − c_P)`
  for FAST-moving portals; slow carriers are correct to O(dt) today.
- Anchor persistence across restarts; ship-to-ship double-anchored pairs.
- Per-chart gravity is assert-uniform (first chart wins) — revisit if a modded
  dimension ships different gravity.
- Self-collision through portal loops: designed (spec Appendix A), not funded.
