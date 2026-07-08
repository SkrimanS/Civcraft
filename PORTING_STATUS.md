# CivCraft 1.12.2 Port Status

Repository initialized for the MineTexas CivilizationCraft / CivCraft port to Spigot/Paper 1.12.2.

## Target

Port the full CivCraft gameplay system to Minecraft server 1.12.2:

- camps
- civilizations
- towns
- technologies
- buildings
- wonders
- wars
- economy
- SQL persistence
- WorldEdit / Vault / WorldBorder compatibility

## Base source

Imported full MineTexas CivilizationCraft source from `ataranlen/civcraft` into the working branch.

## Working branch

- `port/spigot-1.12.2`

## Current state

Stage 1, Stage 2 scaffolding, local runtime harness, local database harness, and runtime diagnostics are started.

Added a Maven parent project, CivCraft module setup, GitHub Actions build, local test tooling, MariaDB test tooling, jar inspection, and log analysis:

- `pom.xml`
- `civcraft/pom.xml`
- `civcraft/src/plugin.yml`
- `.github/workflows/build-legacy-package.yml`
- `docker-compose.civcraft-test.yml`
- `tools/sql/init-civcraft-test-db.sql`
- `tools/build-civcraft-jar.ps1`
- `tools/check-legacy-libs.ps1`
- `tools/build-civcraft-source.ps1`
- `tools/inspect-built-jar.ps1`
- `tools/analyze-test-log.ps1`
- `tools/start-test-db.ps1`
- `tools/stop-test-db.ps1`
- `tools/prepare-test-server.ps1`
- `tools/run-test-server.ps1`
- `tools/templates/civcraft-test-config.yml`
- `civcraft/lib/README.md`
- `TESTING_1_12_2.md`

## Stage 1: legacy class packaging

The default Maven build packages the already committed legacy Eclipse `civcraft/bin/**/*.class` files together with plugin resources from `civcraft/src`.

This gives us a repeatable test jar for first runtime checks on Spigot/Paper 1.12.2.

Command from repository root:

```powershell
mvn -pl civcraft clean package
```

Or on Windows:

```powershell
.\tools\build-civcraft-jar.ps1
```

Expected output jar:

```text
civcraft\target\civcraft-1.8.0-Beta6-1.12.2-SNAPSHOT.jar
```

The build script now also runs jar inspection:

```powershell
.\tools\inspect-built-jar.ps1
```

GitHub Actions also builds this legacy package on every push to `port/spigot-1.12.2` and uploads the jar as an artifact named:

```text
civcraft-1.12.2-legacy-test-jar
```

## Stage 2: source compile profile

A Maven profile named `source-compile` has been added. It is intended to compile `civcraft/src/**/*.java` against Spigot/Paper 1.12.2 and the old legacy plugin APIs.

Command:

```powershell
mvn -pl civcraft -Psource-compile clean package
```

Or on Windows:

```powershell
.\tools\build-civcraft-source.ps1
```

Before source compilation, check missing legacy jars:

```powershell
.\tools\check-legacy-libs.ps1
```

The source compile profile currently expects these local files under `civcraft/lib`:

```text
dhutils.jar
HeroChat.jar
Vault.jar
VanishNoPacket.jar
WorldBorder.jar
NoCheatPlus.jar
TitleAPI.jar
TagAPI.jar
CustomMobs-4.0-INDEV.jar
craftbukkit-1.12.2.jar
```

## Stage 3: local runtime test harness

A local test server folder can be prepared with:

```powershell
.\tools\prepare-test-server.ps1
```

Or with a known Spigot/Paper 1.12.2 server jar:

```powershell
.\tools\prepare-test-server.ps1 -ServerJar C:\path\to\server.jar
```

Then run:

```powershell
.\tools\run-test-server.ps1
```

The runner saves a console transcript under:

```text
server-test\logs\console-*.txt
```

Analyze the newest transcript with:

```powershell
.\tools\analyze-test-log.ps1
```

## Stage 4: local MariaDB test harness

Start local database:

```powershell
.\tools\start-test-db.ps1
```

Stop local database:

```powershell
.\tools\stop-test-db.ps1
```

The compose file creates two databases:

```text
game
global
```

with user/password:

```text
civcraft / civcraft
```

The local test config template disables external server listing and points CivCraft to this local database.

## Plugin dependency change for testing

`plugin.yml` was relaxed from hard `depend: [CustomMobs]` to soft dependencies:

```yaml
softdepend: [TitleAPI, CustomMobs, Vault, WorldBorder, WorldEdit, HeroChat, TagAPI, NoCheatPlus]
```

This is only for the 1.12.2 test branch so Bukkit can try loading CivCraft and expose real missing-class/runtime errors instead of stopping immediately because one legacy plugin is absent.

## Important note

Stage 1 may produce a jar, but it does not prove that all `civcraft/src/**/*.java` files compile cleanly against Spigot/Paper 1.12.2.

Stage 2 is expected to reveal real compile errors. Those errors are the next data we need to repair the actual source port.

## Next stages

1. Confirm GitHub Actions can build the legacy test jar.
2. Start local MariaDB with Docker.
3. Start a clean Spigot/Paper 1.12.2 test server.
4. Analyze startup/runtime errors with `tools/analyze-test-log.ps1`.
5. Try `source-compile` and capture Maven compile errors.
6. Repair 1.12.2 API/dependency/runtime problems one by one.
