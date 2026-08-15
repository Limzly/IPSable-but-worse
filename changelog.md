# Changelog

This is a fork of IPSable for the Create Convoluted modpack. It's indev — things break, things change, no promises on version numbers meaning anything yet.

## indev

First consolidated state of this fork. Squashes the previous 0.6.0–0.6.7 commits into one entry.

### What works

- **Game boots** with IP + Sable + DH + Iris + Sodium + Veil + Flywheel all loaded together — no crash on launch (the original IP+Sable collision is fixed upstream by IPSable)
- **Sound Physics log spam** — `MixinBlockGetter` was logging a full stack trace every time a sound mod raycast crossed a portal boundary; now logs a one-line warning. The 30-block clamp behavior is unchanged.
- **DH render-setup error spam** — the `@Pseudo` mixin on DH's `OverrideInjector.bind` stops ~15,000 `IllegalStateException` errors per session from Iris's `LodRendererEvents$12` re-registering its handler on every portal render re-entry.
- **Cross-portal lighting** — the lightmap is now always updated when switching dimensions for portal rendering. Previously cached dimensions reused a stale lightmap, causing wrong brightness and sky color to leak through.
- **Auto-enable compatibility render mode** when Iris + DH are both detected — uses `IrisCompatibilityPortalRenderer` instead of the normal `IrisPortalRenderer` to avoid the GL Error 1281 cascade that breaks shaders.
- **DH config patch** — patches `DistantHorizons.toml` at startup to add `ipl_sable:sublevels` to `ignoredDimensionCsv`, attempting to prevent DH's data path accumulation across dimensions. Requires a relaunch to take effect.

### What doesn't work yet (honest status)

These are the remaining bugs. My previous attempts at fixing them (depth clears, FB restores, pipeline nulling, clip adjustment changes) either didn't help or made things worse, so they've been reverted. The rendering pipeline is back to upstream IPSable's behavior — only the lightmap fix and the compat-mode auto-enable are kept.

- **Shadow stripes** — 5-7 block thick bands of broken lighting across terrain near portals, spaced 3-5 blocks apart. Not classic z-fighting. Likely related to how the clip plane interacts with the chunk grid or how the clip equation is uploaded per-shader. Needs deeper investigation.
- **Ghost terrain** — small sections (5×8×5 or larger) of the wrong dimension render through, with broken lighting. Likely a clip equation timing issue where some shaders get a zeroed uniform.
- **Shaders + portals** — Iris shaderpacks don't fully cooperate with the portal re-render path. The portal surface can be visible through solid blocks when shaders are on.
- **DH data collision** — DH's `LocalSaveStructure` accumulates data paths across dimensions. The config patch attempts to work around it but the underlying issue is architectural: IPSable's hosting dimension model confuses DH's per-dimension data model. The config patch needs a relaunch and may not fully fix the issue.
- **Portal visible through blocks with shaders** — new bug. The portal surface renders on top of solid terrain when a shaderpack is active. Likely a depth buffer management issue in the compatibility renderer's interaction with Iris's depth passes.

### What I reverted (didn't work)

These changes were tried in v0.6.5–v0.6.7 and didn't fix the issues. Reverted to avoid introducing new bugs:

- `glClear(GL_DEPTH_BUFFER_BIT)` before rendering portal destination content — didn't fix invisible terrain; may have caused depth-related issues
- Deferred→main FB blit after each portal — didn't fix ghost blocks; may have caused flickering
- Removed `setPipeline(worldRenderer, null)` — didn't fix black screen; may have caused portal-visible-through-blocks
- Increased `FrontClipping.ADJUSTMENT` from 0.01 to 0.1 — didn't fix shadow stripes

### Config + metadata

- Fixed `mod_group_id` (was `com.example.examplemod`, leftover NeoForge template cruft)
- Updated authors, issue tracker URL, description to point at this fork
- Added `BUILD.md` with JDK 21 setup instructions

## Upstream history

This fork builds on top of upstream IPSable. See upstream's changelog for everything before `fork-baseline-v0.5.0` (commit `f5e0471`).
