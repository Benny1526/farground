# Changelog

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
