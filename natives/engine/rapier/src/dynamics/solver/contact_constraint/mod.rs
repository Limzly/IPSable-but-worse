pub(crate) use contact_constraint_element::*;
pub(crate) use contact_constraints_set::{ConstraintsCounts, ContactConstraintsSet};
pub(crate) use contact_with_coulomb_friction::*;
pub(crate) use generic_contact_constraint::*;
pub(crate) use generic_contact_constraint_element::*;

#[cfg(feature = "dim3")]
pub(crate) use contact_with_twist_friction::*;

mod contact_constraint_element;
mod contact_constraints_set;
mod contact_with_coulomb_friction;
mod generic_contact_constraint;
mod generic_contact_constraint_element;

mod any_contact_constraint;
#[cfg(feature = "dim3")]
mod contact_with_twist_friction;

#[cfg(feature = "dim3")]
use crate::utils::ScalarType;
#[cfg(feature = "dim3")]
use crate::{
    math::DIM,
    utils::{DisableFloatingPointExceptionsFlags, OrthonormalBasis},
};

// ---------------------------------------------------------------------------
// IPL atlas Tier 2: portal-frame mapping helpers. An image collider carries a
// portal isometry P = (R, t); the builders express the imaged body's state in
// the far frame (com → P(com), v → R·v, ω → R·ω, I⁻¹ → R·I⁻¹·Rᵀ) so the
// constraint math — including the SHARED contact directions — runs in one
// frame, and warmstart/solve map velocities at the gather/scatter boundary.
// Non-SIMD builds keep Tier-1 (translation-only) semantics; the shipped
// configuration always enables SIMD.
// ---------------------------------------------------------------------------

/// Widen per-lane scalars into a SIMD register.
#[cfg(all(feature = "dim3", feature = "simd-is-enabled"))]
#[inline(always)]
pub(crate) fn ipl_widen(vals: [crate::math::Real; crate::math::SIMD_WIDTH]) -> crate::math::SimdReal {
    use simba::simd::SimdValue;
    let mut out = crate::math::SimdReal::splat(vals[0]);
    let mut ii = 1;
    while ii < crate::math::SIMD_WIDTH {
        out.replace(ii, vals[ii]);
        ii += 1;
    }
    out
}

/// Widen per-lane portal rotations into a wide unit quaternion.
#[cfg(all(feature = "dim3", feature = "simd-is-enabled"))]
#[inline(always)]
pub(crate) fn ipl_widen_rot(
    rots: [crate::math::Rotation; crate::math::SIMD_WIDTH],
) -> crate::math::SimdRotation<crate::math::SimdReal> {
    use crate::math::SIMD_WIDTH;
    na::UnitQuaternion::new_unchecked(na::Quaternion::from_parts(
        ipl_widen(core::array::from_fn::<_, SIMD_WIDTH, _>(|ii| rots[ii].w)),
        na::Vector3::new(
            ipl_widen(core::array::from_fn::<_, SIMD_WIDTH, _>(|ii| rots[ii].x)),
            ipl_widen(core::array::from_fn::<_, SIMD_WIDTH, _>(|ii| rots[ii].y)),
            ipl_widen(core::array::from_fn::<_, SIMD_WIDTH, _>(|ii| rots[ii].z)),
        ),
    ))
}

/// `R · I⁻¹ · Rᵀ` — manual similarity transform (parry's `quadform` is only
/// specialized for scalar `SdpMatrix3`).
#[cfg(all(feature = "dim3", feature = "simd-is-enabled"))]
#[inline(always)]
pub(crate) fn ipl_conjugate_ii(
    rot: &crate::math::SimdRotation<crate::math::SimdReal>,
    ii: crate::math::SimdAngularInertia<crate::math::SimdReal>,
) -> crate::math::SimdAngularInertia<crate::math::SimdReal> {
    let r = rot.to_rotation_matrix().into_inner();
    let s = na::Matrix3::new(
        ii.m11, ii.m12, ii.m13, //
        ii.m12, ii.m22, ii.m23, //
        ii.m13, ii.m23, ii.m33,
    );
    let m = r * s * r.transpose();
    parry::utils::SdpMatrix3 {
        m11: m[(0, 0)],
        m12: m[(0, 1)],
        m13: m[(0, 2)],
        m22: m[(1, 1)],
        m23: m[(1, 2)],
        m33: m[(2, 2)],
    }
}

#[inline]
#[cfg(feature = "dim3")]
pub(crate) fn compute_tangent_contact_directions<N: ScalarType>(
    force_dir1: &N::Vector,
    linvel1: &N::Vector,
    linvel2: &N::Vector,
) -> [N::Vector; DIM - 1] {
    use crate::utils::{CrossProduct, DotProduct, SimdLength, SimdSelect};
    use OrthonormalBasis;

    // Compute the tangent direction. Pick the direction of
    // the linear relative velocity, if it is not too small.
    // Otherwise use a fallback direction.
    let relative_linvel = *linvel1 - *linvel2;
    let mut tangent_relative_linvel =
        relative_linvel - *force_dir1 * (force_dir1.gdot(relative_linvel));

    let tangent_linvel_norm = {
        let _disable_fe_except =
            DisableFloatingPointExceptionsFlags::disable_floating_point_exceptions();
        let length = tangent_relative_linvel.simd_length();
        tangent_relative_linvel /= length;
        length
    };

    const THRESHOLD: f32 = 1.0e-4;
    let use_fallback = tangent_linvel_norm.simd_lt(na::convert(THRESHOLD));
    let tangent_fallback = force_dir1.orthonormal_vector();
    let tangent1 = tangent_fallback.select(use_fallback, tangent_relative_linvel);
    let bitangent1 = force_dir1.gcross(tangent1);

    [tangent1, bitangent1]
}
