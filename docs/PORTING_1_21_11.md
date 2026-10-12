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
- GitHub Actions packages the plugin and checks resources are present.

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
