# Farground

A Fabric mod for Minecraft 26.2 and 26.3 that fills in distant terrain for
[Voxy](https://modrinth.com/mod/voxy) or [Distant Horizons](https://modrinth.com/mod/distanthorizons)
in the background, nearest first, while staying out of your way. Works in singleplayer, when
hosting a LAN world, and on dedicated servers.

**Download:** [GitHub releases](https://github.com/Benny1526/farground/releases) ·
[CurseForge](https://www.curseforge.com/projects/1767570) · [Modrinth](https://modrinth.com/mod/farground) (both in review)

## Requirements
| | 26.2 | 26.3 |
|---|---|---|
| Jar | `farground-1.7.0+26.2.jar` | `farground-1.7.0+26.3.jar` |
| Fabric Loader | 0.18+ | 0.18+ |
| LOD mod (one or both) | Voxy 0.2.19-beta, or Distant Horizons 3.3.3 | Voxy 0.2.20-beta, or Distant Horizons 3.3.4 (tested) |
| Optional | Mod Menu + Cloth Config (settings screen) | same |
| Dedicated server | Distant Horizons, or Voxy Server (+ Voxy, Sodium) | same |

Without a LOD mod, Farground stays idle and says so.

## Languages
English, Spanish (Spain and Mexico), Brazilian Portuguese, Russian, German, French,
Simplified Chinese and Japanese. All text is in `assets/farground/lang/`; to add a language,
copy `en_us.json`, translate the values (keep every `%s`) and send it in. Messages carry the
English text too, so players without Farground (for example on a server) never see raw keys.

## Distant Horizons
Farground hands each finished chunk to Distant Horizons through DH's public API, the same
way DH receives chunks Minecraft loads normally. While Farground runs it pauses DH's own
distant generator (through DH's API, so nothing is written to DH's settings; DH gets its
setting back when Farground stops), so the same land isn't generated twice and Farground's
smooth-play limits apply. Turn **Pause Distant Horizons' own generator** off in the
settings to let both run.

DH's update queue throws away the furthest chunks when it overflows, so Farground waits
whenever that queue is more than half full ("letting Distant Horizons catch up").

The automatic full-detail distance is worked out from Voxy's settings; with only Distant
Horizons it uses the manual value (128 chunks by default).

## Speed
Benchmark: same seed, same 81x81-chunk area, the full "Everything Voxy" 26.3 pack plus
Terralith and structure mods, RTX 4060 laptop / i7-13650HX.

| Generator | Chunks per second |
|---|---|
| Chunky 1.5.3 | 83-86 |
| Farground 1.1+ (full detail) | 100-106 |
| Farground 1.4 with terrain-only far rings | faster still (see below) |

What makes it fast:
- **Non-blocking requests**, topped up the moment a chunk finishes.
- **Tile order**: 8x8 tiles, nearest first, so neighbouring chunks reuse each other's work.
- **Short hold**: finished chunks stay loaded 10 seconds for the next tile.
- **Fast lighting**: sky light from terrain height instead of Minecraft's lighting pass.
- **Terrain-only far rings**: past the full-detail distance, only the land shape is generated
  (no trees, ores, caves or structures). From thousands of blocks away it looks the same.
  If you later come closer, those chunks are regenerated with full detail.
- **Automatic full-detail distance**: by default it works out where Voxy's blocks get too
  coarse to show trees (from Voxy's detail setting, your screen height and FOV) and stops
  generating trees there. The Nether and the End use a short distance (32 chunks) since the
  ceiling and the void hide distant detail.
- **Look direction first**: each new ring starts with the part in front of you.
- **Turbo while away**: when the window isn't focused, or you haven't moved for 60 seconds,
  it ignores frame rate and runs up to 1.5x faster.
- **Auto-tune**: the first time you're away, it tries several speeds for about 1.5 minutes
  and keeps the smallest one that is within 8% of the fastest. Run it again with
  `/farground autotune`.

## Staying out of your way
- Slows to a trickle while you move or look around, then ramps back up gently once you stand still.
- Backs off when your frame rate dips below its recent average, or the server tick gets slow.
- Watches your 1% lows: a few very slow frames (the hitches you feel, even when the average
  FPS looks fine) make it back off hard and hold for a few seconds.
- Waits if Java memory is still above 90% full right after a cleanup (a real shortage),
  so it never pushes the game into long garbage-collection pauses.
- Waits until the chunks around you have loaded (joining a world, flying into new land).
- Pauses on battery power (laptops), and while Voxy's own queue is backed up.
- When flying fast, it generates ahead of where you are heading.

## Commands
Singleplayer: anyone. Dedicated server: operators (`/farground status|pause|resume|radius|speed|area|prune`).

| Command | What it does |
|---|---|
| `/farground` | Progress, speed, and what it is doing |
| `/farground pause` / `resume` | Stop or start generating |
| `/farground radius <chunks>` | Generate a fixed distance (8-4096), instead of matching your LOD mod |
| `/farground radius auto` | Match your LOD mod's render distance again (the default) |
| `/farground fulldetail <chunks>` | Full detail out to here, land shape only beyond (0-4096) |
| `/farground speed <n>` | Most chunks at once while you stand still |
| `/farground movingspeed <n>` | Most chunks at once while moving (0 = pause while moving) |
| `/farground area <x> <z> <radius>` | Fill a square around a block position at full detail, Chunky-style |
| `/farground area cancel` | Cancel queued area jobs |
| `/farground autotune` / `autotune cancel` | Find the best speed for this PC (stand still ~1.5 min) |
| `/farground prune` / `prune cancel` | When the world closes, delete saved chunks you were never near (see Disk use) |
| `/farground hud off\|generating\|always` | Progress display in a screen corner |
| `/farground reset` | Forget progress for your current dimension |

## Settings
Everything is in **Mod Menu -> Farground** (needs Cloth Config), or `config/farground.json`:

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `autoRadius` | `true` | Generate exactly as far as Voxy or Distant Horizons draws |
| `radiusChunks` | `256` | How far out to generate when `autoRadius` is off |
| `fullDetailRadius` | `-1` (auto) | Trees, ores and structures out to here; land shape only beyond |
| `autoDetailVoxelSize` | `4` | For auto: stop trees once Voxy draws blocks this big |
| `otherDimensionFullDetail` | `32` | Full-detail cap in the Nether and the End |
| `afkTurbo` | `true` | Run faster while you are away |
| `afkSeconds` | `60` | Seconds without moving before you count as away |
| `memoryPausePercent` | `90` | Wait while Java memory is above this (0 = never) |
| `autoTuneOnFirstAfk` | `true` | Run the speed tune once, the first time you are away |
| `fastLighting` | `true` | Sky light from terrain height (faster) |
| `maxConcurrent` | 4x CPU threads (16-128) | Most chunks at once while standing still |
| `activeConcurrent` | `6` | Most chunks at once while moving (0 = pause) |
| `idleSeconds` | `3` | Seconds still before speeding back up |
| `rampPerSecond` | `4` | How fast it climbs back to full speed |
| `frameRateAware` | `true` | Back off when FPS dips |
| `pauseOnBattery` | `true` | Pause while a laptop is unplugged |
| `flyAhead` | `true` | Generate ahead when flying fast |
| `saveUnvisitedChunks` | `true` | `false` = don't save chunks you never visited (experimental, see below) |
| `hud` | `WHILE_GENERATING` | `OFF`, `WHILE_GENERATING`, `ALWAYS` |
| `hudCorner` | `TOP_LEFT` | Corner for the progress display |
| `announceDone` | `true` | Chat message when everything in range is done |
| `tickBudgetMs` | `40` | Back off when the server tick is slower than this |
| `holdTicks` | `200` | Keep finished chunks loaded this long so neighbours reuse them |
| `recenterChunks` | `8` | Restart from your position after moving this far |

## Disk use
Generated chunks are saved like explored ones (a few GB at 256 chunks). Setting
`saveUnvisitedChunks` to `false` skips saving chunks you have never been near: Voxy already has
their LODs, and they are re-created if you go there. **Experimental**: trees or structures that
cross the edge between saved and re-created land can end up cut off.

The safer way to free space is `/farground prune`. It runs once, after the world has closed
and saved, and:
- deletes only chunks that never finished generating (a chunk you have been near is "full"
  and is never touched),
- keeps everything within 2 chunks of a full chunk, so nothing at the edge of explored land
  gets cut,
- copies every region file it changes to `farground-prune-backup` in the world folder first.

Voxy keeps the LODs, so the view doesn't change. If you travel into pruned land, Minecraft
generates it again (same seed, same terrain). Delete the backup folder once you are happy.

## Dedicated servers
Put Farground and your LOD mod on the server:
- **Distant Horizons:** Farground + Distant Horizons. Players need Distant Horizons.
- **Voxy:** Farground + Voxy + Sodium + Voxy Server. Players need Voxy, Sodium and Voxy Server.

Farground generates finished chunks around online players, throttled by server tick time,
and the LOD mod stores them and sends them to players. Operators can fill an area ahead of
time with `/farground area <x> <z> <radius>` (also from the server console, with nobody
online); progress messages go to the server log. Tested on 26.3 with both: an 81x81-chunk
area at full detail took under a minute. The automatic radius, terrain-only rings, fast
lighting, the progress display and FPS checks are singleplayer-only.
## Existing worlds
Farground loads chunks you have already explored straight from disk (no regenerating), so
running it in an old world imports that terrain into Voxy too.

## Building
```
gradlew build -Pmc=26.2
gradlew build -Pmc=26.3
```
The jars end up in `build/libs/`. Library versions for each Minecraft version are in
`versions/<mc>.properties`.

If Gradle fails with "Unable to establish loopback connection" on Windows, point Java's local
sockets somewhere outside the temp folder first:
```
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$env:USERPROFILE\.gradle\sock"
```
Very long folder paths can also break the build; building from a short path helps.
