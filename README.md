# IPSable-but-worse

A fork of [IPSable](https://github.com/r2smith141/IPSable) (which itself is a fork of [Immersive Portals for NeoForge](https://github.com/iPortalTeam/ImmersivePortalsModForNeo)) that tries to make Immersive Portals work with Sable physics in the [Create Convoluted](https://modrinth.com/modpack/create-convoluted) modpack.

**Status: indev.** This does not fully work yet. It boots, it doesn't crash, portals render, but there are still visual bugs (shadow stripes, ghost terrain, shaders fighting the portal rendering). I'm working through them.

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

## What doesn't work yet

These are the things I'm still fighting:

- **Shadow stripes** — z-fighting from the clip plane, shows up as bands of broken lighting across terrain near portals
- **Ghost terrain** — small sections of the wrong dimension render through, with broken lighting
- **Shaders + portals** — Iris shaderpacks don't fully cooperate with the portal re-render path
- **Portal visible through blocks** — with shaders on, the portal surface can show through solid terrain
- **Distant Horizons data collision** — DH accumulates data paths across dimensions because of the IPSable hosting dimension model. There's a config patch that tries to work around it but it needs a relaunch to take effect

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
