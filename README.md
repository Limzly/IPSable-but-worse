# IPSable-but-worse

A fork of [IPSable](https://github.com/r2smith141/IPSable) (which itself is a fork of [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo)) that tries to make Immersive Portals work with Sable physics in the [Create Convoluted](https://modrinth.com/modpack/create-convoluted) modpack.

**Status: indev.** This does not fully work yet. It boots, it doesn't crash, portals render, but there are still visual bugs I'm working through. Most of the fixes that exist are code-level changes based on investigation - they haven't been confirmed in-game yet. If you build it, expect broken things and tell me what you see.

## What this is

The Create Convoluted modpack needs three things to coexist:
- **Immersive Portals** — see-through portals, seamless dimension travel
- **Sable** — physics-based moving structures (airships, etc), required by Create Aeronautics
- **Distant Horizons** — LOD rendering for distant terrain

The stock mods don't get along. Immersive Portals and Sable both hook the same entity collision mixin and crash on launch. IPSable (upstream) fixes that crash by forking Immersive Portals itself and rewiring how sub-levels interact with portals. This fork takes IPSable and adds the modpack-specific compatibility glue for Iris shaders, Distant Horizons, and the rest of the Create Convoluted stack.

## What works

These have been confirmed in-game:

- Game boots with IP + Sable + DH + Iris + Sodium + Veil + Flywheel all loaded together
- No crash on launch (the original IP+Sable collision is fixed upstream by IPSable)
- Portals render and you can walk through them
- Cross-portal physics (Sable sub-levels straddle portals) — upstream IPSable work
- Sound Physics mods no longer spam the log with raycast errors
- DH's render-setup handler no longer throws 15,000+ exceptions per session

## What's been changed but NOT confirmed yet

These are code-level changes that should fix the listed bugs in theory, based on bytecode disassembly and source analysis. None of them have been tested in-game yet, so treat them as "maybe fixed, probably needs more work":

- **Shadow stripes** — clip equation is now refreshed on every shader bind using the current camera position AND current modelView matrix (was stale before, only refreshed for one of two bind hooks)
- **Liquids/particles through terrain** — no-clip sentinel is now written to clear stale clip equations when not in a portal render (was missing from one of the bind hooks entirely)
- **Terrain disappears near portal** — defensive `disableClipping()` call after `popPortalLayer()` (the Iris path can skip the cleanup hook)
- **DH data collision** — the mixin that was supposed to prevent DH from creating a DhLevel for the hosting dimension never actually applied (Mixin refuses cancellable injections on constructors, and `require=0` silently swallowed the error). Replaced with a proper `@Inject` on `DhServerWorld.getOrLoadLevel` + `DhClientServerWorld.getOrLoadLevel`
- **DH config first-launch** — the config patch used to silently no-op on first launch because DH hadn't generated its config file yet. Now retries every 5 seconds for up to 60 seconds

## What doesn't work yet

These are bugs I know about but haven't fixed:

- **Portal visible through blocks with shaders** — Iris depth buffer management in the compatibility renderer. The portal quad can show through solid terrain when a shaderpack is active
- **Wrong water color through portals** — biome color resolver isn't swapped during portal render, so water on the destination side uses the source dimension's color
- **Ghost terrain** — small sections of the wrong dimension render through, with broken lighting. Might be fixed by the clip equation refresh, needs testing

If you want to help, build the latest commit, test it, and tell me what breaks. The full bug catalog with root causes and fix status is in [changelog.md](changelog.md) and [RENDERING_INVESTIGATION.md](RENDERING_INVESTIGATION.md).

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

The jar ends up in `build/libs/`.

## Credits

- **qouteall** — original Immersive Portals author
- **iPortalTeam** — NeoForge port maintainers
- **r2smith141** — IPSable fork (Sable physics compat)
- **GaMiR9195** — IPSable contributor
- **ryanhcode** — Sable physics mod
- **Limzly** (me) — this fork's mess

## License

Apache-2.0, same as upstream IPSable and Immersive Portals.
