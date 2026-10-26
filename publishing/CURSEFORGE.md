# Publishing Farground on CurseForge

## Before you start
- Farground needs **Voxy or Distant Horizons**. Distant Horizons is on CurseForge, so it can
  be listed as a dependency. **Voxy is not** (it's Modrinth-only), so the description tells
  Voxy players where to get it.
- CurseForge reviews new projects by hand; the first approval can take a day or more.
- `logo-400.png` in this folder is the project logo (CurseForge requires one).

## Steps
1. Sign in at curseforge.com → **Create Project** → game **Minecraft**, class **Mods**.
2. Name: **Farground**. Summary: "Fast, lightweight background terrain generation for
   Voxy and Distant Horizons."
3. Logo: upload `logo-400.png`.
4. Categories: Performance (main), World Gen, Utility. License: MIT.
   Source / issues links: https://github.com/Benny1526/farground
5. Description: switch the editor to Markdown and paste `MODRINTH.md`, then add this
   section near the top:

   > **Needs Fabric API and one LOD mod: Distant Horizons or Voxy.** Distant Horizons is
   > right here on CurseForge. Voxy is only on [Modrinth](https://modrinth.com/mod/voxy);
   > download it there and put it in your mods folder next to Farground.

6. Upload files (one per Minecraft version), from `dist/`:
   - `farground-1.7.0+26.3.jar`: game version 26.3, mod loader Fabric, Java 25, release type Beta.
   - `farground-1.7.0+26.2.jar`: game version 26.2, mod loader Fabric, Java 25, release type Beta.
   - Display name: `Farground 1.7.0 (26.3)` / `Farground 1.7.0 (26.2)`.
   - Changelog: the 1.5.0 to 1.7.0 sections of `CHANGELOG.md`.
   - Relations: Fabric API → **Required dependency**; Distant Horizons → **Optional
     dependency** (it's one of two choices, so not "required"); Mod Menu and Cloth Config →
     **Optional dependency**. (Voxy and Voxy Server can't be added; covered in the description.)
7. Submit for review.

## Keeping both sites in step
Upload the same jar to Modrinth and CurseForge for each release, with the same changelog.
