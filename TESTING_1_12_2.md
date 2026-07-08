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
.\tools\analyze-test-log.ps1
```

The console transcript is saved to:

```text
server-test\logs\console-*.txt
```

Send that log back for the next fix pass.

## Built jar inspection

The build script automatically runs:

```powershell
.\tools\inspect-built-jar.ps1
```

It checks that the jar contains:

```text
plugin.yml
com/avrgaming/civcraft/main/CivCraft.class
```

## Plugin dependency policy for testing

For the 1.12.2 port branch, `plugin.yml` has been relaxed from a hard dependency on `CustomMobs` to soft dependencies:

```yaml
softdepend: [TitleAPI, CustomMobs, Vault, WorldBorder, WorldEdit, HeroChat, TagAPI, NoCheatPlus]
```

This lets the server attempt to enable CivCraft even if some old optional plugins are missing. Runtime errors may still reveal that a plugin or API jar is truly required for a specific feature.

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

For the first boot test, it is okay to start with fewer dependency plugins and use the console log to see what actually breaks.

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

## Log analysis

After a failed or successful server start, run:

```powershell
.\tools\analyze-test-log.ps1
```

It extracts likely-important lines such as:

```text
Exception
NoClassDefFoundError
ClassNotFoundException
NoSuchMethodError
SQLException
Could not load
Could not pass event
CivCraft
```

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
