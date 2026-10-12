# CivCraft 2.0 — Paper 1.21.11 port

## Branch
`port/paper-1.21.11` (separate from main and the Spigot 1.12.2 experiment).

## Base and provenance
The modern `civcraft-leaf` module was imported from [Ichi667/civcraft](https://github.com/Ichi667/civcraft), commit `1ee4a0f180aaa5dd230200f70e8ae6a32eb9bc9c` (`1.8-dev`).
Original CivilizationCraft gameplay data was imported from the same revision.
Copyright and license notices of the original project are preserved in `LICENSE` (GPL-2.0).
This is a source port, NOT a verified full recreation of CivCraft 2.0 mechanics.

## Changes on this branch
- Parent Maven project + modern Java plugin module retained.
- Paper API set to `1.21.11-R0.1-SNAPSHOT`, Java release 21.
- Server defaults target Paper; compatibility with Leaf is optional rather than mandatory.
- Replaced the local binary FAWE JAR compile dependency with Maven-managed WorldEdit API.
- Updated `plugin.yml` and packaged 28 original gameplay data/config files.
- Extracts gameplay definition files into `plugins/CivCraft/civcraft/data` on first startup.
- GitHub Actions packages the plugin and checks resources are present (currently manual-only until the first playable testing milestone).

## Local build
```bash
mvn -B -ntp -pl civcraft-leaf -am clean package
```

The resulting JAR should be in `civcraft-leaf/target/`. Build success alone is not runtime validation.

## Runtime checks still required
1. CI compilation and classpath resolution on Java 21.
2. Start a clean Paper 1.21.11 test server and inspect console startup.
3. Exercise /camp, /civ, /town, /research, /build, and persistence across restarts.
4. Validate optional integrations (WorldEdit/FAWE, Vault, PlaceholderAPI, NPC plugins).
5. Check legacy IDs/materials, structure placement, rollback, permissions, money transactions and anti-dupe behavior.
6. Reconstruct and test the CivCraft 2.0 game systems not yet complete in the source fork (wars, wonders, culture, diplomacy, victory).

Do not use this development snapshot on a production server until those checks pass.

## Verified CI milestones (12 October 2026)

- GitHub Actions compiles and packages on Java 21 / Paper API 1.21.11.
- Actual Paper 1.21.11 Docker server successfully enables the plugin twice.
- The original data loads successfully: 44 structures and 73 technologies.
- A Mineflayer 1.21.11 player connects, creates a civilization, founds a town, claims one chunk and lists research.
- SQLite is checked after the player commands; duplicate claim and repeated civilization creation must not create duplicates or double-charge.
- The same player/civilization/town/claim/balance data is checked after restarting the server.
- Console admin integration status and reload commands are exercised through RCON.

## Known major gaps

- This is **not** a feature-complete CivCraft 2.0 rebuild; wars, diplomacy, culture, wonders and victory mechanics remain incomplete.
- Original building templates include 1,274 `.def` files (~594 MB). A small pack of 24 original building files (camp, town hall, cottage, library, bank and mine, four rotations each) is now bundled and extracted automatically. Remaining templates, including the multi-megabyte Capitol, are imported with `tools/import-legacy-templates.py`.
- Normal structures now use `.schem` when WorldEdit/FAWE is installed, and fall back to original directional `.def` via the existing tick-limited block queue. New parser guards validate dimensions and duplicate coordinates.
- The default economy uses local SQLite without Vault/ExcellentEconomy installed; compatibility must be tested separately.
- FancyNpcs, MMOItems, MythicMobs, WorldEdit/FAWE integrations and NPC/foundation gameplay are still unverified.
- The CI server uses offline mode **only inside isolated GitHub Actions** and exposes the Minecraft port on loopback for its test client; do not copy its security settings to production.

## Stage 3: first playable version (WORK IN PROGRESS — no tests)

By user request (12 October 2026), automatic push and PR testing is PAUSED until a first coherent test version is available. The GitHub Actions workflow has only a `workflow_dispatch` manual trigger. Do not start intermediate CI or game checks.

Code added since the last validated smoke test:
- Manifest-driven first-run extraction of 24 original directional building templates for the default theme.
- Pure Paper .def fallback for normal buildings, previews, camps and foundation items without WorldEdit.
- Preserved priority of modern WorldEdit .schem assets where available.
- Safety bounds for malformed .def coordinates, duplicate blocks and unrealistic sizes; lazy load instead of preloading hundreds of MB.
- Added missing legacy numeric block conversions used by original buildings.
- Building research prerequisites checked before preview and actual placement.
- Per-town building limits from original `structures.yml` are enforced; zero means unlimited.
- Verified-upstream importer (SHA checks) supports remaining Capitols, any theme or all 1,274 files.

Install the external Capitol assets from the **repository root** while targeting your Minecraft server folder:
```bash
python tools/import-legacy-templates.py --scope capitals --destination /path/to/server/plugins/CivCraft/legacy-templates
```

The source data is pinned to Ichi667 commit `1ee4a0f180aaa5dd230200f70e8ae6a32eb9bc9c`. No network activity is performed by the plugin itself.

### Planned testing milestone

Start the tests only when normal /build placement, foundation item flow, structure templates, basic civilization/town progression, research, economy and persistence are developed enough for the first playable snapshot. Before that milestone do not label any of the new changes above as runtime-verified.

The tests listed earlier document the requirements; they are intentionally on hold.


## Stage 3 continuation — foundation and progression code (13 October 2026; UNTESTED)

Changes added after the earlier asset/structure work:

- \`FoundationItemFactory\` creates three native, server-signed (namespaced PDC) items for camp, civilization and town foundations. Existing MMOItems item matching is retained. The item name or material alone does not authorize founding.
- Operator/permission-gated \`/civadmin foundation give <player> <camp|civilization|town> [1-64]\` provides items to online players without requiring MMOItems.
- \`/civadmin funds give <player> <amount>\` allows authorized administrators to provision a developing world, with an upper limit of 10,000,000 per command and local/Vault economy support.
- Failed foundation placement now refunds founding costs after a successfully created camp, town or civilization is rolled back. A normal plugin shutdown returns unconfirmed foundation items still pending in online inventories.
- A civilization with at least one town receives baseline science from the existing \`technologies.base-beakers-per-minute\` and \`town-beakers-per-minute\` settings. Research no longer deadlocks at zero science before the first library. \`/research rate\` shows effective beakers/hour.
- The building economy now pays completed structure production to town treasuries every minute. Per-town fractional carry persists in an \`income_remainder\` SQLite column. Payments are transactional and have no offline catch-up. Controlled by \`economy.production.enabled\`.
- Only a mayor (or a CivCraft administrator) can claim or release town chunks. Releasing a foreign or unclaimed chunk is rejected before mutation.

### Remaining features before the first playable milestone

- Integrate the large default Capitol template into a comfortable install flow (currently downloaded separately through the importer).
- Complete and review the settlement foundation flow and construction lifecycle, including safe cancellation and error recovery.
- Complete multiplayer town/civilization member permissions, technology and resource economy progression, and protection against item dupes.
- Complete first-playable game content and default configuration for a new world.
- **After that milestone only**, reactivate CI, build the JAR, and perform server and gameplay tests on Paper 1.21.11.

New stage-3 changes in this section have NOT been compiled or tested, per user instruction.


## Stage 4 — multiplayer city and civilization membership (13 Oct 2026)

Code only; tests and builds remain postponed until the first playable snapshot.

- Persistent 24-hour single-use invitations in the SQLite settlement_invites table; expiry configurable 1–168 hours in settlements.invitation-expiration-hours.
- A civilization leader can invite players to their civilization; a mayor can invite residents to a city (which also grants membership of that city's civilization).
- Added /civ invite, join, leave, members, kick, setleader and /town invite, join, leave, members, kick, setmayor.
- Invitations require online players. Kick and leadership transfer can target existing offline residents by their stored names.
- Inviter's current office is verified again when the player accepts the invitation; accepts are transactional.
- Players may belong to one civilization and one town. Existing camp affiliation blocks joining, foreign civilization membership blocks town joining.
- Leaders and mayors cannot be kicked or leave until leadership is transferred to an existing member.
- Claim block modification rights are now scoped to the owning town instead of the entire civilization.
- Block interact, inventory open, buckets, pistons, external fluids, fire and explosion terrain damage are protected inside claims. Control these rules with claim-protection toggles.
- New Russian help for /civ help and /town help, and tab completion for these commands.

Still outstanding: missing large Capitol assets and other original templates, reliable recovery of in-progress structure builds across server restarts, deeper governance/culture/diplomacy/war.
These Stage 4 source changes have not yet been compiled or tested by request.


## Stage 5 — crash-resilient construction and recovery (code only)

Building state is journaled before any block in the reserved footprint is cleared,
changed into preview bedrock, or replaced with the structure material. Original
BlockData is written to SQLite in bounded batches and retained throughout building,
rollback and demolition until the terrain recovery has finished.

- Unfinished builds are discovered on startup and requeued without a second charge.
- Cancelling an active build atomically records ROLLBACK_PENDING and pays the permitted refund exactly once; original blocks are restored before the chunk locks are released.
- Completed structures can be demolished via DEMOLISH_PENDING with a once-only refund and restart-resumable restoration.
- Building over existing tile entities is rejected until NBT/inventory-safe original state backup is implemented. Recovery avoids overwriting occupied container states.
- Structures in QUEUED, BUILDING, ROLLBACK_PENDING and DEMOLISH_PENDING states lock their reserved chunks against player edits, inventories, hoppers, buckets, fluid movement, fire, pistons, falling-block changes and explosion damage. Configuration: structures.lock-active-worksites (default true).
- The read-only command /civadmin recovery status shows up to 15 pending structure records, their coordinates, durable journal and snapshot readiness, including from the server console.
- No automatic test runs. GitHub workflow is still manual-only, and the stage-5 source is uncompiled/unverified until first playable test version.

Current limitation: BlockData snapshots cannot faithfully restore inventories or all
tile-entity NBT; preexisting tile entities are therefore prohibited inside building
footprints. Larger original construction assets (particularly the Capitol) still
need integration into a complete first-playable distribution.
