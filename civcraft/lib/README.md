# CivCraft legacy local libraries

This directory is intentionally used for old plugin/API jars that are not reliably available from public Maven repositories anymore.

Do **not** commit downloaded third-party jar files unless their license explicitly allows redistribution.

For the `source-compile` Maven profile, place these files here with exactly these names:

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

Run from repository root:

```powershell
.\tools\check-legacy-libs.ps1
```

Then try source compilation:

```powershell
.\tools\build-civcraft-source.ps1
```

The previous Eclipse classpath referenced similar files from `C:/Projects/lib`, for example `Vault v1.5.6.jar`, `HeroChat (2).jar`, `WorldBorder v1.8.5.jar`, `NoCheatPlus 2016-05-21.jar`, and `craftbukkit-1.12.2.jar`.
