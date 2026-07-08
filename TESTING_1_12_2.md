# CivCraft 1.12.2 local test flow

This branch currently has two build stages:

1. Legacy package build: packages already compiled Eclipse classes from `civcraft/bin`.
2. Source compile profile: tries to compile `civcraft/src` against Spigot/Paper 1.12.2 and legacy plugin jars.

## Fast runtime test

From repository root on Windows PowerShell:

```powershell
git switch port/spigot-1.12.2
git pull

.\tools\build-civcraft-jar.ps1
.\tools\start-test-db.ps1
.\tools\prepare-test-server.ps1 -ServerJar C:\path\to\spigot-or-paper-1.12.2.jar
.\tools\run-test-server.ps1
```

The console transcript is saved to:

```text
server-test\logs\console-*.txt
```

Send that log back for the next fix pass.

## Required plugin jars for the test server

CivCraft currently has this hard dependency in `civcraft/src/plugin.yml`:

```yaml
depend: [CustomMobs]
```

So at minimum `server-test/plugins/CustomMobs.jar` must exist or Bukkit will refuse to enable CivCraft.

Recommended test plugin layout:

```text
server-test/plugins/
  CivCraft.jar
  CustomMobs.jar
  Vault.jar
  WorldBorder.jar
  WorldEdit.jar
  HeroChat.jar
  TitleAPI.jar
  TagAPI.jar
  NoCheatPlus.jar
```

Some may later become optional, but for the first unmodified runtime test it is better to mirror the old server as closely as possible.

## Local database

The local test DB is defined in:

```text
docker-compose.civcraft-test.yml
```

It creates:

```text
game
global
```

with:

```text
user: civcraft
password: civcraft
port: 3306
```

The test config template copied into `server-test/plugins/CivCraft/config.yml` disables external server listing and points CivCraft at this local DB.

## Source compilation test

This will probably fail at first. That is expected.

```powershell
.\tools\check-legacy-libs.ps1
.\tools\build-civcraft-source.ps1
```

Place old dependency jars in:

```text
civcraft\lib
```

with names listed by `check-legacy-libs.ps1`.
