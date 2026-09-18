<h1 align="center">Flashback</h1>

Copyright 2024 Moulberry. Do not reupload or redistribute.

Flashback is a Minecraft mod which allows you to record your Minecraft gameplay, play it back and create stunning cinematic shots

**This fork ports Flashback to Minecraft 26.3 ("Wilderness Bound").** Upstream: https://github.com/Moulberry/Flashback

## How to install
1. Install Fabric (https://fabricmc.net/wiki/install) for Minecraft 26.3
2. Download the JAR from this repo's [Releases](https://github.com/ewitulsk/EvansFlashbackFork/releases)
3. Install Flashback's JAR into your mods folder (.minecraft/mods)
4. Run the game

## Dependencies

Required:

- [Minecraft](https://www.minecraft.net/) 26.3 (`>26.1 <26.4`)
- [Java](https://adoptium.net/) 25 or newer
- [Fabric Loader](https://fabricmc.net/) 0.15.10 or newer (developed/tested on 0.19.5)
- [Fabric API](https://modrinth.com/mod/fabric-api) 0.160.7+26.3 or newer

Bundled (jar-in-jar, no separate install needed):

- Lattice 2.1.0-26.3 (https://github.com/Moulberry/Lattice)
- FFmpeg natives (javacv-platform)
- imgui-java natives

Optional compatibility (verified against these 26.3 builds):

- [Sodium](https://modrinth.com/mod/sodium) 0.9.2+
- [Iris](https://modrinth.com/mod/iris) 1.11.6+
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) 2.6.23+ (Flashback voicechat plugin)
- [Distant Horizons](https://modrinth.com/mod/distanthorizons) 3.3.0+
- [Axiom](https://modrinth.com/mod/axiom) 6.1.1+ (targets statically verified)
- [Mod Menu](https://modrinth.com/mod/modmenu) 21.0.0+
- [ScalableLux](https://modrinth.com/mod/scalablelux)
- [Bobby](https://modrinth.com/mod/bobby) — no 26.3 build yet; compat mixins stay dormant

## Support

If you need assistance installing or using the mod, feel free to join the [discord](https://discord.gg/flashbacktool) and ask for help in #support

## Contributing

Flashback currently does not accept outside contributions

## License

This project is licensed with a custom license, see [LICENSE.md](https://github.com/Moulberry/Flashback/blob/master/LICENSE.md)
The localization files (src/main/resources/assets/flashback/lang) are licensed separately under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)
