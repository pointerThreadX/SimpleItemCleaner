# Technical reference

SimpleItemCleaner 1.0.0 targets Minecraft 1.12.2 and Forge 14.23.5.2864. Mod ID: `simpleitemcleaner`; Java package: `com.creator01.simpleitemcleaner`; public author: `creator01`.

## Configuration reference

The instance/server owns `config/simpleitemcleaner.cfg`. Singleplayer worlds in one instance share this file. The GUI's six groups present the same 14 properties stored in the three original CFG categories.

| GUI group | CFG key | Default | Meaning / validation |
| --- | --- | --- | --- |
| General | general.enabled | true | Enable cleanup and natural-expiration protection |
| Cleanup | general.intervalSeconds | 300 | 1..86400 seconds between cleanups |
| Cleanup | general.minimumAgeSeconds | 180 | 1..86400 seconds; younger drops are excluded |
| Cleanup | general.excludedDimensions | empty list | Signed integer IDs; excludes cleanup **and expiration protection** |
| Warnings | messages.warningsEnabled | true | Broadcast countdown warnings |
| Warnings | messages.warningSeconds | 600, 300, 180, 60, 30 | Signed integers; ignore values <=0 or >=interval; deduplicate active times |
| Warnings | messages.warningMessage | See below | Literal text, at most 1024 characters; `{time}` becomes remaining seconds, e.g. `30s` |
| Warnings | messages.warningColor | RED | A Minecraft text color, not a formatting-only code |
| Results | messages.resultsEnabled | true | Broadcast cleanup results, including zero removals |
| Results | messages.resultMessage | See below | Literal text, at most 1024 characters; placeholders listed below |
| Results | messages.resultColor | GREEN | A Minecraft text color, e.g. GOLD, WHITE, DARK_RED |
| Policies | policies.vanilla | empty list | Registered names with optional metadata |
| Policies | policies.permanent | empty list | Same syntax; overrides VANILLA |
| Logging | general.logStatistics | true | Cleanup statistics and lifecycle session counters |

English defaults for new or missing message properties, independent of the client language. Existing configured messages are never translated or replaced; custom text may use any language:

```text
[SimpleItemCleaner] Clearing dropped items in {time}!
[SimpleItemCleaner] Items removed: {count} | {entities} entities | {duration} ms
```

Empty lists and empty messages are accepted. Use the corresponding enabled flags to suppress public messages; administrative `clear` feedback remains separate. Unknown placeholders remain literal. Unknown integer dimension IDs are allowed and only have an effect if that dimension exists. Invalid booleans, interval/age bounds, colors, list types or item rules disable cleanup/protection for the server session with a diagnostic. Malformed files are not silently replaced with defaults.

## Components and session lifecycle

All paths below are under `src/main/java/com/creator01/simpleitemcleaner`.

| Component | Responsibility |
| --- | --- |
| SimpleItemCleaner | Common registration, session events and command registration |
| config/ModConfig | Validated, immutable server-session snapshot |
| config/ConfigEditorSession | Independent local draft and atomic file save |
| client/ConfigGuiFactory, ConfigScreen | Native Forge GUI, translations, footer layout and input handling |
| policy/ItemPolicy, PolicyResolver | Item rules and precedence |
| cleanup/CleanupClock, CleanupController | Server tick countdown, warnings, results and reset |
| cleanup/CleanupService, ItemRules | Eligibility, loaded-world traversal and removal |
| cleanup/CleanupResult | Long counters, duration and placeholders |
| command/SimpleItemCleanerCommand | Public status and permission-level-2 clear |
| lifecycle/* | Full-age persistence, lifespan ownership and safe rollback |

`preInit` registers the capability and event handlers once. `FMLServerAboutToStartEvent` clears session state and rereads the CFG. `FMLServerStartedEvent` starts a full interval using the new snapshot. Stopping clears the server reference and clock; stopped also resets counters and disables the active snapshot. Saving in the GUI never publishes live server settings. Reentering the same world therefore reloads settings without a process restart or duplicate handler registration.

One `ServerTickEvent.END` scheduler serves the logical server, across already-loaded dimensions. Twenty ticks represent one configured second. No watcher, background cleanup thread or extra entity scan runs while the menu is open.

## Cleanup and policies

Only live, nonempty `EntityItem` drops are candidates. The cleaner checks dimension, loaded chunk, ownership conflicts, item policy and minimum age. It does not load chunks to find items or cancel pending chunk unloads. Candidate lists are temporary and discarded per dimension; there is no global entity-reference collection.

CONTROLLED is the fallback, with age-protected cleanup and extended natural expiration. VANILLA excludes the item from cleanup and preserves its original expiration. PERMANENT excludes cleanup and extends expiration while protection is active. Precedence is **PERMANENT > VANILLA > CONTROLLED**.

Rules use `namespace:item`, `namespace:item@*`, or `namespace:item@metadata`. Metadata must be a nonnegative integer within Java int range. Names must exist in the item registry; `minecraft:iron` is invalid, while `minecraft:iron_ingot` is valid. Registry names avoid reliance on numeric IDs, but compatibility with any particular registry-altering mod still requires testing in that modpack.

Disabling the mod, excluding a dimension or changing an item to VANILLA stops protection and permits restoration of owned extensions. Unloaded items are reconciled when their chunks load naturally. A restored deadline that has already passed can expire immediately. Keep the JAR installed while reversing extensions: removing it cannot execute rollback.

`clear` uses the same rules as scheduled cleanup, requires permission level 2 and restarts the interval. `status` is public. Neither bypasses policies or minimum age. Inventories, machines, mobs, players and XP are not processed, but a dropped container remains an eligible item; its contents are not inspected separately.

## Counts and duration

`CleanupResult` holds long counts for stack units, removed entities and elapsed nanoseconds. The stack count is captured immediately before removal, but only a confirmed live-to-dead transition contributes to totals. A stack of 64 contributes 64 units and one entity. Already-dead, protected, empty or unsuccessfully removed drops do not count.

`System.nanoTime()` covers loaded-dimension traversal, selection, eligibility/lifecycle checks and removal. It excludes initial enable/thread checks, message formatting, logs and broadcasting. Duration uses exact BigDecimal conversion and HALF_UP rounding to three decimal places with a comma separator: 83,000 ns → `0,083` ms; 1,500,000 ns → `1,500` ms. Very short operations can round to `0,000`.

Result placeholders are `{count}`, `{entities}` and `{duration}`. Repeated placeholders are replaced; unknown ones stay literal. This measures one cleanup operation, not total tick performance, memory usage or a modpack benchmark.

## NBT, age and lifespan ownership

Persistence is per entity through `ICapabilitySerializable<NBTTagCompound>`, under **`ForgeCaps/simpleitemcleaner:lifecycle`**. Format version 1 stores:

| NBT field | Type | Purpose |
| --- | --- | --- |
| Version | int | Format identifier |
| FullAge | int | Current full item age at serialization |
| OwnsLifespan | byte/boolean | Whether the mod owns an extension |
| OriginalLifespan | int | Deadline before the first owned extension |
| ExpectedLifespan | int | Last deadline installed by this mod |
| Conflict | byte/boolean | Suspend intervention when ownership/state is unsafe |

Vanilla serializes Age as a short. The entity-bound provider reads the current int age on **every save**, even between expiration events. An event-only custom tag would become stale between events, which is why a serializable capability is used. The provider also retains full-age serialization after releasing an extension, preserving long custom lifespans across later saves.

Capabilities deserialize before EntityItem's vanilla fields. Restoration is deferred to `EntityJoinWorldEvent`, and only applies if the observed age matches the saved age or its short conversion. Distinguishable external edits mark a conflict instead of being overwritten. Field access is cached and supports `age` and `field_70292_b`, avoiding the client-only age getter.

At LOWEST priority, `ItemExpireEvent` handling respects an event already canceled by another mod. Protection requests a positive extension to `age + 1200`, rather than resetting every item to 6000 ticks. This spaces later expiration events and remembers custom original lifespans. A foreign lifespan write breaks ownership: extension, cleanup and rollback are suspended for that entity. Unknown NBT is retained without authorizing intervention.

There is no migration from other namespaces or recovery of age already lost before a snapshot existed. The signed int representation also has a finite limit; unsafe extensions are refused. PERMANENT is not invulnerability or a mathematically unlimited lifetime. Collection, damage, explosions, subclass overrides and removals by other mods remain possible. Merges follow the surviving entity's vanilla age. Asynchronous serializers and indistinguishable external writes require separate compatibility analysis.

## GUI transaction and resource loading

`@Mod(guiFactory=...)` names the factory as a string. Graphical types are confined to `client` and marked `@SideOnly(CLIENT)`; common code has no graphical type dependency. `acceptableRemoteVersions="*"` keeps installation optional on the opposite side. Cleanup only runs on the logical server; the mod must load on a physical client for integrated-server support.

The GUI uses Forge GuiConfig, ConfigElement, native array editors and native Undo/Defaults/Apply Globally. Category Done returns to the draft; only Save on the root writes the CFG. Cancel or Esc on the root discards the entire draft. Fields are read-only during an active world. The GUI edits only local settings, with no network synchronization or remote administration.

Opening an editor does not write an existing file or create a missing file. On Save, the transaction snapshots property values, collects controls, validates, checks for external file changes, serializes UTF-8 and atomically replaces the CFG using a neighboring temporary file. Failure restores backing properties while keeping visible edits available for correction, preserving the native Undo baseline.

Local Undo/Defaults apply to the selected category; Apply Globally includes children. Scalar entry subclasses reject invalid input instead of allowing native silent clamping/default substitution. File errors retain the current CFG; no truncating fallback is used. A filesystem without atomic replacement produces an error and may leave a `.pending` recovery file. A CFG already invalid at opening requires manual correction. Successful serialization can normalize comments/formatting; opening/canceling preserves bytes.

The resource pack requires **pack_format 3** in `pack.mcmeta` for lowercase `en_us.lang`; this avoids Forge's legacy uppercase-language fallback. This is the only bundled language resource. Minecraft's language manager loads English before the selected locale, so the mod's labels, tooltips and guidance remain English with a Portuguese or other client language. Native Minecraft/Forge captions still follow their own translations. Footer captions are measured and native buttons wrap into rows for narrow scaled screens.

## Build and maintenance boundaries

Build with JDK 8, Gradle Wrapper 5.6.4 and ForgeGradle 3.0.197. Bytecode is Java 8 (major 52). Mappings are `snapshot_20171003-1.12`; `artifactural:1.0.13` is pinned to avoid the plugin's dynamic dependency selector. No added libraries, mixins or coremods are used.

The distributable is `build/simpleitemcleaner/libs/simpleitemcleaner-1.0.0.jar`, finalized by reobfJar. ForgeGradle also writes its fixed intermediate `build/reobfJar/output.jar`; do not distribute that scratch file. ZIP bytes may vary across legacy reobfuscation runs, so validation should inspect entry contents as well as whole-file hashes.

Keep source, tests, Wrapper, declared dependencies, six scripts, README, this reference, [TESTING.md](TESTING.md), MIT and Wrapper license/notice. `.gradle-user-home` is strictly a local dependency cache, never public project content. Runtime directories, logs, build outputs and backups are not source-release inputs. Git is not used by the build or verification scripts.

## Security and privacy boundaries

The production source and final class constants contain no process execution, direct network/telemetry API, environment/account collection, native library loading or added dependencies. Normal server chat uses Minecraft's existing transport. Only the Forge-provided CFG and a neighboring save file are accessed directly; item NBT remains owned by Forge's world serialization. Tests use local fixtures; Gradle/Wrapper execute Java and download official build dependencies when online.

World mutation stays on the logical server thread. There is one session server reference, cleared on stop, and no permanent global entity collection. `clear` requires permission level 2; local configuration editing grants no remote authority. Invalid configuration disables the session, unsafe lifecycle ownership prevents intervention, and GUI save failures preserve the existing file.

Local configuration and the runtime directory are trusted administrator inputs, not a sandbox against another local process. CFG reads and policy lists have no total size cap; exceptionally large local inputs can consume memory. External-change detection is optimistic and cannot lock out a simultaneous writer between comparison and atomic replacement. Read-only disks and unsupported atomic moves still require operator correction. Privacy checks cover reviewed inputs and recognizable patterns, not arbitrary encoded secrets or compromised dependencies. Revalidate the exact runtime/modpack after dependency or behavioral changes.
