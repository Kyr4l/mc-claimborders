# Border and force-field variants

All variants ship in the plugin and are selected in `config.yml`. `/claim admin reload` switches at runtime.

## Border renderers (`border.renderer`)

| | PARTICLES | GHOST_BLOCKS | DISPLAY |
| --- | --- | --- | --- |
| What it is | Per-player particles: horizontal lines at the viewer's height along each nearby edge, vertical posts at chunk corners | Per-player fake blocks: outsiders see a stained-glass wall band around their height; members with `/claim show` see a lit outline on the ground | Non-persistent `BlockDisplay` panels per straight run of border (up to 8 chunks), scaled to (run length) × (world height) × 0.08: a seamless translucent wall. Two panels per run, `material` for members and `foreign-material` for outsiders; each player is shown only theirs |
| Who sees it | Players within `border.radius` (48); members only with `/claim show` | Players within `border.ghost-blocks.radius` (32); members get the outline | Everyone (`reveal: ALWAYS`) or within `reveal-radius` (`NEAR`); members hidden by default |
| Server cost | ~45 particles per edge per 10 ticks per nearby player | Block-change packets only for the diff; nothing when standing still | Two entities per straight run (a 4×4 claim has 8); no per-tick work in `ALWAYS` |
| Collision | None (entry guard does it) | Client-side: outsiders bump into the fake wall before the server reacts | None |
| Version | Any | Any | 1.19.4+, else falls back to particles |
| Cleanup | None needed | Fake blocks restored on leave/quit/unload | Entities tagged + non-persistent, removed on unload, swept on enable |
| Caveats | Easy to miss at night; particle setting "minimal" hides it | Mod contraptions or real block updates briefly overwrite the fake blocks | Very tall panels; use `height: <n>` for a surface-level band instead of `FULL`. The vanilla world-border texture cannot be used without a client resource pack |

Default: `DISPLAY` with `reveal: NEAR` for a force-field look that only appears when
somebody approaches; `PARTICLES` as the fallback.

## Force field (`forcefield.mode`)

| | HARD | BOUNCE | PUSH |
| --- | --- | --- | --- |
| On crossing into a foreign chunk | Move rewritten back to last allowed position | Same, plus horizontal knockback away from the chunk (`knockback`) | Same as HARD at the line |
| Before the line | Nothing | Nothing | Repulsion grows from `push-radius` blocks out to full `push-strength` at the line |
| Feel | Invisible wall | Shield | Magnetic field (default) |

Common to all modes: teleports into a claim are cancelled (`block-teleports`), vanilla vehicles drop
outsiders at the line (`block-vehicles`), claiming a chunk ejects intruders (`eject-on-claim`), logging in
inside a claim ejects too (`eject-on-join`). A player who is already inside a claim can always walk out.

## Rejected variants

- **Per-player vanilla world border** (`Player#setWorldBorder`): the vanilla border is a single square, so
  it cannot follow a chunk-shaped claim and cannot show more than one claim at a time. Not implemented.
- **Real blocks in the world**: would change the world, break Create contraptions and drop items. No.
- **Packet-level per-player display entities** (ProtocolLib): same look as `DISPLAY` with per-player
  visibility, but adds a dependency. Bukkit's `hideEntity`/`showEntity` already covers the per-player case.
