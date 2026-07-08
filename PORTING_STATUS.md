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

Stage 1 and Stage 2 scaffolding are started.

Added a Maven parent project and CivCraft module setup:

- `pom.xml`
- `civcraft/pom.xml`
- `tools/build-civcraft-jar.ps1`
- `tools/check-legacy-libs.ps1`
- `tools/build-civcraft-source.ps1`
- `civcraft/lib/README.md`

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

## Important note

Stage 1 may produce a jar, but it does not prove that all `civcraft/src/**/*.java` files compile cleanly against Spigot/Paper 1.12.2.

Stage 2 is expected to reveal real compile errors. Those errors are the next data we need to repair the actual source port.

## Next stages

1. Build a test jar from the legacy compiled classes.
2. Start a clean Spigot/Paper 1.12.2 test server with required dependencies.
3. Capture startup/runtime errors.
4. Try `source-compile` and capture Maven compile errors.
5. Repair 1.12.2 API/dependency/runtime problems one by one.
