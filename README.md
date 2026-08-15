# IPSable-but-worse

A fork of [IPSable](https://github.com/r2smith141/IPSable) (which itself is a fork of [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo)) that makes Immersive Portals work with Sable physics in the [Create Convoluted](https://modrinth.com/modpack/create-convoluted) modpack.

**Status: indev (v0.6.8).** The mod boots, doesn't crash, portals render, and the major rendering bugs from 0.6.7 have been fixed. Still untested by me as of this version - build it and tell me what breaks. See [changelog.md](changelog.md) for what changed.

## What this is

The Create Convoluted modpack needs three things to coexist:
- **Immersive Portals** — see-through portals, seamless dimension travel
- **Sable** — physics-based moving structures (airships, etc), required by Create Aeronautics
- **Distant Horizons** — LOD rendering for distant terrain

The stock mods don't get along. Immersive Portals and Sable both hook the same entity collision mixin and crash on launch. IPSable (upstream) fixes that crash by forking Immersive Portals itself and rewiring how sub-levels interact with portals. This fork takes IPSable and adds the modpack-specific compatibility glue for Iris shaders, Distant Horizons, and the rest of the Create Convoluted stack.

## What works

- Game boots with IP + Sable + DH + Iris + Sodium + Veil + Flywheel all loaded together
- No crash on launch (the original IP+Sable collision is fixed upstream by IPSable)
- Portals render and you can walk through them
- Cross-portal physics (Sable sub-levels straddle portals) — upstream IPSable work
- Sound Physics mods no longer spam the log with raycast errors
- DH's render-setup handler no longer throws 15,000+ exceptions per session
- Auto-enables compatibility render mode when Iris + DH are both detected
- DH config auto-patches to ignore the hosting dimension (with retry on first launch)
- Lightmap correctly updates when switching dimensions for portal render

## What was fixed in 0.6.8

The 0.6.7 rendering fixes had two critical bugs that meant they only applied to half the shader bind paths. Fixed:

- **Shadow stripes** — the clip equation refresh only worked for one of two program-bind hooks. Now both hooks delegate to a single shared path, so the fix applies to all shaders (terrain, entity, Iris-rewritten, Flywheel)
- **Liquids/particles through terrain** — same root cause, same fix. The no-clip sentinel now reaches all programs
- **DH data collision** — the mixin that was supposed to prevent DH from creating a DhLevel for the hosting dimension never actually applied (Mixin refuses cancellable injections on constructors, and `require=0` silently swallowed the error). Replaced with a proper `@Inject` on `DhServerWorld.getOrLoadLevel` + `DhClientServerWorld.getOrLoadLevel` that returns null
- **Entity shadow stripes** — the clip equation refresh only updated the world-space equation, not the eye-space one. Entity/particle/BE shaders still got stale equations. Now refreshes both using `RenderSystem.getModelViewMatrix()`

Full details in [changelog.md](changelog.md).

## What doesn't work yet

- **Portal visible through blocks with shaders** — Iris depth buffer management in the compatibility renderer. The portal quad can show through solid terrain when a shaderpack is active
- **Wrong water color through portals** — biome color resolver isn't swapped during portal render, so water on the destination side uses the source dimension's color
- **Ghost terrain** — should be fixed by the 0.6.8 clip equation refresh, needs testing

If you want to help, grab the latest build, test it, and tell me what breaks.

## Target stack

| Mod | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 |
| Sable | 2.0.3+ |
| Create | 6.0.10 |
| Create Aeronautics | 1.3.0+ |
| Sodium | 0.8.12+ |
| Iris | 1.8.14-beta.1 |
| Distant Horizons | 3.2.0-b |
| Veil | 4.1.4 |
| Flywheel | 1.0.6 |

## Building

You need **JDK 21** (not 24+, not 25 — Gradle 8.10.2 doesn't support them). See [BUILD.md](BUILD.md) for setup instructions and common errors.

```bash
git clone https://github.com/Limzly/IPSable-but-worse.git
cd IPSable-but-worse
./gradlew jarJar --no-daemon
```

The jar ends up at `build/libs/immersive-portals-sable-compat-0.6.8+ip-6.0.7.jar`.

Drop it into your Minecraft instance's `mods/` folder. It replaces both:
- `immersive_portals-6.0.7-all.jar` (upstream Immersive Portals)
- `immersive_portal_sable_bridge-0.1.jar` (the decrepit bridge, now removed)

## Credits

- **qouteall** — original Immersive Portals author
- **iPortalTeam** — NeoForge port maintainers
- **r2smith141** — IPSable fork (Sable physics compat)
- **GaMiR9195** — IPSable contributor
- **ryanhcode** — Sable physics mod
- **Limzly** (me) — this fork's mess

## License

Apache-2.0, same as upstream IPSable and Immersive Portals.
