# SimpleItemCleaner

A lightweight dropped-item cleaner for **Minecraft 1.12.2**, built against **Forge 14.23.5.2864**. By **creator01**, licensed under MIT.

SimpleItemCleaner periodically removes eligible dropped items, protects recent drops and lets you choose how specific items are handled. It works in singleplayer through the integrated server and on dedicated servers.

**AI Disclosure:** This mod's code was primarily generated using OpenAI Codex, based on human-defined requirements and validated through automated and in-game testing.

## Features

- Scheduled cleanup of dropped items in loaded chunks and dimensions.
- Minimum-age protection and configurable dimension exclusions.
- Per-item policies, including metadata-specific rules.
- Configurable countdown warnings, result messages and text colors.
- Separate counts for removed item units and entities, plus cleanup duration.
- Local Forge configuration GUI with English labels and tooltips in every client language.
- Item age and lifespan tracking across saves and chunk reloads.

Only dropped item entities are eligible. Inventories, players, mobs and experience orbs are not cleaned. A container dropped on the ground is still an item and can be removed with its contents.

## Installation

Place `simpleitemcleaner-1.0.0.jar` in the instance or server's `mods` directory.

- **Singleplayer:** install it in the client instance; cleanup runs on the integrated server.
- **Dedicated server:** install it on the server. Players do not need the mod to connect.
- **Multiplayer client:** installation provides the local GUI, but cannot enable or configure cleanup on a remote server. A client with the mod can connect to a server without it.

The project owner reports successful testing in singleplayer, multiplayer, dedicated servers and a full modpack using **Cleanroom 0.6.13-alpha with Java 25**. This is not a guarantee for every loader version or mod combination; see [testing notes](docs/TESTING.md).

## Configuration

Open **Main menu → Mods → SimpleItemCleaner → Config**, or edit `config/simpleitemcleaner.cfg` manually.

Save from the main configuration screen. Cancel discards unsaved edits; Undo and Defaults use the native Forge controls. Changes take effect when you next enter a singleplayer world, without restarting Minecraft. Restart a dedicated server after editing its configuration. The GUI only edits the local instance; it does not change remote servers.

The mod is **enabled by default**, with a **300-second cleanup interval** and a **180-second minimum item age**. Time follows server ticks, so pausing singleplayer stops the timer and low TPS lengthens real-time intervals. Warnings, result messages and statistics logging are enabled. Policy and dimension-exclusion lists start empty.

Warning times default to `600, 300, 180, 60, 30` seconds remaining. Values outside the cleanup interval are ignored, so the default effective warnings are `180, 60, 30`.

Result messages support `{count}` (item units), `{entities}` and `{duration}` (milliseconds); warnings support `{time}`. Default chat messages are in English. Existing messages are preserved, and custom text can use any language. All 14 options and their limits are listed in [the configuration reference](docs/TECHNICAL.md#configuration-reference).

## Item policies

| Policy | Scheduled/manual cleanup | Natural expiration |
| --- | --- | --- |
| **CONTROLLED** | Removed when old enough | Extended while protection is active |
| **VANILLA** | Excluded | Original lifespan preserved |
| **PERMANENT** | Excluded | Extended while protection is active |

Items without a rule are CONTROLLED. Precedence is **PERMANENT > VANILLA > CONTROLLED**.

The `vanilla` and `permanent` lists accept registered item names: `minecraft:iron_ingot`, `minecraft:wool@14`, or `minecraft:wool@*`. A name without metadata matches all variants.

**PERMANENT items can accumulate and reduce performance.** This policy does not prevent collection, lava, explosions or removal by other mods. To reverse owned lifespan extensions, disable the mod in its configuration and reopen the world/server **while keeping the JAR installed**.

## Commands

| Command | Permission | Action |
| --- | --- | --- |
| `/simpleitemcleaner status` | Everyone | Show cleaner status, remaining time and minimum age |
| `/simpleitemcleaner clear` | Command permission level 2 | Clean eligible items and restart the interval |

Manual cleanup uses the same age, dimension and policy rules as scheduled cleanup. Both commands also work from the server console.

## Building

Use **JDK 8** and the included **Gradle 5.6.4 Wrapper**. ForgeGradle is pinned to **3.0.197**.

```powershell
.\scripts\gradle-jdk8.ps1 build
.\scripts\verify-artifact.ps1
.\scripts\audit-public.ps1
```

The Windows helper discovers JDK 8 or accepts `-Jdk8 'C:\Java\jdk8'`; it does not change permanent Java environment settings. With JDK 8 configured in the current shell, `./gradlew build` also runs the build.

Output: `build/simpleitemcleaner/libs/simpleitemcleaner-1.0.0.jar`. The build runs the automated tests and reobfuscates the JAR. Git is not required. See [build and validation procedures](docs/TESTING.md) for offline and portability checks.

## License

- [Technical reference](docs/TECHNICAL.md): configuration, architecture, persistence and limitations.
- [Testing](docs/TESTING.md): automated coverage and manual validation scenarios.
- [MIT license](LICENSE), copyright © 2026 creator01.

The Gradle Wrapper retains its upstream [license](gradle/wrapper/LICENSE.txt) and [notice](gradle/wrapper/NOTICE.txt).
