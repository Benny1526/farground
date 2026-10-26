# Publishing Farground on Modrinth

1. Sign in at modrinth.com → **Create a project** → type **Mod**, name **Farground**.
2. Summary: "Fast, lightweight background terrain generation for Voxy and Distant Horizons."
3. Description: paste `MODRINTH.md`.
4. Categories: Optimization, Worldgen, Utility. Environment: client and server
   (works on either; required on whichever side generates).
5. License: MIT. Source: your GitHub repo (optional; `.github/workflows/build.yml` builds
   both versions there).
6. **Upload a version** for each jar in `dist/`:
   - `farground-1.7.0+26.3.jar` → game version 26.3, loader Fabric
   - `farground-1.7.0+26.2.jar` → game version 26.2, loader Fabric
   - Version number: `1.7.0+26.3` / `1.7.0+26.2`, release channel Beta.
   - Changelog: the 1.5.0 to 1.7.0 sections of `CHANGELOG.md`.
   - Dependencies: Fabric API (required); Voxy and Distant Horizons (both optional:
     players need one of them, which the description says); Mod Menu, Cloth Config and
     Voxy Server (optional).
7. Icon: upload `logo-400.png` from this folder. For CurseForge, see `CURSEFORGE.md`.
8. Add a screenshot or two (a far view from a mountain works well) and submit for review.
