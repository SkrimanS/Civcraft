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

Stage 1 is started.

Added a Maven parent project and a CivCraft module package step:

- `pom.xml`
- `civcraft/pom.xml`
- `tools/build-civcraft-jar.ps1`

The current Maven build packages the already committed legacy Eclipse `civcraft/bin/**/*.class` files together with plugin resources from `civcraft/src`.

This gives us a repeatable test jar for first runtime checks on Spigot/Paper 1.12.2.

## Important note

This is not yet the final source-port build. It does not prove that all `civcraft/src/**/*.java` files compile cleanly against Spigot/Paper 1.12.2.

Next stages:

1. Build a test jar from the legacy compiled classes.
2. Start a clean Spigot/Paper 1.12.2 test server with required dependencies.
3. Capture startup/runtime errors.
4. Convert the project from legacy Eclipse classpath to real Maven source compilation.
5. Repair 1.12.2 API/dependency/runtime problems one by one.

## Build command

From repository root:

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
