<p align="center">
<img src="icon_big.png" width="512" height="512">
</p>
<h1 align="center">
Enderkinesis
</h1>
<p align="center">
There are forces older than time itself. Push your ship with them!
</p>

## Requirements

- **Valkyrien Skies 2** — the `1.20.1/playtest` branch (until it is merged), pinned in
  `gradle.properties` to `vs2_version=2.4.13+c3fbe5f60e` / `vscore_version=1.1.0+af5183035b`
  (the playtest HEAD on the Valkyrien Skies maven). Bump instructions are in `gradle.properties`.
- Architectury, Fabric API + fabric-language-kotlin (Fabric), Kotlin for Forge (Forge).

## LLM Disclosure
<p>LLMs are used in the creation of this project, because I care far more about the project existing than I care about enjoying the process of writing code. This is not to disparage the efforts and talents and artwork that goes into programming, I simply choose to spend my time more on the design and art of this project than the programming of this project.
</p>
<p>
I will always work to ensure that, to the best of my ability, LLM usage is kept as ethical as possible. I do know how to write code and make mods, and there will always be eyes on any code that makes it to the end user's computer.
</p>
<p><b>There will never be any usage of any AI generated artwork or assets in this project.</b> In this house we only use real talented artists, and when possible, we pay them. The only job that the LLM will ever replace is my own.</p>

## Building

Run `./gradlew clean build`. Output jars: `fabric/build/libs/` and `forge/build/libs/`
(the unclassified `enderkinesis-<version>.jar`, not the `-dev`/`-dev-shadow` ones).


## License

Enderkinesis uses a layered license. Short version:

- **Code** (`*.kt`, `*.java`, build scripts, JSON data, mixin configs) — **Apache License 2.0**. Full text in `LICENSE`. Fork it, modify it, bundle it in any modpack, use it commercially. Just keep the `LICENSE` and `NOTICE` files with your distribution.
- **Name & branding** — the "Enderkinesis" name and logo are reserved by Apache 2.0 §6 (the license does not grant trademark rights). Ports to other editions / loaders / MC versions are explicitly fine *under a distinct name*. What's not allowed is releasing a competing standalone redistribution under the Enderkinesis name.
- **Assets** (textures, models, sounds, language files, lore, `*.nbt` structures, `icon_big.png`) — **All Rights Reserved**, with a narrow grant to convey them as bundled inside the mod jar (so modpacks just work). Don't extract, retexture, or reuse them in other projects without permission. See `LICENSE-ASSETS` for the full terms.
