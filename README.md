# ClaimBorders

Chunk claiming for Bukkit-compatible servers (Spigot, Paper, and hybrid modded servers such as
Arclight or Mohist). Players claim chunks one by one, the claim gets a visible border, and a force
field keeps non-trusted players out.

- One claim per player, any number of chunks (limit in config or per-permission), optional adjacency rule.
- Three border renderers, switchable in config: **particles**, **ghost blocks** (client-side fake blocks) or
  **display entities** (world-border style translucent panels, one per straight run of border).
- Three force-field behaviours: **HARD** stop, **BOUNCE** (stop + knockback) or **PUSH** (soft repulsion zone).
- Block / interact / entity / explosion protection, all limited to real player actions. Mod machinery
  (Create contraptions, kinetic networks, deployers, belts...) is never touched.
- Clean lifecycle: load, unload and reload with PlugManX without leftovers.

See [docs/variants.md](docs/variants.md) for the comparison of the border and force-field variants.

## Commands

| Command | What it does |
| --- | --- |
| `/claim` | Claim the chunk you stand in |
| `/claim unclaim` (or `/unclaim`) | Release the chunk you stand in |
| `/claim unclaimall confirm` | Release every chunk you own |
| `/claim trust <player>` (or `/trust`) | Let a player into your claim |
| `/claim untrust <player>` (or `/untrust`) | Remove them |
| `/claim trustlist` | Who is trusted |
| `/claim info` | Owner of the chunk you stand in |
| `/claim list` | Your chunks |
| `/claim map` | Chunk map around you, in chat |
| `/claim show` | Toggle the display of your own borders |
| `/claim admin unclaim` | Force-release the chunk you stand in |
| `/claim admin unclaimall <player>` | Release everything a player owns |
| `/claim admin reload` | Re-read `config.yml`, restart the renderer |
| `/claim admin stats` | Totals and active modes |

## Permissions

| Node | Default | Meaning |
| --- | --- | --- |
| `claimborders.use` | everyone | Claim and manage your own claim |
| `claimborders.admin` | op | Admin sub-commands |
| `claimborders.bypass` | op | Walk through force fields, ignore protection |
| `claimborders.limit.<n>` | none | Per-player/group chunk limit (highest wins) |
| `claimborders.limit.unlimited` | none | No limit |

## Configuration

`config.yml` is documented inline. The important switches:

```yaml
claims.max-chunks: 16          # default limit
border.renderer: DISPLAY       # PARTICLES | GHOST_BLOCKS | DISPLAY | NONE
forcefield.mode: PUSH          # HARD | BOUNCE | PUSH
border.display.reveal: NEAR    # ALWAYS | NEAR  (DISPLAY renderer only)
border.display.material: LIGHT_BLUE_STAINED_GLASS_PANE   # what members see
border.display.foreign-material: RED_STAINED_GLASS_PANE  # what outsiders see
protection.ignore-fake-players: true   # keep on with Create
```

Claims are stored in `plugins/ClaimBorders/claims.json`, saved two seconds after any change and on disable.

## Building

Requires a JDK (17 or newer) and Maven. On openSUSE:

```sh
sudo zypper install java-21-openjdk-devel maven
JAVA_HOME=/usr/lib64/jvm/java-21-openjdk-21 mvn -q package
```

Maven must run on a full JDK. If the default `java` is a plain runtime (such as the Java 25 JRE on
openSUSE), it has no `--release` data and the build fails with "release version 21 not supported"; the
`JAVA_HOME` above points Maven at the JDK. To make that permanent: `sudo update-alternatives --config java`.

The jar lands in `target/ClaimBorders-<version>.jar`. It compiles against Spigot API 1.21.1 with Java 21
bytecode, matching Youer/Mohist/Arclight 1.21.1 (any 1.20.5+ server on Java 21 should work). To target an
older server, lower `spigot.api.version` and `maven.compiler.release` in `pom.xml` and `api-version` in
`plugin.yml`; particle and material names are resolved at runtime, so the code itself compiles on 1.20.1 too.

## Testing with PlugManX

```
/plugman load ClaimBorders
/plugman unload ClaimBorders
/plugman reload ClaimBorders
```

On unload the plugin cancels its tasks, removes every display panel, restores every fake block it sent,
unregisters its listeners and saves. On load it sweeps any tagged panel left behind by a crash.

## Create and other mods

Protection hooks only player-caused Bukkit events (`BlockBreakEvent`, `BlockPlaceEvent`, buckets,
`PlayerInteractEvent`, player damage to passive entities, explosions). It never listens to piston,
physics, redstone, fluid or `EntityChangeBlock` events, so contraptions, belts, drills and shafts crossing
a claim border keep working in both directions.

Create's Deployer uses a Forge fake player, which hybrid servers expose as a Bukkit `Player`. The plugin
detects fake players (not in the online list, or a `FakePlayer`/`Deployer` class) and exempts them from
every rule when `protection.ignore-fake-players` is on.

Players riding a Create train or contraption are passengers of a mod entity, which the entry guard does
not control. They can ride through a claim. Vanilla boats, minecarts and horses are handled.

## Layout

```
src/main/java/io/github/kyr4l/claimborders/
  ClaimBordersPlugin.java   lifecycle, reload, message cooldown
  ClaimConfig.java          typed config
  claim/                    ChunkKey, Claim, ClaimManager (index + persistence), BoundaryCalculator
  border/                   BorderService + ParticleRenderer / GhostBlockRenderer / DisplayEntityRenderer
  protect/                  EntryGuard (force field), BlockProtection, Players (fake-player detection)
  command/                  /claim
```
