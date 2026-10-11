# Testing and validation

## Validation status

The project owner reports successful functional testing of SimpleItemCleaner 1.0.0 in **singleplayer, multiplayer, a dedicated server and the full modpack**. The reported runtime is **Minecraft 1.12.2 with Cleanroom 0.6.13-alpha and Java 25**. Earlier owner reports also covered large item populations and GUI save/reentry without restarting Minecraft.

These are owner-reported manual results, including clients with and without the mod. Minecraft was not launched during the final source/artifact audit. The exact modpack manifest and logs are not included here. Compatibility with other combinations is not implied; the scenarios below are a repeatable regression checklist.

## Build and automated tests

Use the pinned Wrapper and **JDK 8** for Gradle and compilation, even when the runtime instance uses a newer Java version. Initial dependency downloads require network access; subsequent builds can use the local cache.

```powershell
.\scripts\gradle-jdk8.ps1 build
.\scripts\gradle-jdk8.ps1 build --offline --console=plain
.\scripts\verify-artifact.ps1
.\scripts\audit-public.ps1
.\scripts\test-audit.ps1
```

The first two commands are online/default and offline alternatives, not two required builds. The helper accepts `-Jdk8 'C:\Java\jdk8'`, restores process environment variables and does not change permanent Windows settings. On other systems, configure JDK 8 in the current shell and run `./gradlew build`; the PowerShell audit helpers require PowerShell.

`build` runs all eight executable Java suites and reobfuscates the JAR. No JUnit dependency is required. The expected total is **567 assertions**, preserving the previous coverage while checking English fallback and existing Unicode messages:

| Suite | Assertions | Coverage |
| --- | ---: | --- |
| LifecycleStateTest | 91 | Age/lifespan ownership, restoration, conflicts and boundaries |
| LifecycleNbtTest | 21 | Versioned NBT and preservation rules |
| EntityItemAccessTest | 12 | Mapped age-field access without a world |
| FunctionalTest | 109 | Configuration, policies, ticks, mapped entity/world stubs and permissions |
| CleanupResultTest | 22 | Long counts, duration and message placeholders |
| ConfigEditorTest | 91 | Registration, 14 settings, CFG IO, existing Unicode messages, validation, sessions and dedicated class isolation |
| GuiQualityTest | 198 | English fallback with English/Portuguese clients, native Undo/Reset, error recovery, scope and footer geometry |
| FirstInstallationTest | 23 | Absent config directory/CFG, all default settings, persistence and session reopen |

Some deliberate-invalid-input tests log ERROR/WARN messages. Success is determined by the suite assertions and Gradle exit status, not absence of these expected diagnostics. Fixtures are created under build and retained.

The GUI suite loads real ZIP resources through Forge/Minecraft resource managers and executes native entry data methods. It prepares entry objects without graphical constructors, using reflection/Unsafe **only in tests**. It does not render, click a screen or launch Minecraft. Footer geometry checks use a six-pixel character budget at scaled widths 320, 427, 640, 854 and 1280. Real fonts, scaling and input interaction still require runtime checks.

The dedicated-loading test denies graphical packages in an isolated class loader. This checks dependencies, not the full dedicated Forge/Cleanroom transformer environment. Session tests invoke lifecycle handlers and clocks in one JVM; they do not open real saved worlds.

## Artifact, privacy and portability

Default release path: `build/simpleitemcleaner/libs/simpleitemcleaner-1.0.0.jar`.

`verify-artifact.ps1` requires exactly the 27 reviewed classes and five resources, rejects duplicate/unexpected entries, compares every resource with its source and restricts manifest fields. It parses Java 8 constant pools (including modified UTF-8), verifies internal class names, SRG references and graphical isolation, and rejects unexpected process/network/environment APIs. Only the English language resource is allowed. It prints SHA-256 without local paths.

`audit-public.ps1` scans public text, entry names, all JAR bytes and decoded constant pools. Detectors cover environment-derived identities, personal paths, email, UUIDs, Windows SIDs, IPv4/MAC addresses, unreviewed URLs and common credential/key formats. Required third-party license/notice content is exempt from generic contact/URL checks, but not known private identities or credential checks. Filesystem redirections are refused. `audit-common.ps1` supplies these shared checks; it is not a standalone command.

For a separately generated artifact, pass `-JarPath`. For historical identities, use `-PreviousJarPath` with local historical JARs; names are derived in memory. `-ForbiddenTokens` supports additional owner-supplied identifiers. Avoid embedding private audit inputs in public commands/logs. This is not a universal detector of every possible secret or personal identifier.

```powershell
.\scripts\verify-artifact.ps1 -JarPath 'C:\releases\simpleitemcleaner-1.0.0.jar'
.\scripts\audit-public.ps1 -JarPath 'C:\releases\simpleitemcleaner-1.0.0.jar'
.\scripts\test-portable.ps1 -Jdk8 'C:\Java\jdk8'
```

The portability script creates a public-file copy below build, without `.git`, restricts PATH so Git is unavailable, runs an offline full build/tests and audits the result. It reuses the caller project's dependency cache and retains the copy for inspection. It checks for Git's presence without executing Git. Run a normal build first to populate the cache when starting from a cleaned checkout.

`test-audit.ps1` runs **33 checks**, including synthetic privacy inputs, environment detection, modified UTF-8, redirected-path rejection, corrupted bytecode, duplicate/unexpected entries, a redundant language resource, altered resources and a private constant injected into a compiled class. These tests support PowerShell 7 and Windows PowerShell 5.1, require Windows junction support and generate fixtures only under build. The JDK helper's discovery/version check also runs in both shells. Keep `.gradle-user-home` locally for offline rebuilds; it is ignored and excluded from public inputs.

All six scripts should be retained. Do not publish the working directory wholesale: caches, runtime files, logs, backup archives and build fixtures are not source-release contents. Pattern-based audits are not a proof against arbitrary obfuscation, encrypted secrets or every possible identifier. Review new source and dependency changes manually.

## Runtime test setup

Use a separate test instance and a disposable world with commands enabled. Keep the chosen loader/Java pairing explicit and activate only one SimpleItemCleaner JAR. Preserve existing worlds and CFGs outside the test instance.

For first installation, start with no `config/simpleitemcleaner.cfg`, enter a world or start the dedicated server, then verify the generated 14 options, enabled status, 300-second interval and 180-second age protection. Exit/reopen and confirm the values persist. Opening and canceling the GUI alone must not create the file.

For faster checks, set interval **60**, minimum age **15**, warnings **30/10**, VANILLA `minecraft:stick` and `minecraft:wool@14`, PERMANENT `minecraft:gold_ingot` and `minecraft:emerald`. Leave diamonds CONTROLLED. These are test settings, not distribution defaults. PERMANENT drops can accumulate; keep the scenario bounded.

## GUI and configuration

1. Open Main menu → Mods → SimpleItemCleaner → Config with the client set to English, then Portuguese. All mod-owned labels, tooltips and error guidance must remain English, with no literal translation keys. Check all six groups and 14 options, small windows and each GUI scale; native Minecraft/Forge captions may follow the client language and must remain readable without overlap.
2. Edit multiple groups and cancel from the root, then repeat using Esc. Existing CFG bytes must remain unchanged; opening/canceling before the first save must not create a missing CFG.
3. In an initially empty VANILLA list, enter `minecraft:iron` and save. Expect rejection naming `policies.vanilla`, with unchanged disk contents. Return, Undo, then save successfully. Repeat with Defaults and with correction to `minecraft:iron_ingot`.
4. Try multiple identifiers and invalid items in both policy lists. Fix each reported field, repeat failed attempts and confirm valid lists persist. An error remaining in the other category must still be rejected.
5. Check native scope: Undo restores opening values, Defaults restores defaults. Local actions affect only their category; Apply Globally at the root includes children. Other visible edits survive a failed save.
6. Test interval 0, negative age, values above 86400, integer overflow, malformed items and metadata overflow. Confirm rejection without silent replacement. Test empty lists/text, ignored warning values, duplicate warnings and unknown placeholders according to [the reference](TECHNICAL.md#configuration-reference).
7. Edit the CFG externally while the GUI is open; Save must reject the conflict and preserve the external version. Cancel/reopen. A previously malformed CFG requires manual correction and must not be silently replaced.
8. Save 60/15 with warnings 30/10, enter a world and check status. Exit to the main menu without closing Minecraft; save 20/5 with warnings 5/2 and reenter. Check new values, a fresh countdown and no duplicate warnings. Recheck disabled/re-enabled sessions.

## Cleanup, counts and messages

With the 60/15 test settings, run `/simpleitemcleaner clear` to reset the timer. Summon separated drops to avoid collection or merging:

```text
/summon minecraft:item ~ ~2 ~ {Item:{id:"minecraft:diamond",Count:1b},Age:0s,PickupDelay:32767s,NoGravity:1b,Tags:["sic_one"]}
/summon minecraft:item ~5 ~2 ~ {Item:{id:"minecraft:diamond",Count:5b},Age:0s,PickupDelay:32767s,NoGravity:1b,Tags:["sic_five"]}
/summon minecraft:item ~10 ~2 ~ {Item:{id:"minecraft:diamond",Count:10b},Age:0s,PickupDelay:32767s,NoGravity:1b,Tags:["sic_ten"]}
```

Wait at least 15 seconds of unpaused ticks, then clear before the automatic cycle. With no other eligible drops, expect **16 units / 3 entities** and the measured duration in milliseconds with three decimal places and a comma. Clear again: zero units/entities, no double counting. Repeat with a stack of 64 (64/1), a fresh protected drop, VANILLA/PERMANENT drops and a scheduled cycle. Merging can change entity counts without changing units.

Check warnings once at 30/10 seconds remaining, results including zero, log counters and timer reset. Test custom `{count}`, `{entities}`, `{duration}`, repetitions and unknown placeholders; test warning `{time}` and message colors. Disabling public warnings/results should not suppress administrative clear feedback.

On a fresh CFG, expect English default warnings/results and command feedback. With an existing CFG, confirm that custom messages in any language remain unchanged after startup and after unrelated GUI edits. The three-decimal comma format remains unchanged regardless of language. Review both decoded class constants and text resources when auditing release language; identifiers, Unicode test data and mandatory third-party notices are not translated.

Verify inventories, machines, mobs and XP remain intact. Confirm metadata-specific rules, excluded loaded dimensions and that unloaded chunks are not forced to load. A dropped container remains an item; its contents are not separately counted. Pausing SP stops tick time; low TPS changes elapsed wall time.

## Persistence and rollback

- With enabled=true and `Lifespan:200`, VANILLA should expire near its original deadline, CONTROLLED should survive natural expiration until eligible cleanup, and PERMANENT should survive both paths. If both lists match, PERMANENT wins.
- To cross the vanilla short-age boundary, use a PERMANENT emerald:

```text
/summon minecraft:item ~20 ~2 ~ {Item:{id:"minecraft:emerald",Count:1b},Age:32760s,Lifespan:32770,PickupDelay:32767s,NoGravity:1b,Tags:["sic_age"]}
/entitydata @e[type=minecraft:item,tag=sic_age,c=1] {}
```

Wait at least three seconds before inspecting. FullAge under `ForgeCaps/simpleitemcleaner:lifecycle` should exceed 32767; vanilla Age can appear negative because it is a short. Do not use `Age:40000s` as a valid full-age initializer. Save/reopen and repeat with actual chunk unload/reload away from spawn/chunk loaders; unloaded time should not age the entity.

For rollback, create a PERMANENT gold drop with `Age:290s,Lifespan:300`, wait three seconds, save, disable in the CFG and reenter **with the mod still installed**. The original deadline has elapsed, so the restored item may expire immediately. Repeat with a long unexpired custom lifespan and two saves. Test dimension exclusion and switching to VANILLA as additional release paths. Unknown NBT/conflicts and other mods' expiry handlers require targeted modpack checks.

## Dedicated server and multiplayer

- Start a test dedicated server with its own CFG and the JAR; check startup, status, clear and one scheduled cycle, with no graphical-class loading failures.
- Test several players: status without OP, clear at permission level 2, console commands, one shared countdown and one result per cycle.
- Check clients with/without the mod against the modded server, and a modded client against a server without it. Local GUI edits must not affect remote settings or grant remote administration.
- Test reconnects, server restarts, multiple loaded dimensions, policy-protected drops and full-modpack interactions. Record the exact JAR hash, loader/Java versions, mod list, settings, expected/observed results and relevant log excerpts.

Before sharing logs or source archives, remove private server addresses, account identifiers and absolute personal paths from the copies intended for publication. Preserve original evidence locally.
