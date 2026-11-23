# Contributing to Farground

Thanks for helping! Bug reports, ideas, translations and code are all welcome.

## Reporting a bug
Use the **Bug report** form under [Issues](https://github.com/Benny1526/farground/issues/new/choose).
The most useful things are the Farground, Minecraft and loader versions, your LOD mod, and a link
to `logs/latest.log` (upload it to [mclo.gs](https://mclo.gs)). For slowness, a
[spark](https://spark.lucko.me) profile taken while Farground generates helps a lot:
`/spark profiler start --thread * --timeout 180`.

## Translations
Language files are in `src/main/resources/assets/farground/lang/`. Copy `en_us.json`, translate the
values (not the keys), and keep `%s` placeholders and formatting codes as they are. A pull request
or a file attached to an issue both work.

## Building
You need Java 25. Fabric:
```
gradlew build -Pmc=26.2
gradlew build -Pmc=26.3
```
NeoForge:
```
gradlew -p neoforge build -Pmc=26.2
gradlew -p neoforge build -Pmc=26.3
```
Jars end up in `build/libs/` and `neoforge/build/libs/`. In PowerShell, quote the version:
`"-Pmc=26.3"`. GitHub Actions builds both Minecraft versions on every push and pull request.

On Windows, if Gradle fails with "Unable to establish loopback connection", run this first:
```
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$env:USERPROFILE\.gradle\sock"
```

## Where the code lives
- `src/main/java/dev/farground/`: everything shared by both loaders and both Minecraft versions.
  `Generator` is the core (what to generate next, how fast, when to wait).
- `src/main/java/dev/farground/fabric/` and `neoforge/src/`: the small loader-specific parts.
  Shared code talks to the loader through `Platform`.
- `src/mc26.2/` and `src/mc26.3/`: code and mixins for one Minecraft version only. Both builds add
  the folder for the version being built. Each has its own `farground.mixins.json`.
- `versions/<mc>.properties` and `neoforge/versions/<mc>.properties`: library versions per
  Minecraft version.

## Pull requests
- Keep a pull request to one change; small ones get reviewed faster.
- Make sure both Minecraft versions build, and NeoForge too if you changed shared code.
- Say how you tested it in-game (version, loader, LOD mod), or that you didn't.
- Add a line to `CHANGELOG.md` if players would notice the change, and put new messages in
  `en_us.json` rather than in the code.
- For something big, open an issue first so we can agree on the approach before you spend time on it.

By contributing, you agree that your contribution is released under the project's
[MIT license](LICENSE).
