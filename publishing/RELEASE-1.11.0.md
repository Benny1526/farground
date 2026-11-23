# Releasing Farground 1.11.0

Do these in order. Nothing goes public until step 4.

## 1. Test in-game
1.11.0 has four groups of changes, so test all of them, not just C2ME.

**C2ME (26.3 instance, Fabric):**
- [x] The log has no C2ME warning, and says Farground starts at 26 at once and will tune again.
- [x] `/farground status` shows "C2ME: chunks are built on 13 threads".
- [x] Stand still for about 1.5 minutes: the log shows `Auto-tune results` for 13 to 130 at once,
      and the speed is well above the old 40 chunks/s.
- [ ] Optional: set C2ME to 6 threads, restart, and check the yellow warning in `/farground status`.
      Then set it back to `"default"`.

**Smaller saves:**
- [x] Fill a new world out to a decent radius, close it: the log says region files were shrunk.
- [x] Reopen it and fly to the edge of the full-detail area: trees at the edges are whole.
- [ ] `/farground prune` on an older world: it runs when the world closes, keeps a backup, and the
      world still loads.

**Faster biomes with lithostitched (26.3 instance, which has Lithosphere):**
- [x] The world loads and generates; the speed is well above the 58-83 chunks/s from before.
- [x] Explore a little: biomes and terrain look normal (same as before, no odd patches).
- [x] Optional: a spark profile while it generates no longer shows `Climate$Sampler.sample`
      near the top.

**Other setups:**
- [x] A pack **without C2ME**: no C2ME line in `/farground status`, generation works as before.
- [x] **NeoForge** with Distant Horizons: generates, and the log shows no mixin errors (this checks the new method-handle chunk
      request and Farground's first mixin).
- [x] **26.2** on either loader: the world loads and generates.

## 2. Build the release jars
```powershell
powershell -ExecutionPolicy Bypass -File publishing\build-release.ps1
```
It builds Fabric and NeoForge for 26.2 and 26.3, then copies the four jars to `dist\` and to both
upload kits. Check that the four dates it prints are from today.

## 3. Commit, tag and push
Everything for 1.11.0 is still uncommitted on `main`. Suggested commit message:

```
1.11.0: smaller saves, better with C2ME

- Far terrain-only chunks aren't saved; early copies are cleaned up and region files shrunk
  (new world to 128 chunks: 59 MB instead of 305 MB). saveFarTerrain replaces
  saveUnvisitedChunks; /farground prune uses the same tree-safe rule.
- C2ME: auto-tune levels are multiples of C2ME's thread count, re-tune when it changes, and
  /farground status warns when C2ME runs below its default thread count.
- Fabric: no access widener; getChunkFutureMainThread goes through a method handle, so Loom
  no longer rewrites the Minecraft jar (on-access antivirus blocked that).
- Batched biome climate for biome sources without 26.3's createResolverForChunk (lithostitched):
  same biomes, 47 instead of 13 chunks/s with Lithosphere. Setting fastModdedBiomes.
- Quieter progress display once everything in range is done.
- Community files: issue forms, pull request template, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT.
```

Then tag `v1.11.0` and push `main` and the tag. GitHub Actions builds both Minecraft versions on
the push, on Linux, which double-checks the build away from your PC's antivirus.
After pushing, turn on **Settings → Code security → Private vulnerability reporting** on GitHub,
so the "Report a vulnerability" link in SECURITY.md works. Then open **New issue** once to check
that the Bug report and Feature request forms show up.
(Only `v1.7.0` is tagged so far; 1.8.0 to 1.10.0 have no tags. Adding them is optional.)

## 4. Publish
1. **GitHub release** for `v1.11.0`: notes from `curseforge-kit/3-changelog-1.11.0.md`, the four
   jars from `dist\` attached.
2. **CurseForge:** follow `curseforge-kit/CHECKLIST.md` (description, then four files).
3. **Modrinth:** follow `modrinth-kit/CHECKLIST.md` (description, then four versions).

Use the same changelog everywhere. Do all three on the same day, so nobody finds a version on one
site that isn't on the others.
