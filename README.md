<img src="https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/main/_Misc%20Files/logo%20files/new/SVG/Distant-Horizons-Plugin.svg?ref_type=heads" height="128" alt="Plugin logo">

# Distant Horizons Server Plugin

> EaseCation production fork. `origin` is
> `git@github.com:EaseCation/DistantHorizonsSupport.git`; upstream releases come from
> `https://gitlab.com/distant-horizons-team/distant-horizons-server-plugin.git`.
> The first maintained release is `0.14.0-ec.1`, based on upstream `0.14.0`.

DH Support is a Bukkit/Spigot/Paper/Folia server plugin that transmits Level Of Detail (LOD) data to players with Distant Horizons installed. Distant Horizons _will_ work fine without this plugin, but then each client will have to be within normal view distance of chunks to load them, and they will not receive updates for distant chunks when they change. 

## Installing and configuring

Please see [our wiki](https://gitlab.com/distant-horizons-team/distant-horizons-server-plugin/-/wikis/home) for details.

## Building

This repository is built as the `DistantHorizonsSupport` included build in GeyserWorkspace. The fork intentionally
does not carry a second Gradle wrapper. From the GeyserWorkspace root run:

```shell
./gradlew :DistantHorizonsSupport:api:test :DistantHorizonsSupport:test
./gradlew :DistantHorizonsSupport:build
```

The production Runtime API and artifact contract are documented in [docs/runtime-api.md](docs/runtime-api.md).

## Updating from upstream

```shell
git fetch upstream --tags
git switch -c upgrade/<version> <upstream-tag>
git cherry-pick <EaseCation-runtime-api-commits>
```

Run the GeyserWorkspace root build and database compatibility tests before creating
`<upstream-version>-ec.<patch>`. Runtime API major version and plugin version are intentionally independent.

## Contribution

There are several ways to contribute to this project. You can offer your feedback on [the DH Discord](https://discord.gg/xAB8G4cENx), report any issues or bugs you find, or attack an open issue and submit a pull request ❤️

![Plugin usage statistics](https://bstats.org/signatures/bukkit/DH%20Support.svg)
