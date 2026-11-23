# Changelog

## 1.11.0 - far land no longer fills your disk, better with C2ME
- **Far chunks aren't saved any more.** Most of what Farground fills in is far land where it only
  generates the land shape (no trees). Your LOD mod already has those chunks, and Minecraft's
  land-shape step only writes into its own chunk, so they are re-created exactly the same if you
  ever go there.
- **Leftover early copies are cleaned up.** Minecraft saves early versions of the chunks around
  the ones being generated. Farground deletes them once it is well past them and shrinks the
  region files when the world closes. Measured on a new world filled out to 128 chunks with full
  detail to 16: 59 MB after closing, against 305 MB with everything saved, and no slower.
- **Nothing gets cut off.** Chunks with trees, and the chunks next to them, are always saved,
  because trees spill into neighbouring chunks. Tested in-game: an area filled in by Farground,
  unloaded and then loaded again has the same trees at the edges as a world Farground never
  touched, within the small differences any two runs have; a control that deleted saved chunks
  with trees lost about a quarter of the leaves along its edge.
- **Replaces the experimental `saveUnvisitedChunks`** setting, which also skipped chunks with
  trees and could cut them off. The new setting is `saveFarTerrain` (off by default).
- **`/farground prune` uses the same safe rule.** It now deletes only land-shape chunks with no
  trees next to them. Before, it kept everything within 2 chunks of land you had visited, but
  could cut trees along the edge of what it kept. Use it to free the space in worlds generated
  before this update: in a test world, 46 MB -> 12 MB.

- **Better with C2ME.** With C2ME installed, every chunk is built on C2ME's thread pool, so
  its size caps the speed. The auto-tune now tries multiples of C2ME's thread count (for 13
  threads: 13, 26, 39, 52, 78, 104, 130 at once), remembers the count it tuned for, and when
  the count changes it scales the setting right away and tunes again the next time you are
  away. Worlds tuned before this update tune once more.
- **Warning for a low C2ME thread count.** If C2ME is set to fewer world-gen threads than its
  own default for your PC, `/farground status` says so in yellow, with the setting to change,
  and the game log warns at startup. This was the cause of Turbo seeming slow on one PC: C2ME
  was set to 6 threads instead of 13, and every speed from 24 to 128 at once came out at
  about 40 chunks/s.

- **Up to 3.5x faster world generation with lithostitched** (26.3). lithostitched, a library
  used by many worldgen mods (Lithosphere among them), wraps the world's biome source, and its
  wrapper doesn't have 26.3's new whole-chunk biome method. Minecraft then works out a chunk's
  biome climate one point at a time instead of in one batch: 2,304 separate samples per chunk
  with Lithosphere, about 60 ms of work per chunk. Farground now hands such biome sources the
  climate computed in one batch, the way Minecraft does for its own. This speeds up all chunk
  generation in those packs, not only Farground's, and the biomes are the same. Tested with
  lithostitched 2.0.6 and Lithosphere 1.9: all 921,600 biome points of 400 chunks spread
  across 31,000 blocks (41 biomes) came out identical; biomes took 4.7 ms per chunk instead of
  58 ms, and the same land area generated at 47 chunks/s instead of 13. Vanilla biome sources
  are left alone. New setting `fastModdedBiomes` (on) turns it off if a biome mod misbehaves.
  Chunks being that much cheaper changes the best speed setting, so a world tuned before this
  tunes again once, the next time you are away. In a full 26.3 pack (C2ME, Lithosphere,
  Biolith biomes, Streams Reflowing) a 125x125-chunk full-detail area took 2:35 instead of 4:20.

- **Fixed: `/farground area` never said it was done.** A finished area waited for every chunk
  in the dimension to be idle, but generation around you starts meanwhile and keeps chunks in
  progress, so the "done" message never came and a second queued area never started. It now
  waits only for its own chunks.

- **No access widener any more** (Fabric). Its one entry, the non-blocking chunk request, is
  now called through a method handle. With an access widener, Loom rewrites the Minecraft jar
  on every fresh build, and on-access antivirus such as Bitdefender blocks that by holding the
  new jar open, so the mod couldn't be built on those PCs. No change in game.

- **Quieter progress line.** Once everything in range is done, it no longer pops up for the small
  strips of new land that come into range as you move around. It comes back if you travel far
  enough to leave a real job (under 75% of your range done and over 20,000 chunks to go), and for
  area jobs, pausing and speed tuning.

## 1.10.0 - time-left estimate, fixed dark distant terrain
- **Fixed: distant terrain drawn black with Voxy** ([#1](https://github.com/Benny1526/farground/issues/1)).
  Snow-covered land was the worst: snow layers, paths and other thin blocks counted as solid
  ground in the fast lighting, so their tops got no sky light, and slopes under overhangs went
  dark too. Fast lighting now follows Minecraft's own sky light rules inside each chunk: full
  light down through air, a little lost in leaves, water and snow, none in solid blocks, then
  spreading sideways under overhangs. Tested in-game against Minecraft's real lighting on the
  same snowy area: the views now look the same, with an average difference of 0.1 of 15 light
  levels and about 0.2 ms of work per chunk. Land generated before this update keeps its dark
  look; `/farground reset` makes Farground go over it again and send Voxy the new light.
- **How long it will take:** the progress line on screen now says roughly how long is left
  around you, for example "Farground 42% · 310/s · ~1h 20m left". `/farground status` shows it
  too, along with the average speed it's based on.
- The estimate uses the average speed over the last few minutes, so a short burst or pause
  doesn't swing it around. It appears about 30 seconds after generation starts and is hidden
  while Farground is paused (on battery, low memory, or by you).
- Tested in-game: the estimate appears after 30 seconds and settles as the speed settles.

## 1.9.0 - first full release (no longer beta)
Release checks before leaving beta: crash safety, low memory, unusual worlds, long sessions.
- **Settings can't be lost in a crash:** the settings file is now written to a temporary file
  and swapped in, so a crash or power cut mid-save can't leave it empty (Farground would
  quietly have gone back to its defaults). Two saves at once (settings screen and auto-tune)
  can no longer mix.
- **Progress saves on all drives:** on drives that can't swap files in one step (some network
  shares and sync folders) the progress file could fail to save every time. It now falls back
  to a normal replace.
- **Linux desktops never pause "on battery":** a wireless mouse, keyboard or controller whose
  battery doesn't report itself as a device could make a Linux desktop look like a laptop on
  battery. Without a charger listed, a computer now always counts as plugged in.
- **Long-running servers:** per-player data is cleared when players leave, so a server with
  many visitors over weeks doesn't keep growing.
- **More robust requests:** if another mod's generator fails the moment a chunk is requested,
  that request no longer counts as "in progress" forever (which would slowly have slowed
  generation to a stop).
- Tested: the game force-killed mid-generation (3 times, 205,000 chunks on disk, all readable,
  progress kept, no gaps in the distance); 3 GB of memory (146,000 chunks, no problems);
  a 47-minute non-stop session (694,000 chunks, memory levels off, no warnings);
  Amplified, Superflat, single-biome and large-biome worlds; Overworld, Nether and End; a small
  world border; reopening worlds; old, damaged and empty settings files.

## 1.8.0
- **NeoForge support** (26.2 and 26.3) with Distant Horizons. Same features, settings and
  commands as on Fabric; the settings screen needs Cloth Config. Tested on NeoForge 26.3.0.99
  with Distant Horizons 3.3.4: about 180 chunks a second handed to DH. Voxy is Fabric-only, so
  it isn't supported on NeoForge, and there is no Forge version (no LOD mod runs on Forge for
  26.x).
- **Faster on high-refresh desktops:** Farground slowed down whenever the frame rate dipped 15%
  below its average. At 200+ FPS that happens all the time without being noticeable, so it now
  only counts a dip that also makes each frame at least 1.5 ms slower.

## 1.7.1
- **Fixed:** desktops could stay "paused on battery". Farground now asks the operating system
  whether the computer itself is on battery (on Windows, the same answer as the taskbar battery
  icon). Desktops, UPS backup units and the batteries in wireless mice, keyboards and headsets
  no longer count. When the state can't be read, Farground treats the computer as plugged in.

## 1.7.0
- **Automatic radius:** Farground now generates exactly as far as your LOD mod draws,
  read from Voxy's or Distant Horizons' render distance (the larger one if you have both),
  and follows it when you change it. New setting "Match my LOD mod's render distance"
  (on by default); `/farground radius <chunks>` switches to a fixed radius and
  `/farground radius auto` switches back.
- **Translations:** Spanish (Spain and Mexico), Brazilian Portuguese, Russian, German,
  French, Simplified Chinese and Japanese. Every message, tooltip and status line is in the
  language files; players without Farground (for example on a server) see English.
- Tested on 26.2 with Distant Horizons 3.3.3 (it worked the same as on 26.3).
- Tested on real dedicated servers (26.3): with Distant Horizons and with Voxy + Voxy Server,
  an 81x81-chunk area generated at full detail in under a minute and the LOD mod stored it.
- Announcements ("Area ... is done", speed tune results) also go to the server log, so a
  server console sees them even with nobody online.

## 1.6.0
- **Distant Horizons support.** Farground now works with Voxy, Distant Horizons, or both.
  Voxy is no longer required. Chunks go to DH through its public API; tested with DH 3.3.4
  on 26.3 (about 340 chunks/s, LODs stored and lit correctly).
- While Farground runs, DH's own distant generator is paused (not saved to DH's settings),
  so land isn't generated twice. New setting to turn this off.
- Farground waits when DH's update queue is half full, so DH never throws chunks away.
- Dedicated servers: works with Distant Horizons as well as Voxy Server.
- With no LOD mod installed, Farground stays idle and `/farground` says what to install.

## 1.5.4
- Every setting now has a hover tooltip: what it does, its default, and a colour-coded
  performance impact (Low / Medium / High) that updates as you move the slider or flip the
  switch.
- Distance sliders show plain chunk counts.

## 1.5.3
- Settings screen redone with sliders: generate distance (32-1024 chunks, shown in km too),
  full-detail distance, Nether/End detail, speed, tick limit, away time, memory limit.
- "Automatic full detail distance" switch: on by default; turn it off and the full-detail
  slider (default 128 chunks) takes over. `/farground fulldetail auto` switches back.
- New switches: generate in the Overworld / Nether / End, tune speed again, and free disk
  space when the world closes (same as `/farground prune`).

## 1.5.2
- Fixed "waiting for memory" showing up all the time. Memory is now measured right after
  Java's last cleanup (what is really in use), not the current fill level, which collectors
  like ZGC and G1 keep high on purpose.
- Quieter chat: "All distant terrain ... is ready" appears once per dimension each time you
  open the world, instead of again every time you travel and new land finishes.
- The automatic speed tune no longer posts in chat (the corner shows "tuning"). Running
  `/farground autotune` yourself still reports start and result.
- Fixed a possible hang on the "Saving world" screen when quitting while many chunks were
  still generating. Farground now lets them finish first (usually well under a second,
  at most 15 seconds).

## 1.5.1
- Fixed: right after joining a world you counted as "away", so the speed tune and turbo
  started while you were playing and caused stutter. You now count as away only after
  standing still for `afkSeconds` (60 s).
- The automatic speed tune now only runs while you are away (`/farground autotune` still
  runs as soon as you stand still), and announces itself once instead of every restart.
- Turbo keeps the stutter protection while the game window is on screen.

## 1.5.0
- **Stutter detection**: watches your 1% low frame times, not only average FPS. A few very
  slow frames make Farground back off hard and hold for a few seconds.
- **Automatic full-detail distance** (new default): trees and structures stop where Voxy's
  blocks get too coarse to show them, worked out from Voxy's detail setting, screen height
  and FOV. Old configs using the previous default of 128 switch to automatic.
- **Nether and End**: full detail capped at 32 chunks there (`otherDimensionFullDetail`).
- **Look direction first**: each new ring starts in front of you.
- **Turbo while away**: window unfocused or 60 s without moving → ignores FPS, up to 1.5x faster.
- **Memory pause**: waits while Java memory is above 90% full.
- **Auto-tune**: the first time you're away it tries 7 speeds (~1.5 min) and keeps the best
  for your PC. `/farground autotune` runs it again; `autotune cancel` stops it.
- **`/farground prune`**: when the world closes, deletes saved chunks you were never near
  (only unfinished chunks, never within 2 chunks of a visited one), with a backup of every
  changed file. Voxy keeps the LODs.
- `/farground status` shows the full-detail distance in use, turbo, memory and tune state.
- New settings in Mod Menu for all of the above.

## 1.4.0
- Terrain-only far rings (about 3x faster past the full-detail distance).
- `/farground area`, progress HUD, settings screen (Mod Menu + Cloth Config).
- Optional skip-saving of unvisited chunks; dedicated-server mode with Voxy Server.

## 1.3.0
- Gentle ramp back up after you stop moving (no hitch when you start again).

## 1.2.0
- Slows down while you move or look around.

## 1.1.0
- Non-blocking chunk requests; tile ordering; fast lighting. Faster than Chunky.

## 1.0.0
- First release.
