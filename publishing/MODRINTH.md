# Farground

**Fast, lightweight background terrain generation for [Voxy](https://modrinth.com/mod/voxy)
and [Distant Horizons](https://modrinth.com/mod/distanthorizons).** Fills in the distant
landscape around you, nearest first, while you play, and stays out of your way.

## Why Farground
- **Works with either LOD mod.** Voxy, Distant Horizons, or both. With Distant Horizons,
  Farground takes over the generating and DH just draws (DH's own generator is paused while
  Farground runs; your DH settings aren't changed).
- **Faster than Chunky** in our benchmark (100-106 vs 83-86 chunks/s at full detail, same
  seed and pack), and about 3x faster again for the far rings, where it only generates the
  land shape. With Distant Horizons: about 340 chunks/s in testing.
- **Made for LODs.** Past the distance where trees can't be seen anyway, it skips them.
  With Voxy that distance is worked out automatically from Voxy's detail setting, your
  screen and FOV.
- **Doesn't fight your frame rate.** Slows down while you move, backs off on FPS drops and
  stutters (1% lows), on slow server ticks, when memory is really low, and on battery.
- **Runs faster when you look away.** Alt-tab or stand still for a minute and it speeds up.
- **Tunes itself.** The first time you're away it finds the best speed for your PC.
- **Front first.** New land in the direction you're looking appears first.
- **Saves disk space.** `/farground prune` removes chunks you were never near, safely, with
  a backup.
- **Easy settings.** Every option has a tooltip with what it does and a Low / Medium / High
  performance rating (Mod Menu + Cloth Config).
- **Matches your LOD mod.** Generates exactly as far as Voxy or Distant Horizons draws,
  and follows it when you change your render distance.
- **In your language.** English, Spanish, Portuguese (Brazil), Russian, German, French,
  Simplified Chinese and Japanese.
- **Small.** Needs only Fabric API plus your LOD mod.

## How to use
Install it next to Voxy or Distant Horizons and open your world. That's it. The corner
display shows progress; `/farground` shows details.

| Command | What it does |
|---|---|
| `/farground` | Progress and what it's doing |
| `/farground pause` / `resume` | Stop or start |
| `/farground radius <chunks>` / `auto` | Fixed distance, or match your LOD mod (default) |
| `/farground area <x> <z> <radius>` | Fill an area at full detail, Chunky-style |
| `/farground autotune` | Find the best speed for your PC |
| `/farground prune` | Free disk space when the world closes |

## Works with
- Singleplayer and LAN hosting.
- Dedicated servers with Distant Horizons, or with Voxy and
  [Voxy Server](https://modrinth.com/mod/voxyserver).
- Existing worlds: chunks you've explored are read from disk, not regenerated.
- Terrain mods (Terralith, Tectonic, etc.) and structure mods.

## Versions
Minecraft 26.2 and 26.3, Fabric. Java 25. Voxy 0.2.19+ or Distant Horizons 3.3.3+.

## Source
[github.com/Benny1526/farground](https://github.com/Benny1526/farground) (MIT). Bug reports and translations welcome there.
