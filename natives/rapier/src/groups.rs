//! IPL atlas (spec v3 §2.1): collision groups encode (chart, type) so colliders in
//! different charts never pair, while preserving stock Sable's type semantics
//! (level things collide with level things and ropes; ropes collide with level
//! things but not each other; boxes are level-class).
//!
//! Layout: bits 0..15 = LEVEL(chart), bits 16..31 = ROPE(chart), chart bit index =
//! `chart % 16`. All masks use `InteractionTestMode::And` — the fork's `Or` mode is
//! more permissive than AND and cannot express chart isolation (a shared type bit
//! makes every pair interact). And-mode truth: interact iff
//! `(m1 & f2) != 0 && (m2 & f1) != 0`.
//!
//! With >16 live charts, two charts can share a bit and would stop being isolated
//! from each other; `chart_bit` logs once if that ever happens (MC dimension counts
//! make this effectively unreachable).

use crate::scene::ChartId;
use rapier3d::prelude::{Group, InteractionGroups, InteractionTestMode};

pub const MAX_CHART_BITS: u16 = 16;

static WRAP_WARNED: std::sync::Once = std::sync::Once::new();

#[inline]
fn chart_bit(chart: ChartId) -> u32 {
    if chart >= MAX_CHART_BITS {
        WRAP_WARNED.call_once(|| {
            eprintln!(
                "[ipl-natives] WARNING: chart id {chart} exceeds {MAX_CHART_BITS} group bits; \
                 charts {} apart share a collision-group bit and are no longer isolated",
                MAX_CHART_BITS
            );
        });
    }
    (chart % MAX_CHART_BITS) as u32
}

#[inline]
fn level_bit(chart: ChartId) -> Group {
    Group::from_bits_retain(1u32 << chart_bit(chart))
}

#[inline]
fn rope_bit(chart: ChartId) -> Group {
    Group::from_bits_retain(1u32 << (16 + chart_bit(chart)))
}

/// The per-chart static terrain collider: level-class member of its chart, filters
/// everything. And-mode makes cross-chart terrain pairs impossible (the other
/// side's filter has no bit in this chart).
#[inline]
pub fn terrain_group(chart: ChartId) -> InteractionGroups {
    InteractionGroups::new(level_bit(chart), Group::ALL, InteractionTestMode::And)
}

/// Ships, contraptions, boxes: level-class in one chart. Collides with level-class
/// and ropes of the SAME chart (and terrain, whose membership spans all charts).
#[inline]
pub fn level_group(chart: ChartId) -> InteractionGroups {
    InteractionGroups::new(
        level_bit(chart),
        level_bit(chart).union(rope_bit(chart)),
        InteractionTestMode::And,
    )
}

/// Rope points: rope-class in one chart. Collides with level-class of the same
/// chart only (rope-rope stays excluded, matching stock ROPE_GROUP).
#[inline]
pub fn rope_group(chart: ChartId) -> InteractionGroups {
    InteractionGroups::new(rope_bit(chart), level_bit(chart), InteractionTestMode::And)
}

/// An Atlas image is geometry belonging to a body whose real chart is elsewhere. It must
/// interact with the far chart without making that real body collide with every chart. Give
/// the image the far chart's normal level membership; its parent-body mapping is handled by
/// the engine fork during constraint generation.
#[inline]
pub fn image_group(chart: ChartId) -> InteractionGroups {
    level_group(chart)
}
