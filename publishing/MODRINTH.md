# Farground

**Fast, lightweight background terrain generation for [Voxy](https://modrinth.com/mod/voxy)
and [Distant Horizons](https://modrinth.com/mod/distanthorizons).** Fills in the distant
landscape around you, nearest first, while you play, and stays out of your way.

## Why Farground
- **Fabric or NeoForge.** On NeoForge it works with Distant Horizons (Voxy is Fabric-only).
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
- **Gets the most out of C2ME.** With C2ME, the speed tune works in steps of C2ME's thread
  count, and `/farground status` warns if C2ME is set to fewer threads than its default,
  which quietly caps how fast any generator can go.
- **Front first.** New land in the direction you're looking appears first.
- **Small saves.** Far land lives in your LOD mod instead of your save. In a new world filled out to
  128 chunks, the save's chunk files were 59 MB instead of 305 MB, with no loss of speed, and trees
  at the edge of what's saved stay whole.
- **Easy settings.** Every option has a tooltip with what it does and a Low / Medium / High
  performance rating (Fabric: Mod Menu + Cloth Config; NeoForge: Cloth Config, from the
  Mods list).
- **Matches your LOD mod.** Generates exactly as far as Voxy or Distant Horizons draws,
  and follows it when you change your render distance.
- **In your language.** English, Spanish, Portuguese (Brazil), Russian, German, French,
  Simplified Chinese and Japanese.
- **Small.** On Fabric it needs only Fabric API plus your LOD mod; on NeoForge, only
  Distant Horizons.

## How to use
Install it next to Voxy or Distant Horizons and open your world. That's it. The corner
display shows progress and roughly how long is left; `/farground` shows details.

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
- C2ME, including its faster chunk threads.

## Versions
Minecraft 26.2 and 26.3, Fabric and NeoForge. Java 25.
- Fabric: Fabric API, plus Voxy 0.2.19+ or Distant Horizons 3.3.3+.
- NeoForge: Distant Horizons 3.3.3+.

## Source
[github.com/Benny1526/farground](https://github.com/Benny1526/farground) (MIT). Bug reports and translations welcome there.
